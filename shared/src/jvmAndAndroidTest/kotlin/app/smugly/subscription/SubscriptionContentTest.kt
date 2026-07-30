package app.smugly.subscription

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The JSON-array body shape. Modelled on what a real panel returns: an array of complete Xray
 * configs, each named by a top-level `remarks`, some with chained proxy outbounds that cannot be
 * expressed as a single `vless://` URI.
 */
class SubscriptionContentTest {

    private fun xrayConfig(remarks: String, outboundTags: List<String>) = """
        {
          "remarks": "$remarks",
          "log": {"loglevel": "warning"},
          "inbounds": [
            {"tag":"socks-in","protocol":"socks","port":10808},
            {"tag":"http-in","protocol":"http","port":10809}
          ],
          "outbounds": [
            ${outboundTags.joinToString(",") { """{"tag":"$it","protocol":"vless"}""" }},
            {"tag":"direct","protocol":"freedom"},
            {"tag":"block","protocol":"blackhole"}
          ],
          "routing": {"rules": []}
        }
    """.trimIndent()

    @Test
    fun parsesJsonArrayOfXrayConfigs() {
        val body = "[${xrayConfig("🇪🇸 Испания", listOf("proxy-primary", "proxy-child-1"))}," +
            "${xrayConfig("🇷🇺 Россия", listOf("proxy"))}]"

        val parsed = SubscriptionContent.parse(body)
        assertEquals(2, parsed.entries.size)
        assertEquals(listOf("🇪🇸 Испания", "🇷🇺 Россия"), parsed.entries.map { it.name })
        assertTrue(parsed.entries.all { it is SubscriptionContent.Entry.XrayJson })
    }

    @Test
    fun keepsChainedOutboundsIntact() {
        // A multi-outbound plan must survive verbatim; rewriting it would break the chain.
        val body = "[${xrayConfig("Spain", listOf("proxy-primary", "proxy-child-1", "proxy-child-2"))}]"
        val entry = SubscriptionContent.parse(body).entries.single() as SubscriptionContent.Entry.XrayJson
        val outbounds = JSONObject(entry.json).getJSONArray("outbounds")
        assertEquals(5, outbounds.length())
        assertEquals("proxy-primary", outbounds.getJSONObject(0).getString("tag"))
        assertEquals("proxy-child-2", outbounds.getJSONObject(2).getString("tag"))
    }

    @Test
    fun metadataStillComesFromHeadersForJsonBodies() {
        val body = "[${xrayConfig("Spain", listOf("proxy"))}]"
        val parsed = SubscriptionContent.parse(
            body,
            mapOf(
                "profile-title" to "base64:0JHQsNC70LTRkdC20L3Ri9C5IFZQTg==",
                "subscription-userinfo" to "upload=0; download=277196729587; total=0; expire=1807367606",
                "profile-update-interval" to "1"
            )
        )
        assertEquals("Балдёжный VPN", parsed.metadata.title)
        assertEquals(277196729587L, parsed.metadata.info.downloadBytes)
        assertEquals(1, parsed.metadata.updateIntervalHours)
        // total=0 means unlimited — the UI must not render a 0-byte quota.
        assertTrue(!parsed.metadata.info.hasQuota)
    }

    @Test
    fun singleJsonObjectAlsoWorks() {
        val parsed = SubscriptionContent.parse(xrayConfig("Solo", listOf("proxy")))
        assertEquals(1, parsed.entries.size)
        assertEquals("Solo", parsed.entries.single().name)
    }

    @Test
    fun jsonWithoutOutboundsIsNotAServer() {
        // An API error envelope must not become a profile.
        val parsed = SubscriptionContent.parse("""{"error":"unauthorized","code":401}""")
        assertTrue(parsed.entries.isEmpty())
    }

    @Test
    fun unnamedConfigsGetPositionalNames() {
        val body = """[{"outbounds":[{"tag":"proxy","protocol":"vless"}]}]"""
        assertEquals("Server 1", SubscriptionContent.parse(body).entries.single().name)
    }

    @Test
    fun linkBodiesStillWorkAndTakeNameFromFragment() {
        val body = "vless://uuid@example.com:443#%F0%9F%87%AA%F0%9F%87%B8%20Spain"
        val entry = SubscriptionContent.parse(body).entries.single()
        assertTrue(entry is SubscriptionContent.Entry.Link)
        assertEquals("🇪🇸 Spain", entry.name)
    }

    @Test
    fun malformedJsonFallsThroughToLinkParsing() {
        // Truncated JSON followed by a link: must not throw, and must still find the link.
        val body = "vless://uuid@example.com:443#ok"
        assertEquals(1, SubscriptionContent.parse(body).entries.size)
        assertTrue(SubscriptionContent.parse("[{\"outbounds\":").entries.isEmpty())
    }

    @Test
    fun findsConfigsWhateverTheyAreWrappedIn() {
        // Panels reshape their payloads; the walk must not care about the envelope.
        val one = xrayConfig("Spain", listOf("proxy"))
        val two = xrayConfig("Estonia", listOf("proxy"))
        val wrapped = """{"status":"ok","data":{"configs":[$one,$two]}}"""
        val parsed = SubscriptionContent.parse(wrapped)
        assertEquals(listOf("Spain", "Estonia"), parsed.entries.map { it.name })
    }

    @Test
    fun unknownExtraFieldsAreHarmlessAndPreserved() {
        val body = """
            [{
              "remarks":"Spain",
              "someFutureField":{"a":1},
              "anotherOne":[1,2,3],
              "outbounds":[{"tag":"proxy","protocol":"vless"}]
            }]
        """.trimIndent()
        val entry = SubscriptionContent.parse(body).entries.single() as SubscriptionContent.Entry.XrayJson
        val obj = JSONObject(entry.json)
        // Stored verbatim: nothing the panel sent is dropped on the floor.
        assertTrue(obj.has("someFutureField"))
        assertTrue(obj.has("anotherOne"))
        assertEquals("Spain", entry.name)
    }

    @Test
    fun metadataCanRideInsideTheJsonToo() {
        val cfg = xrayConfig("Spain", listOf("proxy"))
        val body = """{"profileTitle":"Inline VPN","subscription_userinfo":"upload=1; download=2; total=9; expire=7","configs":[$cfg]}"""
        val parsed = SubscriptionContent.parse(body)
        // Loose key matching: profileTitle / subscription_userinfo are recognised.
        assertEquals("Inline VPN", parsed.metadata.title)
        assertEquals(9, parsed.metadata.info.totalBytes)
        assertEquals(7, parsed.metadata.info.expiresAtSeconds)
    }

    @Test
    fun headersStillOutrankJsonFields() {
        val cfg = xrayConfig("Spain", listOf("proxy"))
        val body = """{"profile-title":"From body","configs":[$cfg]}"""
        val parsed = SubscriptionContent.parse(body, mapOf("Profile-Title" to "From header"))
        assertEquals("From header", parsed.metadata.title)
    }

    @Test
    fun nestedOutboundsDoNotBecomeSeparateServers() {
        // One config with three proxy outbounds is one server, not three.
        val body = "[" + xrayConfig("Spain", listOf("p1", "p2", "p3")) + "]"
        assertEquals(1, SubscriptionContent.parse(body).entries.size)
    }
}
