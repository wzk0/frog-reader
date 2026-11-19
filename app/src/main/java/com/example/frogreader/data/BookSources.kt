package com.example.frogreader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

@Serializable
internal enum class SourceConnection { Connected, TimedOut, Failed }

@Serializable
internal data class SourceSubscription(val id: String, val name: String)

internal val LegacySourceSubscription = SourceSubscription("legacy", "")

@Serializable
internal data class BookSource(
    val bookSourceUrl: String,
    val bookSourceName: String,
    val searchUrl: String = "",
    val enabled: Boolean = true,
    val favorite: Boolean = false,
    val connection: SourceConnection? = null,
    val responseMillis: Long? = null,
    val subscriptions: List<SourceSubscription> = listOf(LegacySourceSubscription),
    val header: JsonElement? = null,
    val ruleSearch: SourceSearchRule = SourceSearchRule(),
    val exploreUrl: String = "",
    val enabledExplore: Boolean = true,
    val ruleExplore: SourceSearchRule = SourceSearchRule(),
    val ruleBookInfo: SourceBookInfoRule = SourceBookInfoRule(),
    val ruleToc: SourceTocRule = SourceTocRule(),
    val ruleContent: SourceContentRule = SourceContentRule(),
)

@Serializable
internal data class SourceSearchRule(val bookList: String = "", val name: String = "", val author: String = "", val bookUrl: String = "", val kind: String = "")
@Serializable
internal data class SourceBookInfoRule(val tocUrl: String = "")
@Serializable
internal data class SourceTocRule(val chapterList: String = "", val chapterName: String = "", val chapterUrl: String = "", val nextTocUrl: String = "")
@Serializable
internal data class SourceContentRule(val content: String = "", val nextContentUrl: String = "")

internal class ReplacementRulesFileException : IllegalArgumentException()
internal class UnsupportedSourceSearchException : IllegalArgumentException()
internal class MissingSourceChaptersException : IOException()
internal class MissingSourceContentException : IOException()

private fun String.requiresScript(): Boolean =
    contains("<js>", ignoreCase = true) || contains("@js:", ignoreCase = true)

