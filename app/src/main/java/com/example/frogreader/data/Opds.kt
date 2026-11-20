package com.example.frogreader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

@Serializable
internal data class OpdsCatalog(
    val url: String,
    val title: String,
    val enabled: Boolean = true,
    val responseMillis: Long? = null,
    val reachable: Boolean? = null,
)

internal class OpdsCatalogStore(directory: File) {
    private val file = AtomicJsonFile(File(directory, "opds-catalogs.json"), Json { ignoreUnknownKeys = true }, ListSerializer(OpdsCatalog.serializer()))
    private val lock = Mutex()

    suspend fun read(): List<OpdsCatalog> = withContext(Dispatchers.IO) {
        lock.withLock { file.readOrDefault(emptyList()) }
    }

    suspend fun update(change: (List<OpdsCatalog>) -> List<OpdsCatalog>): List<OpdsCatalog> = withContext(Dispatchers.IO) {
        lock.withLock { change(file.readOrDefault(emptyList())).also(file::write) }
    }
}

internal data class OpdsLink(val url: String, val title: String, val type: String, val relations: Set<String>)
internal data class OpdsPublication(
    val title: String,
    val author: String,
    val links: List<OpdsLink>,
    val catalog: String,
    val details: OpdsLink? = null,
    val coverUrl: String? = null,
) {
    val key get() = details?.url ?: links.first().url
}
internal data class OpdsFeed(
    val title: String,
    val url: String,
    val navigation: List<OpdsLink>,
    val publications: List<OpdsPublication>,
    val search: OpdsLink?,
    val next: OpdsLink?,
    val groups: List<OpdsFeed> = emptyList(),
    val catalogRoot: String? = null,
    val previous: OpdsLink? = null,
) {
    val hasContent: Boolean get() = navigation.isNotEmpty() || publications.isNotEmpty() || next != null || previous != null || groups.any { it.hasContent }
}

internal class InvalidOpdsFeed : IOException()

internal object Opds {
    private const val Acquisition = "http://opds-spec.org/acquisition"
    private const val FeedAccept = "application/opds+json, application/opds-publication+json, application/atom+xml"
    private const val MaxFeedBytes = 8L * 1024 * 1024
    private val TemplateExpression = Regex("""\{([?&]?)([^}]+)\}""")

    fun address(value: String, base: String? = null): String {
        val reference = URI(value.trim().replace(" ", "%20"))
        val url = when {
            base == null -> reference
            reference.rawPath == "" && reference.rawAuthority == null && reference.scheme == null -> {
                val suffix = reference.toString()
                val parent = if (suffix.startsWith('?')) base.substringBefore('#').substringBefore('?') else base.substringBefore('#')
                URI(parent + suffix)
            }
            else -> URI(base).resolve(reference)
        }
        require(url.scheme?.lowercase() in setOf("http", "https") && url.host != null && url.userInfo == null)
        return url.toASCIIString()
    }

    suspend fun feed(url: String, catalog: String = ""): OpdsFeed =
        request(url, FeedAccept) { input, finalUrl -> parse(readDocument(input), finalUrl, catalog) }

    suspend fun fromDocument(input: InputStream): OpdsCatalog = runInterruptible(Dispatchers.IO) {
        val feed = parse(readDocument(input), "")
        val root = feed.catalogRoot ?: throw InvalidOpdsFeed()
        OpdsCatalog(root, feed.title.ifBlank { URI(root).host })
    }

