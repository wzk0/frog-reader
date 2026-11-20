package com.example.frogreader.data

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class OpdsTest {
    @Test
    fun `Atom catalog follows OpenSearch and resolves relative acquisition links`() = runBlocking {
        val catalog = """
            <feed xmlns="http://www.w3.org/2005/Atom">
                <title>测试目录</title>
                <link rel="search" type="application/opensearchdescription+xml" href="search.xml"/>
            </feed>
        """.trimIndent()
        val search = """
            <OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
                <os:Url xmlns:os="http://a9.com/-/spec/opensearch/1.1/"
                        type="application/atom+xml;profile=opds-catalog" template="results?q={searchTerms}"/>
            </OpenSearchDescription>
        """.trimIndent()
        val results = """
            <feed xmlns="http://www.w3.org/2005/Atom" xml:base="/downloads/">
                <title>搜索结果</title>
                <entry>
                    <title>书名</title>
                    <author><name>作者</name></author>
                    <link rel="http://opds-spec.org/acquisition/open-access" href="book.epub" type="application/epub+zip"/>
                </entry>
                <entry>
                    <title>付费书</title>
                    <link rel="http://opds-spec.org/acquisition/buy" href="buy.epub" type="application/epub+zip"/>
                </entry>
            </feed>
        """.trimIndent()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = when (exchange.requestURI.path) {
                "/opds/root" -> catalog
                "/opds/search.xml" -> search
                "/opds/results" -> if (exchange.requestURI.query == "q=测试 书") results else null
                else -> null
            }?.toByteArray()
            if (body == null) exchange.sendResponseHeaders(404, -1)
            else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val feed = Opds.search(OpdsCatalog("$base/opds/root", "测试目录"), "测试 书")
            val book = checkNotNull(feed).publications.single()
            assertEquals("作者", book.author)
            assertEquals("$base/downloads/book.epub", book.links.single().url)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `JSON catalog reads groups and retains the path for query only pagination`() {
        val feed = Opds.parse(
            """
            {
                "metadata":{"title":"目录"},
                "links":[{"rel":["next"],"href":"?page=2","type":"application/opds+json"}],
                "groups":[{
                    "metadata":{"title":"新书"},
                    "navigation":[{"title":"文学","href":"literature","type":"application/opds+json"}],
                    "publications":[{
                        "metadata":{"title":"书","author":[{"name":"作者甲"},"作者乙"]},
                        "links":[{"rel":"download","type":"application/epub+zip","href":"../book.epub"}]
                    }]
                }]
            }
            """.trimIndent(),
            "https://example.org/opds/index?page=1",
        )
        assertEquals("https://example.org/opds/index?page=2", feed.next?.url)
        val group = feed.groups.single()
        assertEquals("https://example.org/opds/literature", group.navigation.single().url)
        assertEquals("作者甲、作者乙", group.publications.single().author)
        assertEquals("https://example.org/book.epub", group.publications.single().links.single().url)
    }

    @Test
    fun `Atom file reads its online root and keeps navigation entries`() = runBlocking {
        val body = """
            <feed xmlns="http://www.w3.org/2005/Atom">
                <title>示例目录</title>
                <link rel="start" href="https://example.org/zh_CN"
                      type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                <link rel="search" href="https://example.org/zh_CN/search.xml"
                      type="application/opensearchdescription+xml"/>
                <link rel="http://opds-spec.org/recommended" href="/zh_CN/recommended.xml"
                      title="推荐" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
                <entry>
                    <title>&#26368;&#36817;&#26356;&#26032;</title>
                    <link rel="http://opds-spec.org/sort/new" href="/zh_CN/lastUpdate.xml"
                          type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
                </entry>
                <entry>
                    <title>网页链接</title>
                    <link rel="related" href="https://example.org/" type="text/html"/>
                </entry>
            </feed>
        """.trimIndent()
        val imported = body.byteInputStream().use { Opds.fromDocument(it) }
        assertEquals("https://example.org/zh_CN", imported.url)
        val feed = Opds.parse(body, imported.url)
        assertEquals(
            listOf("https://example.org/zh_CN/recommended.xml", "https://example.org/zh_CN/lastUpdate.xml"),
            feed.navigation.map(OpdsLink::url),
        )
        assertEquals(listOf("推荐", "最近更新"), feed.navigation.map(OpdsLink::title))
        assertEquals("https://example.org/zh_CN/search.xml", feed.search?.url)
    }

    @Test
    fun `Atom pagination reads previous and suppresses next at the last page`() {
        val page = Opds.parse(
            """
            <feed xmlns="http://www.w3.org/2005/Atom">
               <title>最后一页</title>
               <link rel="self" href="?page=2"/>
               <link rel="last" href="?page=2"/>
               <link rel="previous" href="?page=1"/>
               <link rel="next" href="?page=3"/>
            </feed>
            """.trimIndent(),
            "https://example.org/catalog?page=2",
        )
        assertEquals("https://example.org/catalog?page=1", page.previous?.url)
        assertEquals(null, page.next)
    }

    @Test
    fun `download reports bytes received as progress when the server supplies a length`() = runBlocking {
        val payload = ByteArray(64 * 1024) { it.toByte() }
        val file = java.nio.file.Files.createTempFile("opds-test", ".epub").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/book.epub") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
            val progress = mutableListOf<Float?>()
            Opds.download(
                OpdsLink("http://127.0.0.1:${server.address.port}/book.epub", "", "application/epub+zip", emptySet()),
                file,
            ) { progress.add(it) }
            assertEquals(0f, progress.first())
            assertEquals(1f, progress.last())
            assertTrue(file.readBytes().contentEquals(payload))
        } finally {
            server.stop(0)
            file.delete()
        }
    }

    @Test
    fun `legacy rule documents are not accepted as OPDS feeds`() {
        val result = runCatching { Opds.parse("""[{"bookSourceUrl":"https://example.org"}]""", "https://example.org") }
        assertTrue(result.exceptionOrNull() is InvalidOpdsFeed)
    }
}
