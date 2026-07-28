package app.vaydns.subscription

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the fetcher against a real local HTTP server: header casing, gzip, redirects and
 * error codes are exactly the parts unit tests over strings cannot cover.
 */
class SubscriptionFetcherTest {

    private lateinit var server: HttpServer
    private val port: Int get() = server.address.port
    private fun url(path: String) = "http://127.0.0.1:$port$path"

    private val vless = "vless://uuid@example.com:443?type=tcp&security=reality#Spain"
    private val vless2 = "vless://uuid@example.net:443?type=tcp&security=reality#Estonia"

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        // Plain body + the full Happ-style header set.
        server.createContext("/plain") { ex ->
            ex.responseHeaders.add("profile-title", "base64:0JHQsNC70LTRkdC20L3Ri9C5IFZQTg==")
            ex.responseHeaders.add("subscription-userinfo", "upload=10; download=20; total=100; expire=1790951622")
            ex.responseHeaders.add("profile-update-interval", "6")
            ex.responseHeaders.add("support-url", "https://t.me/smugvpn_support")
            ex.respond(200, "$vless\n$vless2".toByteArray())
        }

        // Base64-wrapped body, gzip-encoded — the common real-world combination.
        server.createContext("/gzip") { ex ->
            val payload = java.util.Base64.getEncoder()
                .encodeToString("$vless\n$vless2".toByteArray())
            val gz = java.io.ByteArrayOutputStream()
            GZIPOutputStream(gz).use { it.write(payload.toByteArray()) }
            ex.responseHeaders.add("Content-Encoding", "gzip")
            ex.respond(200, gz.toByteArray())
        }

        server.createContext("/redirect") { ex ->
            ex.responseHeaders.add("Location", url("/plain"))
            ex.respond(302, ByteArray(0))
        }

        server.createContext("/loop") { ex ->
            ex.responseHeaders.add("Location", url("/loop"))
            ex.respond(302, ByteArray(0))
        }

        server.createContext("/notfound") { ex -> ex.respond(404, "nope".toByteArray()) }
        server.createContext("/empty") { ex -> ex.respond(200, "<html>no configs</html>".toByteArray()) }

        server.createContext("/agent") { ex ->
            val agent = ex.requestHeaders.getFirst("User-Agent").orEmpty()
            ex.respond(200, "$vless#$agent".toByteArray())
        }

        server.executor = null
        server.start()
    }

    @AfterTest
    fun stop() {
        server.stop(0)
    }

    private fun HttpExchange.respond(code: Int, body: ByteArray) {
        sendResponseHeaders(code, if (body.isEmpty()) -1L else body.size.toLong())
        if (body.isNotEmpty()) responseBody.use { it.write(body) }
        close()
    }

    @Test
    fun fetchesBodyAndLowercasesHeaders() {
        val response = SubscriptionFetcher.fetch(url("/plain")).getOrThrow()
        assertTrue(response.body.contains("vless://"))
        // Header lookup must not depend on the casing the server chose.
        assertEquals("6", response.headers["profile-update-interval"])
        assertTrue(response.headers.containsKey("subscription-userinfo"))
    }

    @Test
    fun endToEndRefreshFoldsMetadataIn() {
        val sub = Subscription(id = "1", name = "", url = url("/plain"))
        val result = SubscriptionManager.refresh(sub, nowMs = 1_700_000_000_000)

        assertTrue(result.isSuccess, "refresh failed: ${result.error}")
        assertEquals(listOf(vless, vless2), result.entries.map { (it as SubscriptionContent.Entry.Link).uri })
        assertEquals("Балдёжный VPN", result.subscription.name)
        assertEquals(100, result.subscription.info.totalBytes)
        assertEquals(30, result.subscription.info.usedBytes)
        assertEquals(1790951622L, result.subscription.info.expiresAtSeconds)
        // profile-update-interval is hours; stored as minutes.
        assertEquals(6 * 60, result.subscription.updateIntervalMinutes)
        assertEquals(1_700_000_000_000, result.subscription.lastUpdatedMs)
        assertEquals("", result.subscription.lastError)
    }

    @Test
    fun handlesGzipAndBase64Body() {
        val result = SubscriptionManager.refresh(
            Subscription(id = "1", name = "n", url = url("/gzip")),
            nowMs = 1
        )
        assertTrue(result.isSuccess, "refresh failed: ${result.error}")
        assertEquals(listOf(vless, vless2), result.entries.map { (it as SubscriptionContent.Entry.Link).uri })
    }

    @Test
    fun followsRedirects() {
        val result = SubscriptionManager.refresh(
            Subscription(id = "1", name = "n", url = url("/redirect")),
            nowMs = 1
        )
        assertTrue(result.isSuccess, "refresh failed: ${result.error}")
        assertEquals(2, result.entries.size)
    }

    @Test
    fun redirectLoopFailsInsteadOfHanging() {
        val result = SubscriptionManager.refresh(
            Subscription(id = "1", name = "n", url = url("/loop")),
            nowMs = 1
        )
        assertTrue(!result.isSuccess)
        assertTrue(result.subscription.lastError.contains("redirect", ignoreCase = true))
    }

    @Test
    fun httpErrorIsReported() {
        val result = SubscriptionManager.refresh(
            Subscription(id = "1", name = "n", url = url("/notfound")),
            nowMs = 1
        )
        assertTrue(!result.isSuccess)
        assertTrue(result.subscription.lastError.contains("404"))
    }

    @Test
    fun bodyWithoutConfigsIsAFailureNotAnEmptyGroup() {
        // Otherwise a panel returning an error page would silently wipe the user's profiles.
        val existing = Subscription(id = "1", name = "n", url = url("/empty"), lastUpdatedMs = 555)
        val result = SubscriptionManager.refresh(existing, nowMs = 999)
        assertTrue(!result.isSuccess)
        assertTrue(result.entries.isEmpty())
        assertEquals(555, result.subscription.lastUpdatedMs)
    }

    @Test
    fun sendsUserAgentAndHonoursOverride() {
        val default = SubscriptionFetcher.fetch(url("/agent")).getOrThrow()
        assertTrue(default.body.contains(SubscriptionFetcher.DEFAULT_USER_AGENT))

        val custom = SubscriptionFetcher.fetch(url("/agent"), userAgent = "MyClient/9").getOrThrow()
        assertTrue(custom.body.contains("MyClient/9"))
    }
}