internal class BookSourceStore(directory: File) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true; isLenient = true; allowTrailingComma = true }
    private val serializer = ListSerializer(BookSource.serializer())
    private val file = AtomicJsonFile(File(directory, "book-sources.json"), json, serializer)
    private val mutex = Mutex()

    suspend fun read(): List<BookSource> = withContext(Dispatchers.IO) {
        mutex.withLock { file.readOrDefault(emptyList()) }
    }

    suspend fun import(value: String, subscription: SourceSubscription = LegacySourceSubscription): List<BookSource> {
        val root = json.parseToJsonElement(value.trimStart().removePrefix("\uFEFF"))
        val payload = if (root is JsonObject) root["bookSources"] ?: root["data"] ?: root else root
        val entries = if (payload is JsonArray) payload else listOf(payload)
        if (entries.isNotEmpty() && entries.all {
                it is JsonObject && "pattern" in it && "replacement" in it && "bookSourceUrl" !in it
            }
        ) throw ReplacementRulesFileException()
        val sources = entries.mapNotNull { entry ->
            val fields = (entry as? JsonObject)?.toMutableMap() ?: return@mapNotNull null
            val address = (fields["bookSourceUrl"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (address.isBlank()) return@mapNotNull null
            val name = (fields["bookSourceName"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            listOf("connection", "responseMillis", "subscriptions", "favorite").forEach(fields::remove)
            fields["bookSourceUrl"] = JsonPrimitive(address)
            fields["bookSourceName"] = JsonPrimitive(name.ifBlank { address })
            val enabled = (fields["enabled"] as? JsonPrimitive)?.contentOrNull
            when (enabled?.lowercase()) {
                "1", "true" -> fields["enabled"] = JsonPrimitive(true)
                "0", "false" -> fields["enabled"] = JsonPrimitive(false)
                else -> fields.remove("enabled")
            }
            for (key in listOf("ruleSearch", "ruleExplore", "ruleBookInfo", "ruleToc", "ruleContent")) {
                val rule = fields[key]
                val parsed = if (rule is JsonPrimitive && rule.isString) {
                    runCatching { json.parseToJsonElement(rule.content) }.getOrNull()
                } else rule
                if (parsed is JsonObject) fields[key] = parsed else fields.remove(key)
            }
            runCatching { json.decodeFromJsonElement(BookSource.serializer(), JsonObject(fields)) }.getOrNull()
        }
        require(sources.isNotEmpty())
        return update { existing ->
            val previous = existing.associateBy(BookSource::bookSourceUrl)
            val imported = sources.map { source ->
                val old = previous[source.bookSourceUrl]
                source.copy(
                    enabled = old?.enabled ?: source.enabled,
                    favorite = old?.favorite ?: false,
                    connection = old?.connection,
                    responseMillis = old?.responseMillis,
                    subscriptions = (old?.subscriptions.orEmpty() + subscription).associateBy { it.id }.values.toList(),
                )
            }
            (existing + imported).associateBy(BookSource::bookSourceUrl).values.toList()
        }
    }

    suspend fun importFromStream(stream: InputStream, subscription: SourceSubscription = LegacySourceSubscription): List<BookSource> = withContext(Dispatchers.IO) {
        import(readSourceDocument(stream), subscription)
    }

    suspend fun importFromUrl(link: String): List<BookSource> = withContext(Dispatchers.IO) {
        val address = link.trim()
        val url = webUrl(address, address)
        val response = Jsoup.connect(url)
            .timeout(12000)
            .maxBodySize(MaxSourceDocumentBytes + 1)
            .ignoreContentType(true)
            .execute()
        val uri = URI(url)
        val name = uri.path.trimEnd('/').substringAfterLast('/').ifBlank { uri.host }
        response.bodyStream().use { import(readSourceDocument(it), SourceSubscription("url:$url", name)) }
    }

    suspend fun update(transform: (List<BookSource>) -> List<BookSource>): List<BookSource> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val sources = transform(file.readOrDefault(emptyList()))
            file.write(sources)
            sources
        }
    }
}

private const val MaxSourceDocumentBytes = 16 * 1024 * 1024

private suspend fun readSourceDocument(stream: InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        coroutineContext.ensureActive()
        val bytes = stream.read(buffer)
        if (bytes < 0) break
        require(output.size() + bytes <= MaxSourceDocumentBytes)
        output.write(buffer, 0, bytes)
    }
    val bytes = output.toByteArray()
    val utf16 = bytes.size >= 2 && (
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ||
            bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()
        )
    return bytes.toString(if (utf16) Charsets.UTF_16 else Charsets.UTF_8)
}

internal data class SourceBook(val source: BookSource, val title: String, val author: String, val url: String, val category: String = "")

internal data class SourceDiscovery(val source: BookSource, val title: String, val url: String)

internal fun BookSource.discoveryCategories(): List<SourceDiscovery> {
    if (!enabled || !enabledExplore || exploreUrl.contains("<js>", ignoreCase = true) ||
        exploreUrl.contains("@js:", ignoreCase = true)
    ) return emptyList()
    return exploreUrl.split(Regex("\\r?\\n|&&")).mapNotNull { entry ->
        val title = entry.substringBefore("::").trim()
        val url = entry.substringAfter("::", "").trim()
        if (title.isEmpty() || url.isEmpty()) null else SourceDiscovery(this, title, url)
    }
}

internal object BookSourceReader {
    suspend fun checkConnection(source: BookSource): Boolean = withContext(Dispatchers.IO) {
        val address = source.bookSourceUrl.substringBefore(',').trim()
        val url = webUrl(address, address)
        val response = Jsoup.connect(url)
            .timeout(6_000)
            .maxBodySize(1)
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .execute()
        response.bodyStream().close()
        true
    }

    suspend fun search(
        source: BookSource,
        key: String,
        onConnected: suspend () -> Unit = {},
    ): List<SourceBook> = fetchBooks(source, source.searchUrl, source.ruleSearch, key, onConnected)

    suspend fun explore(category: SourceDiscovery, onConnected: suspend () -> Unit = {}): List<SourceBook> =
        fetchBooks(category.source, category.url, category.source.ruleExplore, "", onConnected)

