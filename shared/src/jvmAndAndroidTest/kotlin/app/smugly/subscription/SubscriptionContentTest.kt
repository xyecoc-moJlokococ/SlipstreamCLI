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
    fun namedNodesHoldingConfigsBecomeCategories() {
        val body = """
            {"categories":[
              {"name":"Повседневный обход","description":"Обычный интернет",
               "configs":[${xrayConfig("Spain", listOf("proxy"))}]},
              {"name":"Обход БС (s3-fuckup)",
               "configs":[${xrayConfig("Estonia", listOf("proxy"))}]}
            ]}
        """.trimIndent()
        val parsed = SubscriptionContent.parse(body)
        assertEquals(
            listOf("Повседневный обход", "Обход БС (s3-fuckup)"),
            parsed.categories.map { it.name }
        )
        assertEquals("Обычный интернет", parsed.categories.first().description)
        assertEquals(listOf("Spain", "Estonia"), parsed.entries.map { it.name })
        assertEquals(
            listOf(parsed.categories[0].id, parsed.categories[1].id),
            parsed.entries.map { it.categoryId }
        )
    }

    @Test
    fun aConfigMayNameItsOwnCategory() {
        // Flat list shape: the grouping is a field on the server, not the envelope.
        val spain = JSONObject(xrayConfig("Spain", listOf("proxy")))
            .put("category", "Повседневный обход")
            .put("category-description", "Обычный интернет")
        val estonia = JSONObject(xrayConfig("Estonia", listOf("proxy"))).put("group", "Обход БС")
        val parsed = SubscriptionContent.parse("[$spain,$estonia]")
        assertEquals(listOf("Повседневный обход", "Обход БС"), parsed.categories.map { it.name })
        assertEquals("Обычный интернет", parsed.categories.first().description)
        assertEquals(
            listOf(subscriptionCategoryId("Повседневный обход"), subscriptionCategoryId("Обход БС")),
            parsed.entries.map { it.categoryId }
        )
    }

    @Test
    fun aDeclarationBlockSuppliesDescriptionsForAFlatList() {
        val spain = JSONObject(xrayConfig("Spain", listOf("proxy"))).put("category", "Everyday")
        val body = """
            {"categories":[{"name":"Everyday","description":"Just browsing"}],
             "configs":[$spain]}
        """.trimIndent()
        val parsed = SubscriptionContent.parse(body)
        assertEquals("Just browsing", parsed.categories.single().description)
        assertEquals(parsed.categories.single().id, parsed.entries.single().categoryId)
    }

    @Test
    fun declaredCategoriesKeepTheirOrderEvenWhenServersArriveOutOfIt() {
        val a = JSONObject(xrayConfig("A", listOf("proxy"))).put("category", "Second")
        val b = JSONObject(xrayConfig("B", listOf("proxy"))).put("category", "First")
        val body = """
            {"categories":[{"name":"First"},{"name":"Second"}],"configs":[$a,$b]}
        """.trimIndent()
        // The panel listed them in this order; that is the order the folder shows.
        assertEquals(listOf("First", "Second"), SubscriptionContent.parse(body).categories.map { it.name })
    }

    @Test
    fun aPlainListStillDeclaresNoCategories() {
        val body = "[${xrayConfig("Spain", listOf("proxy"))},${xrayConfig("Estonia", listOf("proxy"))}]"
        val parsed = SubscriptionContent.parse(body)
        assertTrue(parsed.categories.isEmpty())
        assertTrue(parsed.entries.all { it.categoryId.isEmpty() })
    }

    @Test
    fun theDocumentItselfIsNotACategory() {
        // A subscription-wide name describes the whole list; wrapping every server in one group
        // out of it would be a category the panel never asked for.
        val body = """{"name":"Балдёжный VPN","configs":[${xrayConfig("Spain", listOf("proxy"))}]}"""
        val parsed = SubscriptionContent.parse(body)
        assertTrue(parsed.categories.isEmpty())
        assertEquals("", parsed.entries.single().categoryId)
    }

    @Test
    fun aWrapperWithoutConfigsCannotInventAGroup() {
        val body = """
            {"meta":{"name":"Not a group","note":"x"},"configs":[${xrayConfig("Spain", listOf("proxy"))}]}
        """.trimIndent()
        assertTrue(SubscriptionContent.parse(body).categories.isEmpty())
    }

    @Test
    fun jsonGroupsMayHoldPlainLinksToo() {
        // s3fu / Slipstream exist only as links — a JSON payload has to be able to carry them, or
        // a panel could group its Xray servers but not the tunnels the groups are usually about.
        val body = """
            {"categories":[
              {"name":"Повседневный обход","configs":[${xrayConfig("Spain", listOf("proxy"))}]},
              {"name":"Обход БС (s3-fuckup)","description":"Через S3",
               "links":["s3fu://import?endpoint=https%3A%2F%2Fs3.example.com&bucket=b&psk=deadbeef#S3"]},
              {"name":"Обход БС (Slipstream)",
               "servers":[{"name":"DNS-туннель","link":"slipstream://import?domain=ee.example.com"}]}
            ]}
        """.trimIndent()
        val parsed = SubscriptionContent.parse(body)
        assertEquals(
            listOf("Повседневный обход", "Обход БС (s3-fuckup)", "Обход БС (Slipstream)"),
            parsed.categories.map { it.name }
        )
        assertEquals(3, parsed.entries.size)
        assertEquals(listOf("Spain", "S3", "DNS-туннель"), parsed.entries.map { it.name })
        assertEquals(
            parsed.categories.map { it.id },
            parsed.entries.map { it.categoryId }
        )
        assertTrue(parsed.entries[1] is SubscriptionContent.Entry.Link)
        assertTrue(parsed.entries[2] is SubscriptionContent.Entry.Link)
    }

    @Test
    fun nestedOutboundsDoNotBecomeSeparateServers() {
        // One config with three proxy outbounds is one server, not three.
        val body = "[" + xrayConfig("Spain", listOf("p1", "p2", "p3")) + "]"
        assertEquals(1, SubscriptionContent.parse(body).entries.size)
    }

    @Test
    fun defaultOpenFlagOnCategoryCollapsesTheOthers() {
        val body = """
            {"categories":[
              {"name":"Everyday","defaultOpen":true,"configs":[${xrayConfig("Spain", listOf("proxy"))}]},
              {"name":"Bypass","configs":[${xrayConfig("Estonia", listOf("proxy"))}]}
            ]}
        """.trimIndent()
        val parsed = SubscriptionContent.parse(body)
        assertEquals(listOf("Everyday", "Bypass"), parsed.categories.map { it.name })
        assertTrue(parsed.categories[0].defaultOpen)
        assertTrue(!parsed.categories[1].defaultOpen)
        assertEquals(
            setOf("sid/${parsed.categories[1].id}"),
            defaultCollapsedCategoryKeys("sid", parsed.categories)
        )
    }

    @Test
    fun rootDefaultCategoryNameMarksThatGroup() {
        val spain = JSONObject(xrayConfig("Spain", listOf("proxy"))).put("category", "Everyday")
        val est = JSONObject(xrayConfig("Estonia", listOf("proxy"))).put("category", "Bypass")
        val body = """
            {"defaultCategory":"Bypass",
             "categories":[{"name":"Everyday"},{"name":"Bypass"}],
             "configs":[$spain,$est]}
        """.trimIndent()
        val parsed = SubscriptionContent.parse(body)
        assertTrue(parsed.categories.single { it.name == "Bypass" }.defaultOpen)
        assertTrue(!parsed.categories.single { it.name == "Everyday" }.defaultOpen)
    }
}