    suspend fun search(catalog: OpdsCatalog, query: String): OpdsFeed? {
        val current = feed(catalog.url, catalog.title)
        val root = if (current.search == null && current.catalogRoot != null && current.catalogRoot != current.url) {
            feed(current.catalogRoot, catalog.title)
        } else current
        val link = root.search ?: return null
        val template = if ('{' in link.url) link.url else {
            request(link.url, "application/opensearchdescription+xml, application/xml, text/xml") { input, finalUrl ->
                val description = Jsoup.parse(readDocument(input), finalUrl, Parser.xmlParser())
                val urls = description.getAllElements().filter { it.localName().equals("Url", ignoreCase = true) }
                urls.firstOrNull { url ->
                    val type = url.attr("type").trim().lowercase()
                    (type.isBlank() || type.startsWith("application/atom+xml") || type.startsWith("application/opds+json")) &&
                        TemplateExpression.findAll(url.attr("template")).any { match ->
                            match.groupValues[2].removeSuffix("?") in setOf("searchTerms", "query")
                        }
                }?.let { resolveTemplate(finalUrl, it.attr("template")) }
            } ?: return null
        }
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name()).replace("+", "%20")
        val url = TemplateExpression.replace(template) { match ->
            val prefix = match.groupValues[1]
            val variables = match.groupValues[2].split(',')
            val name = variables.firstOrNull { it.removeSuffix("?") in setOf("query", "searchTerms") }
            when {
                name == null -> ""
                prefix.isEmpty() -> encoded
                else -> "$prefix${name.removeSuffix("?")}=$encoded"
            }
        }
        if (url == template || '{' in url) return null
        return feed(url, catalog.title)
    }

    fun extension(link: OpdsLink): String? = when (link.type.substringBefore(';').lowercase()) {
        "application/epub+zip" -> "epub"
        "application/x-fictionbook+xml", "application/x-fictionbook" -> "fb2"
        "application/x-mobipocket-ebook", "application/x-mobi" -> "mobi"
        "application/vnd.amazon.ebook", "application/vnd.amazon.mobi8-ebook" -> "azw3"
        "text/plain" -> "txt"
        "text/markdown", "text/x-markdown" -> "md"
        else -> runCatching { URI(link.url).path.lowercase() }.getOrNull()?.let { path ->
            listOf("fb2.zip", "epub", "fb2", "mobi", "azw3", "txt", "md", "pdb").firstOrNull { path.endsWith(".$it") }
        }
    }

    suspend fun acquisition(book: OpdsPublication): OpdsPublication {
        if (book.links.isNotEmpty()) return book
        val details = book.details ?: throw InvalidOpdsFeed()
        val full = feed(details.url, book.catalog).publications.firstOrNull { it.links.isNotEmpty() }
            ?: throw InvalidOpdsFeed()
        return book.copy(links = full.links)
    }

    suspend fun coverPreview(url: String): ByteArray =
        request(url, "image/*") { input, _ ->
            java.io.ByteArrayOutputStream().use { output ->
                copyBounded(input, output, 2L * 1024 * 1024)
                output.toByteArray()
            }
        }

    suspend fun download(link: OpdsLink, file: File, onProgress: (Float?) -> Unit = {}) {
        var size = -1L
        var previousPercent = -1
        request(link.url, link.type.ifBlank { "application/octet-stream" }, onContentLength = { size = it }) { input, _ ->
            onProgress(if (size > 0) 0f else null)
            file.outputStream().use { output ->
                copyBounded(input, output, 256L * 1024 * 1024) { received ->
                    if (size > 0) {
                        val percent = (received * 100 / size).coerceAtMost(100).toInt()
                        if (percent != previousPercent) {
                            previousPercent = percent
                            onProgress(percent / 100f)
                        }
                    }
                }
            }
        }
    }

    internal fun parse(body: String, url: String, catalog: String = ""): OpdsFeed = try {
        when (body.trimStart().removePrefix("\uFEFF").trimStart().firstOrNull()) {
            '{' -> jsonFeed(Json.parseToJsonElement(body.trim().removePrefix("\uFEFF")).jsonObject, url, catalog, depth = 0)
            '<' -> atomFeed(body, url, catalog)
            else -> throw InvalidOpdsFeed()
        }
    } catch (e: IllegalArgumentException) {
        throw InvalidOpdsFeed()
    }

    private fun jsonFeed(root: JsonObject, url: String, catalog: String, depth: Int): OpdsFeed {
        val metadata = root["metadata"] as? JsonObject ?: throw InvalidOpdsFeed()
        val title = metadata["title"].label()
        if (depth == 0 && (title.isBlank() || root.keys.none { it in setOf("links", "navigation", "publications", "groups") })) {
            throw InvalidOpdsFeed()
        }
        val links = root.opdsLinks(url)
        val publications = root.objects("publications").mapNotNull { entry ->
            val info = entry["metadata"] as? JsonObject ?: return@mapNotNull null
            publication(info["title"].label(), info["author"].label(), entry.opdsLinks(url), catalog.ifBlank { title }, entry.objects("images").mapNotNull { it.toLink(url) }.lastOrNull()?.url)
        }.toList()
        val single = publication(title, metadata["author"].label(), links, catalog.ifBlank { title }, root.objects("images").mapNotNull { it.toLink(url) }.lastOrNull()?.url)
            ?.takeIf { it.links.isNotEmpty() }
        val groups = if (depth < 4) root.objects("groups").map { jsonFeed(it, url, catalog.ifBlank { title }, depth + 1) }.toList() else emptyList()
        val navigation = root.objects("navigation").mapNotNull { it.toLink(url) } +
            root.objects("facets").flatMap { it.opdsLinks(url).asSequence() }
        return OpdsFeed(title, url, navigation.toList(), publications + listOfNotNull(single),
            links.firstOrNull { "search" in it.relations }, links.pageLink(url, "next"), groups,
            catalogRoot(links), previous = links.pageLink(url, "previous", "prev"))
    }

    private fun atomFeed(body: String, url: String, catalog: String): OpdsFeed {
        val root = Jsoup.parse(body, url, Parser.xmlParser()).children().firstOrNull()
            ?.takeIf { it.localName() in setOf("feed", "entry") } ?: throw InvalidOpdsFeed()
        val title = root.direct("title").firstOrNull()?.text().orEmpty()
        val links = root.atomLinks(url)
        val navigation = links.filter { link ->
            link.relations.any {
                it in setOf("http://opds-spec.org/facet", "subsection", "collection", "http://opds-spec.org/sort/new",
                    "http://opds-spec.org/sort/popular", "http://opds-spec.org/featured",
                    "http://opds-spec.org/recommended")
            } && link.isCatalogLink()
        }.toMutableList()
        val books = mutableListOf<OpdsPublication>()
        val entries = if (root.localName() == "entry") listOf(root) else root.direct("entry")
        for (entry in entries) {
            val entryTitle = entry.direct("title").firstOrNull()?.text().orEmpty()
            val author = entry.direct("author").mapNotNull { it.direct("name").firstOrNull()?.text() }.joinToString("、")
            val entryLinks = entry.atomLinks(url)
            val book = publication(entryTitle, author, entryLinks, catalog.ifBlank { title })
            if (book != null) books += book
            else entryLinks.firstOrNull { it.isCatalogLink() && "alternate" !in it.relations }
                ?.let { navigation += it.copy(title = entryTitle.ifBlank { it.title }) }
        }
        return OpdsFeed(title, url, navigation.distinctBy { it.url }, books, links.firstOrNull { "search" in it.relations },
            links.pageLink(url, "next"), catalogRoot = catalogRoot(links),
            previous = links.pageLink(url, "previous", "prev"))
    }

    private fun List<OpdsLink>.pageLink(currentUrl: String, vararg relations: String): OpdsLink? {
        val current = firstOrNull { "self" in it.relations }?.url ?: currentUrl
        val boundary = if ("next" in relations) "last" else "first"
        if (any { boundary in it.relations && it.url == current }) return null
        return firstOrNull { link -> link.url != current && link.url != currentUrl && relations.any { it in link.relations } }
    }

    private fun OpdsLink.isCatalogLink(): Boolean =
        type.trim().lowercase().let { it.startsWith("application/atom+xml") || it.startsWith("application/opds+json") } ||
            (type.isBlank() && relations.any { it in setOf("subsection", "collection") })

    private fun catalogRoot(links: List<OpdsLink>): String? =
        (links.firstOrNull { "start" in it.relations || "root" in it.relations }
            ?: links.firstOrNull { "self" in it.relations })?.url

    private fun publication(
        title: String, author: String, links: List<OpdsLink>, catalog: String, image: String? = null,
    ): OpdsPublication? {
        if (title.isBlank()) return null
        val downloads = links.filter { link ->
            link.relations.any { it == Acquisition || it == "$Acquisition/open-access" || it == "download" || it == "acquisition" } &&
                extension(link) != null
        }
        val details = links.firstOrNull { link ->
            ("alternate" in link.relations && link.type.startsWith("application/atom+xml") && "type=entry" in link.type) ||
                ("self" in link.relations && link.type.startsWith("application/opds-publication+json"))
        }
        val cover = image ?: links.firstOrNull { "http://opds-spec.org/image/thumbnail" in it.relations }?.url
            ?: links.firstOrNull { "http://opds-spec.org/image" in it.relations }?.url
        return if (downloads.isEmpty() && details == null) null
            else OpdsPublication(title, author, downloads.distinctBy { it.url to it.type }, catalog, details, cover)
    }

    private fun Element.localName() = tagName().substringAfter(':')
    private fun Element.direct(name: String) = children().filter { it.localName() == name }
    private fun Element.atomLinks(base: String) = direct("link").mapNotNull { element ->
        val ancestors = generateSequence(element) { it.parent() }.toList().asReversed()
        val linkBase = ancestors.fold(base) { parent, node ->
            node.attr("xml:base").takeIf(String::isNotBlank)?.let { runCatching { address(it, parent) }.getOrNull() } ?: parent
        }
        element.attr("href").takeIf(String::isNotBlank)?.let { href ->
            runCatching { resolveTemplate(linkBase, href) }.getOrNull()?.let { resolved ->
                OpdsLink(resolved, element.attr("title"), element.attr("type"), element.attr("rel").split(' ').toSet())
            }
        }
    }

    private fun JsonObject.objects(key: String) = (this[key] as? JsonArray).orEmpty().asSequence().filterIsInstance<JsonObject>()
    private fun JsonElement?.label(): String = when (this) {
        is JsonPrimitive -> contentOrNull.orEmpty()
        is JsonArray -> mapNotNull { it.label().takeIf(String::isNotBlank) }.joinToString("、")
        is JsonObject -> this["name"]?.label() ?: values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        else -> ""
    }
    private fun JsonObject.toLink(base: String): OpdsLink? {
        val href = this["href"].label().takeIf(String::isNotBlank) ?: return null
        val rel = when (val value = this["rel"]) {
            is JsonArray -> value.map { it.label() }.toSet()
            else -> setOf(value.label())
        }
        return runCatching { resolveTemplate(base, href) }.getOrNull()?.let {
            OpdsLink(it, this["title"].label(), this["type"].label(), rel)
        }
    }
    private fun JsonObject.opdsLinks(base: String) = objects("links").mapNotNull { it.toLink(base) }.toList()

    private fun resolveTemplate(base: String, href: String): String {
        if ('{' !in href) return address(href, base)
        val expressions = TemplateExpression.findAll(href).map { it.value }.toList()
        var escaped = href
        expressions.forEachIndexed { index, expression -> escaped = escaped.replaceFirst(expression, "__opds_var_${index}__") }
        var resolved = when {
            href.startsWith("{?") -> base.substringBefore('#').substringBefore('?') + escaped
            href.startsWith("{&") -> base.substringBefore('#') + escaped
            else -> address(escaped, base)
        }
        expressions.forEachIndexed { index, expression -> resolved = resolved.replace("__opds_var_${index}__", expression) }
        return resolved
    }

    private fun readDocument(input: InputStream): String =
        java.io.ByteArrayOutputStream().use { output ->
            copyBounded(input, output, MaxFeedBytes)
            output.toString(Charsets.UTF_8.name())
        }

    private fun copyBounded(
        input: InputStream, output: java.io.OutputStream, limit: Long, onBytes: (Long) -> Unit = {},
    ) {
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IOException()
            output.write(buffer, 0, count)
            onBytes(total)
        }
    }

    private suspend fun <T> request(
        url: String,
        accept: String,
        onContentLength: (Long) -> Unit = {},
        read: (InputStream, String) -> T,
    ): T =
        runInterruptible(Dispatchers.IO) {
            var current = address(url)
            repeat(6) {
                val connection = URI(current).toURL().openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 8_000
                    connection.readTimeout = 12_000
                    connection.setRequestProperty("User-Agent", "FrogReader OPDS")
                    connection.setRequestProperty("Accept", accept)
                    val status = connection.responseCode
                    if (status in listOf(301, 302, 303, 307, 308)) {
                        current = address(connection.getHeaderField("Location") ?: throw IOException(), current)
                    } else {
                        if (status !in 200..299) throw IOException()
                        onContentLength(connection.contentLengthLong)
                        return@runInterruptible connection.inputStream.use { read(it, current) }
                    }
                } finally {
                    connection.disconnect()
                }
            }
            throw IOException()
        }
}