    private suspend fun fetchBooks(
        source: BookSource,
        urlTemplate: String,
        rule: SourceSearchRule,
        key: String,
        onConnected: suspend () -> Unit,
    ): List<SourceBook> = withContext(Dispatchers.IO) {
        if (listOf(urlTemplate, rule.bookList, rule.name).any(String::isBlank) ||
            listOf(urlTemplate, rule.bookList, rule.name, rule.bookUrl).any(String::requiresScript)
        ) throw UnsupportedSourceSearchException()
        val page = request(source.bookSourceUrl, urlTemplate, key, source.header, onConnected)
        val rows = page.rows(rule.bookList).take(60).toList()
        val books = rows.mapNotNull { row ->
            val title = row.value(rule.name)
            val link = row.firstValue(rule.bookUrl).ifBlank {
                (row as? RuleNode.Html)?.link().orEmpty()
            }
            val url = runCatching { resolveSourceUrl(page.url, link) }.getOrNull()
            if (title.isBlank() || link.isBlank() || url == null) null
            else SourceBook(source, title, row.value(rule.author), url, row.value(rule.kind).trim())
        }.distinctBy(SourceBook::url)
        books
    }

    suspend fun writeBook(book: SourceBook, file: File) = withContext(Dispatchers.IO) {
        val source = book.source
        val info = request(book.url, book.url, header = source.header)
        val tocRule = source.ruleToc
        val contentRule = source.ruleContent
        if (listOf(source.ruleBookInfo.tocUrl, tocRule.chapterList, tocRule.chapterName,
                tocRule.chapterUrl, tocRule.nextTocUrl, contentRule.content, contentRule.nextContentUrl)
                .any(String::requiresScript)
        ) throw UnsupportedSourceSearchException()
        val tocLink = info.root.firstValue(source.ruleBookInfo.tocUrl)
        val chapters = linkedMapOf<String, String>()
        if (tocRule.chapterList.isBlank()) {
            chapters[info.url] = book.title
        } else {
            val firstToc = if (tocLink.isBlank() || resolveSourceUrl(info.url, tocLink) == info.url) info
                else request(info.url, tocLink, header = source.header)
            forEachSourcePage(firstToc, tocRule.nextTocUrl, source.header) { page ->
                for (row in page.rows(tocRule.chapterList)) {
                    val link = row.firstValue(tocRule.chapterUrl).ifBlank {
                        (row as? RuleNode.Html)?.link().orEmpty()
                    }
                    if (link.isBlank()) continue
                    val url = runCatching { resolveSourceUrl(page.url, link) }.getOrNull() ?: continue
                    chapters.putIfAbsent(url, row.firstValue(tocRule.chapterName).ifBlank { row.firstValue("text") })
                    require(chapters.size <= 1000)
                }
            }
        }
        if (chapters.isEmpty()) throw MissingSourceChaptersException()
        file.bufferedWriter().use { out ->
            out.appendLine("# " + book.title.replace('\n', ' ')).appendLine()
            if (book.author.isNotBlank()) out.appendLine(book.author.replace('\n', ' ')).appendLine()
            var total = 0
            for ((url, title) in chapters) {
                coroutineContext.ensureActive()
                val first = if (url == info.url) info else request(info.url, url, header = source.header)
                var hasContent = false
                out.appendLine("## ${title.replace('\n', ' ')}").appendLine()
                forEachSourcePage(first, contentRule.nextContentUrl, source.header, chapters.keys - url) { page ->
                    val content = page.root.value(contentRule.content).ifBlank {
                        (page.root as? RuleNode.Html)?.value("@css:#chaptercontent,#chapter-content,#content,article@text").orEmpty()
                    }
                    if (content.isNotBlank()) {
                        hasContent = true
                        total += content.length
                        require(total <= 12_000_000)
                        out.appendLine(content).appendLine()
                    }
                }
                if (!hasContent) throw MissingSourceContentException()
            }
        }
    }
}

private suspend fun forEachSourcePage(
    first: SourcePage,
    nextRule: String,
    header: JsonElement?,
    excludedUrls: Set<String> = emptySet(),
    consume: (SourcePage) -> Unit,
) {
    val queued = mutableSetOf(first.url)
    val visited = mutableSetOf<String>()
    val pending = ArrayDeque<String>()
    var page = first
    repeat(100) {
        coroutineContext.ensureActive()
        if (page.url !in excludedUrls && visited.add(page.url)) {
            consume(page)
            page.root.links(nextRule).map(String::trim).filter(String::isNotEmpty).forEach { link ->
                val next = runCatching { resolveSourceUrl(page.url, link) }.getOrNull() ?: return@forEach
                if (next !in excludedUrls && next !in visited && queued.add(next)) pending.addLast(next)
            }
        }
        while (pending.firstOrNull() in visited) pending.removeFirst()
        val next = pending.removeFirstOrNull() ?: return
        page = request(next, next, header = header)
    }
    throw IOException()
}

