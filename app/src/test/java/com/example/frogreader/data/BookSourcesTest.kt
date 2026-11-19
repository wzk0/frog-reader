package com.example.frogreader.data

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress

class BookSourcesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `saved connection outcomes survive restart and reimport of the same source`() = runBlocking {
        val original = """{"bookSourceUrl":"https://example.com","bookSourceName":"旧书源"}"""
        val store = BookSourceStore(temporary.root)
        store.import(original)
        store.update { sources -> sources.map { it.copy(connection = SourceConnection.Connected, responseMillis = 127, enabled = false, favorite = true) } }

        val reopened = BookSourceStore(temporary.root)
        assertEquals(SourceConnection.Connected, reopened.read().single().connection)
        assertEquals(listOf(LegacySourceSubscription), reopened.read().single().subscriptions)
        val subscription = SourceSubscription("file:example", "example.json")
        reopened.import(original.replace("旧书源", "新书源"), subscription)
        reopened.import(original.replace("旧书源", "新书源"), subscription)
        val source = BookSourceStore(temporary.root).read().single()
        assertEquals("新书源", source.bookSourceName)
        assertEquals(SourceConnection.Connected, source.connection)
        assertEquals(127L, source.responseMillis)
        assertEquals(false, source.enabled)
        assertTrue(source.favorite)
        assertEquals(listOf(LegacySourceSubscription, subscription), source.subscriptions)
    }

    @Test
    fun `large legacy backup imports from a stream with mixed entries and encoded rules`() = runBlocking {
        val store = BookSourceStore(temporary.root)
        val padding = " ".repeat(2 * 1024 * 1024)
        val backup = """
            {"bookSources":[
                null,
                {"bookSourceUrl":" example.com ","enabled":"0",
                 "ruleSearch":"{\"name\":\"tag.a@text\"}","ruleContent":null},
                {"bookSourceUrl":"https://example.net","bookSourceName":"第二个源"},
            ]}
        """.trimIndent()
        val sources = (padding + backup).byteInputStream().use { store.importFromStream(it) }
        assertEquals(listOf("example.com", "第二个源"), sources.map(BookSource::bookSourceName))
        assertEquals("tag.a@text", sources.first().ruleSearch.name)
        assertEquals(false, sources.first().enabled)
        assertEquals(sources, store.read())
    }

    @Test
    fun `replacement rules receive a specific import error`() = runBlocking {
        val store = BookSourceStore(temporary.root)
        val failure = runCatching {
            store.import("""[{"name":"替换","pattern":"a","replacement":"b"}]""")
        }.exceptionOrNull()
        assertTrue(failure is ReplacementRulesFileException)
        assertTrue(store.read().isEmpty())
    }

    @Test
    fun `link import follows redirects and stores the downloaded JSON`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/link") { request ->
            request.responseHeaders.set("Location", "/sources")
            request.sendResponseHeaders(302, -1)
            request.close()
        }
        server.createContext("/sources") { request ->
            val body = """[{"bookSourceUrl":"https://example.com","bookSourceName":"测试书源"}]""".toByteArray()
            request.sendResponseHeaders(200, body.size.toLong())
            request.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val store = BookSourceStore(temporary.root)
            val imported = store.importFromUrl("http://127.0.0.1:${server.address.port}/link")
            assertEquals("测试书源", imported.single().bookSourceName)
            assertEquals(imported, store.read())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `an imported source searches by rule and saves chapters in order`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { request ->
            val body = when (request.requestURI.path) {
                "/search", "/popular" -> if (
                    request.requestHeaders.getFirst("X-Test-Source") == "enabled" &&
                    (request.requestURI.path == "/popular" || request.requestURI.query == "q=测试书")
                ) {
                    """<div class="result"><a href="/book">测试书</a><span class="author">作者</span><span class="kind">科幻</span></div>
                    <div class="result"><a href="javascript:void(0)">无效结果</a></div>"""
                } else ""
                "/book" -> """<nav><a href="/chapter/1">第一章</a></nav><a class="next" href="/toc/2"></a>"""
                "/toc/2" -> """<nav><a href="/chapter/2">第二章</a></nav><a class="next" href="/book"></a>"""
                "/chapter/1" -> """<article>第一段正文</article><a class="next" href="/chapter/1/2"></a>"""
                "/chapter/1/2" -> """<article>第一章续页</article><a class="next" href="/chapter/2"></a>"""
                else -> """<article>第二段正文</article>"""
            }.toByteArray()
            request.responseHeaders.set("Content-Type", "text/html; charset=UTF-8")
            request.sendResponseHeaders(200, body.size.toLong())
            request.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val store = BookSourceStore(temporary.root)
            store.import("""
                {
                  "bookSourceName": "测试书源",
                  "bookSourceUrl": "$base",
                  "searchUrl": "/search?q={{key}}",
                  "header": {"X-Test-Source":"enabled"},
                  "exploreUrl":"热门::/popular",
                  "ruleExplore":{"bookList":"class.result","name":"tag.a@text","bookUrl":"tag.a@href"},
                  "ruleSearch": {"bookList":"class.result","name":"class.missing@text||@XPath:.//a/text()","author":"class.author@text","kind":"class.kind@text","bookUrl":"tag.a@href"},
                  "ruleToc": {"chapterList":"@css:nav a","chapterName":"text","nextTocUrl":"@css:.next@href"},
                  "ruleContent": {"content":"@css:article","nextContentUrl":"@css:.next@href"}
                }
            """.trimIndent())
            val source = store.read().single()
            var connected = false
            val book = BookSourceReader.search(source, "测试书") { connected = true }.single()
            assertTrue(connected)
            assertEquals("作者", book.author)
            assertEquals("科幻", book.category)
            assertTrue(BookSourceReader.checkConnection(source))
            assertEquals("$base/book", book.url)
            val discovery = source.discoveryCategories().single()
            assertEquals("热门", discovery.title)
            assertEquals(book.url, BookSourceReader.explore(discovery).single().url)
            val file = temporary.newFile("book.md")
            BookSourceReader.writeBook(book, file)
            val content = file.readText()
            assertTrue(content.contains("## 第一章\n\n第一段正文"))
            assertTrue(content.indexOf("第一章续页") in 1 until content.indexOf("第二章"))
        } finally {
            server.stop(0)
        }
    }
}