private fun RuleNode.firstValue(rule: String): String = value(rule).lineSequence().first().trim()

private val UrlOptionsSeparator = Regex(",\\s*\\{")

private fun resolveSourceUrl(base: String, value: String): String {
    val options = UrlOptionsSeparator.find(value)?.range?.first ?: value.length
    return webUrl(base, value.substring(0, options).trim()) + value.substring(options)
}

private fun webUrl(base: String, value: String): String {
    val reference = URI(value)
    val uri = if (reference.isAbsolute) reference else URI(base.substringBefore(',').trim()).resolve(reference)
    require(uri.scheme in listOf("https", "http") && uri.host != null && uri.userInfo == null)
    return uri.toASCIIString()
}

private data class SourcePage(val url: String, val root: RuleNode) {
    fun rows(rule: String): Sequence<RuleNode> = root.select(rule)
}

private suspend fun request(
    base: String,
    template: String,
    key: String = "",
    header: JsonElement? = null,
    onConnected: suspend () -> Unit = {},
): SourcePage {
    if (template.requiresScript()) throw UnsupportedSourceSearchException()
    val optionStart = UrlOptionsSeparator.find(template)?.range?.first ?: template.length
    val options = template.substring(optionStart).substringAfter(',', "").trim()
        .takeIf(String::isNotEmpty)?.let { Json.parseToJsonElement(it).jsonObject }
    if (options?.get("webView")?.jsonPrimitive?.booleanOrNull == true) throw UnsupportedSourceSearchException()
    val charset = options?.get("charset")?.jsonPrimitive?.contentOrNull ?: "UTF-8"
    val encoded = URLEncoder.encode(key, charset)
    fun expand(value: String) = value.replace("{{key}}", encoded).replace("{{page}}", "1").replace("<page>", "1")
    val url = webUrl(base, expand(template.substring(0, optionStart).trim()))
    val connection = Jsoup.connect(url).timeout(12000).maxBodySize(4 * 1024 * 1024).ignoreContentType(true)
    when (options?.get("method")?.jsonPrimitive?.contentOrNull?.uppercase()) {
        null, "GET" -> Unit
        "POST" -> connection.method(Connection.Method.POST).requestBody(expand(options["body"]?.jsonPrimitive?.contentOrNull.orEmpty()))
        else -> throw IOException()
    }
    val sourceHeaders = when (header) {
        is JsonObject -> header
        is JsonPrimitive -> header.contentOrNull?.takeIf { it.trimStart().startsWith("{") }
            ?.let { Json.parseToJsonElement(it).jsonObject }
        else -> null
    }
    val headers = sourceHeaders.orEmpty() + (options?.get("headers") as? JsonObject).orEmpty()
    headers.forEach { (name, value) ->
        connection.header(name, value.jsonPrimitive.content)
    }
    val response = runInterruptible { connection.execute() }
    onConnected()
    val finalUrl = webUrl(url, response.url().toString())
    val body = runInterruptible {
        require(response.bodyAsBytes().size < 4 * 1024 * 1024)
        response.body()
    }
    val trim = body.trimStart()
    val root = if (trim.startsWith("{") || trim.startsWith("[")) {
        RuleNode.Data(Json.parseToJsonElement(body))
    } else RuleNode.Html(Jsoup.parse(body, finalUrl))
    return SourcePage(finalUrl, root)
}

private sealed interface RuleNode {
    fun select(rule: String): Sequence<RuleNode>
    fun value(rule: String): String
    fun links(rule: String): Sequence<String> = value(rule).lineSequence()

    class Html(private val element: Element) : RuleNode {
        fun link(): String = firstValue("href").ifBlank { firstValue("@css:a@href") }

        private fun elements(rule: String): Sequence<Element> = when {
            rule.isBlank() -> sequenceOf(element)
            rule.startsWith("//") || rule.startsWith(".//") || rule.startsWith("@XPath:", true) ->
                element.selectXpath(if (rule.startsWith("@XPath:", true)) rule.substring(7) else rule).asSequence()
            rule.startsWith("@css:", true) -> element.select(rule.substring(5)).asSequence()
            else -> rule.split('@').fold(sequenceOf(element)) { nodes, step ->
                val selector = when {
                    step.startsWith("class.") -> "." + step.substring(6)
                    step.startsWith("id.") -> "#" + step.substring(3)
                    step.startsWith("tag.") -> step.substring(4)
                    else -> step
                }
                val index = selector.substringAfterLast('.').toIntOrNull()
                val css = if (index == null) selector else selector.substringBeforeLast('.')
                nodes.flatMap { node ->
                    val matches = if (css == "children") node.children() else node.select(css)
                    if (index == null) matches.asSequence() else listOfNotNull(matches.getOrNull(if (index < 0) matches.size + index else index)).asSequence()
                }
            }
        }

        override fun select(rule: String): Sequence<RuleNode> =
            if ("||" in rule) rule.split("||").firstNotNullOfOrNull { alternative ->
                elements(alternative.trim()).toList().takeIf { it.isNotEmpty() }
            }.orEmpty().asSequence().map(::Html)
            else elements(rule).map(::Html)

        override fun value(rule: String): String {
            if (rule.isBlank()) return ""
            val (expression, replacement) = splitReplacement(rule)
            if ("||" in expression) return replacement(expression.split("||").firstNotNullOfOrNull { value(it.trim()).takeIf(String::isNotBlank) }.orEmpty())
            val xpath = expression.startsWith("//") || expression.startsWith(".//") || expression.startsWith("@XPath:", true)
            val css = expression.startsWith("@css:", true)
            val terminal = expression.lastIndexOf('@')
            val selector = when {
                xpath -> expression.substringBeforeLast("/@", expression).removeSuffix("/text()")
                css && terminal == 0 -> expression
                terminal > 0 -> expression.substring(0, terminal)
                else -> ""
            }
            val property = when {
                xpath -> expression.substringAfterLast("/@", "text")
                css && terminal == 0 -> "text"
                else -> expression.substringAfterLast('@')
            }
            val value = elements(selector).joinToString("\n") {
                when (property) {
                    "text", "textNodes" -> it.wholeText().trim()
                    "ownText" -> it.ownText()
                    "html", "all" -> it.wholeText().trim()
                    else -> it.attr(property)
                }
            }.trim()
            return replacement(value)
        }
    }

    class Data(private val element: JsonElement) : RuleNode {
        override fun select(rule: String): Sequence<RuleNode> = values(rule).map(::Data)

        private fun values(rule: String): Sequence<JsonElement> =
            rule.replace(Regex("^@json:", RegexOption.IGNORE_CASE), "").removePrefix("$").replace("[*]", ".*").replace(Regex("\\[(\\d+)]"), ".$1")
                .trim('.').split('.').filter(String::isNotEmpty).fold(sequenceOf(element)) { nodes, step ->
                    nodes.flatMap { node ->
                        when (node) {
                            is JsonArray -> if (step == "*") node.asSequence() else listOfNotNull(step.toIntOrNull()?.let(node::getOrNull)).asSequence()
                            is JsonObject -> listOfNotNull(node[step]).asSequence()
                            else -> emptySequence()
                        }
                    }
                }.flatMap { if (it is JsonArray) it.asSequence() else sequenceOf(it) }

        override fun value(rule: String): String = links(rule).firstOrNull().orEmpty()

        override fun links(rule: String): Sequence<String> {
            if (rule.isBlank()) return emptySequence()
            val (expression, replacement) = splitReplacement(rule)
            if ("||" in expression) return expression.split("||").firstNotNullOfOrNull { alternative ->
                links(alternative.trim()).filter(String::isNotBlank).toList().takeIf { it.isNotEmpty() }
            }.orEmpty().asSequence().map(replacement)
            return values(expression).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.map(replacement)
        }
    }
}

private fun splitReplacement(rule: String): Pair<String, (String) -> String> {
    val parts = rule.split("##", limit = 3)
    val regex = parts.getOrNull(1)?.let(::Regex)
    return parts.first() to { value -> regex?.replace(value, parts.getOrElse(2) { "" }) ?: value }
}
