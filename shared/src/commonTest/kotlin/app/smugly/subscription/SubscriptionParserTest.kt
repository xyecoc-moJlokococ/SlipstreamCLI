package app.smugly.subscription

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubscriptionParserTest {

    private val vless = "vless://uuid@example.com:443?type=tcp&security=reality#Spain"
    private val vless2 = "vless://uuid@example.net:443?type=tcp&security=reality#Estonia"

    @Test
    fun parsesUserInfoHeader() {
        val info = SubscriptionParser.parseUserInfo(
            "upload=0; download=2153701362; total=0; expire=1790951622"
        )
        assertEquals(0, info.uploadBytes)
        assertEquals(2153701362L, info.downloadBytes)
        assertEquals(0, info.totalBytes)
        assertEquals(1790951622L, info.expiresAtSeconds)
        // total=0 means unlimited, not "no quota left".
        assertTrue(!info.hasQuota)
        assertNull(info.usedFraction())
    }

    @Test
    fun userInfoToleratesSpacingAndOrder() {
        val info = SubscriptionParser.parseUserInfo("expire=100;total=50 ;  upload=1;download=2")
        assertEquals(1, info.uploadBytes)
        assertEquals(2, info.downloadBytes)
        assertEquals(50, info.totalBytes)
        assertEquals(100, info.expiresAtSeconds)
        assertEquals(3, info.usedBytes)
    }

    @Test
    fun decodesQuotedAndBase64Text() {
        assertEquals("Name VPN", SubscriptionParser.decodeText("\"Name VPN\""))
        // base64:SGFwcCB0aGUgYmVzdCE= -> "Happ the best!"
        assertEquals("Happ the best!", SubscriptionParser.decodeText("base64:SGFwcCB0aGUgYmVzdCE="))
        assertEquals("plain", SubscriptionParser.decodeText("  plain  "))
    }

    @Test
    fun readsMetadataFromHeaders() {
        val parsed = SubscriptionParser.parse(
            body = "$vless\n$vless2",
            headers = mapOf(
                "Profile-Title" to "base64:0JHQsNC70LTRkdC20L3Ri9C5IFZQTg==",
                "profile-update-interval" to "1",
                "subscription-userinfo" to "upload=1; download=2; total=3; expire=4",
                "support-url" to "https://t.me/smugvpn_support",
                "profile-web-page-url" to "https://happ.su"
            )
        )
        assertEquals("Балдёжный VPN", parsed.metadata.title)
        assertEquals(1, parsed.metadata.updateIntervalHours)
        assertEquals(3, parsed.metadata.info.totalBytes)
        assertEquals("https://t.me/smugvpn_support", parsed.metadata.info.supportUrl)
        assertEquals("https://happ.su", parsed.metadata.info.webPageUrl)
        assertEquals(listOf(vless, vless2), parsed.links)
    }

    @Test
    fun readsHappInlineMetadataFromBody() {
        val body = buildString {
            appendLine("#profile-title: Name VPN")
            appendLine("#subscription-userinfo: upload=0; download=100; total=200; expire=1790951622")
            appendLine("#announce: base64:SGFwcCB0aGUgYmVzdCE=")
            appendLine(vless)
        }
        val parsed = SubscriptionParser.parse(body)
        assertEquals("Name VPN", parsed.metadata.title)
        assertEquals(100, parsed.metadata.info.downloadBytes)
        assertEquals(200, parsed.metadata.info.totalBytes)
        assertEquals("Happ the best!", parsed.metadata.info.announce)
        assertEquals(listOf(vless), parsed.links)
    }

    @Test
    fun headersWinOverInlineMetadata() {
        val body = "#profile-title: From body\n$vless"
        val parsed = SubscriptionParser.parse(body, mapOf("profile-title" to "From header"))
        assertEquals("From header", parsed.metadata.title)
    }

    @Test
    fun decodesBase64WrappedBody() {
        // Standard alphabet with padding.
        val raw = "$vless\n$vless2"
        val encoded = encodeBase64(raw.encodeToByteArray())
        val parsed = SubscriptionParser.parse(encoded)
        assertEquals(listOf(vless, vless2), parsed.links)
    }

    @Test
    fun decodesUrlSafeBase64WithoutPadding() {
        val raw = "$vless\n$vless2"
        val encoded = encodeBase64(raw.encodeToByteArray())
            .replace('+', '-').replace('/', '_').trimEnd('=')
        val parsed = SubscriptionParser.parse(encoded)
        assertEquals(listOf(vless, vless2), parsed.links)
    }

    @Test
    fun decodesBase64WithEmbeddedNewlines() {
        val raw = "$vless\n$vless2"
        val encoded = encodeBase64(raw.encodeToByteArray()).chunked(24).joinToString("\n")
        assertEquals(listOf(vless, vless2), SubscriptionParser.parse(encoded).links)
    }

    @Test
    fun plainTextBodyIsNotMangled() {
        // A body that is already plain links must survive untouched even though parts of it
        // are valid base64 alphabet.
        val parsed = SubscriptionParser.parse("$vless\n$vless2")
        assertEquals(listOf(vless, vless2), parsed.links)
    }

    @Test
    fun ignoresCommentsAndBlankLines() {
        val body = "\n# just a comment\n\n$vless\n   \n"
        assertEquals(listOf(vless), SubscriptionParser.parse(body).links)
    }

    @Test
    fun deduplicatesRepeatedLinks() {
        assertEquals(listOf(vless), SubscriptionParser.parse("$vless\n$vless").links)
    }

    // Every scheme the importer understands must be here. A missing one is dropped with no
    // error, and a category holding only that protocol disappears from the folder entirely —
    // which is exactly what `cdnfu://` did to «Обход БС [CDN]».
    @Test
    fun appOwnSchemesAreAccepted() {
        val body = "slipstream://import?config=abc\ns3fu://import?config=def\n" +
            "cdnfu://import?url=http%3A%2F%2Fedge.example&psk=deadbeef\nxray://import?config=ghi"
        assertEquals(4, SubscriptionParser.parse(body).links.size)
    }

    @Test
    fun aCategoryHoldingOnlyACdnLinkSurvives() {
        val body = buildString {
            appendLine("#category: Обход БС [CDN]")
            appendLine("cdnfu://import?url=http%3A%2F%2Fedge.example&psk=deadbeef&name=Spain")
        }
        val parsed = SubscriptionParser.parse(body)
        assertEquals(listOf("Обход БС [CDN]"), parsed.categories.map { it.name })
        assertEquals(1, parsed.links.size)
    }

    @Test
    fun emptyAndGarbageBodiesYieldNoLinks() {
        assertTrue(SubscriptionParser.parse("").links.isEmpty())
        assertTrue(SubscriptionParser.parse("   \n  ").links.isEmpty())
        assertTrue(SubscriptionParser.parse("<html>not a subscription</html>").links.isEmpty())
    }

    @Test
    fun categoryMarkersGroupTheLinksBelowThem() {
        val body = buildString {
            appendLine("#profile-title: Name VPN")
            appendLine("#category: Повседневный обход")
            appendLine("#category-description: Быстрые серверы для обычного интернета")
            appendLine(vless)
            appendLine("#category: Обход БС (s3-fuckup)")
            appendLine("s3fu://import?config=abc")
            appendLine(vless2)
        }
        val parsed = SubscriptionParser.parse(body)
        // Metadata still parses; the markers are additional, not a different format.
        assertEquals("Name VPN", parsed.metadata.title)
        assertEquals(
            listOf("Повседневный обход", "Обход БС (s3-fuckup)"),
            parsed.categories.map { it.name }
        )
        assertEquals(
            "Быстрые серверы для обычного интернета",
            parsed.categories.first().description
        )
        // Blank by design: the panel gave the second group no description, and none is invented.
        assertEquals("", parsed.categories[1].description)
        val everyday = parsed.categories[0].id
        val bypass = parsed.categories[1].id
        assertEquals(
            listOf(everyday, bypass, bypass),
            parsed.entries.map { it.categoryId }
        )
    }

    @Test
    fun emptyCategoryMarkerClosesTheGroup() {
        val body = "#group: Grouped\n$vless\n#group:\n$vless2"
        val parsed = SubscriptionParser.parse(body)
        assertEquals(1, parsed.categories.size)
        assertEquals(subscriptionCategoryId("Grouped"), parsed.entries[0].categoryId)
        assertEquals("", parsed.entries[1].categoryId)
    }

    @Test
    fun categoryValuesMayBeBase64() {
        // Same escape hatch as every other text field panels send.
        val body = "#category: base64:0J/QvtCy0YHQtdC00L3QtdCy0L3Ri9C5\n$vless"
        val parsed = SubscriptionParser.parse(body)
        assertEquals("Повседневный", parsed.categories.single().name)
    }

    @Test
    fun bodiesWithoutCategoryMarkersDeclareNone() {
        val parsed = SubscriptionParser.parse("$vless\n$vless2")
        assertTrue(parsed.categories.isEmpty())
        assertTrue(parsed.entries.all { it.categoryId.isEmpty() })
    }

    @Test
    fun categoryIdsIgnoreCaseAndPunctuation() {
        assertEquals(
            subscriptionCategoryId("Обход БС (s3-fuckup)"),
            subscriptionCategoryId("обход бс   s3 fuckup")
        )
        assertTrue(subscriptionCategoryId("Повседневный обход").isNotBlank())
    }

    @Test
    fun defaultCategoryMarkerOpensOnlyThatGroup() {
        val body = buildString {
            appendLine("#category: Everyday")
            appendLine(vless)
            appendLine("#category: Bypass")
            appendLine(vless2)
            appendLine("#default-category: Everyday")
        }
        val parsed = SubscriptionParser.parse(body)
        assertTrue(parsed.categories[0].defaultOpen)
        assertTrue(!parsed.categories[1].defaultOpen)
        val keys = defaultCollapsedCategoryKeys("sub-1", parsed.categories)
        assertEquals(setOf("sub-1/${parsed.categories[1].id}"), keys)
    }

    @Test
    fun defaultOpenTruthyFlagMarksTheCurrentCategory() {
        val body = buildString {
            appendLine("#category: Everyday")
            appendLine("#default-open: true")
            appendLine(vless)
            appendLine("#category: Bypass")
            appendLine(vless2)
        }
        val parsed = SubscriptionParser.parse(body)
        assertTrue(parsed.categories.single { it.name == "Everyday" }.defaultOpen)
        assertTrue(parsed.categories.none { it.name == "Bypass" && it.defaultOpen })
    }

    @Test
    fun withoutDefaultMarkerAllCategoriesStartOpen() {
        val body = "#category: A\n$vless\n#category: B\n$vless2"
        val parsed = SubscriptionParser.parse(body)
        assertTrue(parsed.categories.none { it.defaultOpen })
        assertTrue(defaultCollapsedCategoryKeys("sub", parsed.categories).isEmpty())
    }

    @Test
    fun quotaAndExpiryHelpers() {
        val info = SubscriptionInfo(
            uploadBytes = 25,
            downloadBytes = 25,
            totalBytes = 100,
            expiresAtSeconds = 2_000_000
        )
        assertEquals(0.5f, info.usedFraction())
        // 2_000_000s vs a "now" one day earlier -> 1 day left.
        assertEquals(1, info.daysLeft(nowMs = (2_000_000L - 86_400) * 1000))
        assertEquals(-1, info.daysLeft(nowMs = (2_000_000L + 90_000) * 1000))
    }

    @Test
    fun formatsBytesLikePanels() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("2.0 GB", formatBytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun updateDueRespectsIntervalAndEnabled() {
        val base = Subscription(id = "a", name = "n", url = "https://x", lastUpdatedMs = 1_000_000)
        assertTrue(!base.isUpdateDue(1_000_000 + 60_000))
        assertTrue(base.isUpdateDue(1_000_000 + 25L * 60 * 60 * 1000))
        assertTrue(!base.copy(enabled = false).isUpdateDue(Long.MAX_VALUE / 2))
        assertTrue(!base.copy(updateIntervalMinutes = 0).isUpdateDue(Long.MAX_VALUE / 2))
        // Never fetched -> due immediately.
        assertTrue(base.copy(lastUpdatedMs = 0).isUpdateDue(1))
    }

    // Minimal encoder so the tests do not depend on a platform base64.
    private fun encodeBase64(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            sb.append(alphabet[b0 shr 2])
            sb.append(alphabet[((b0 and 0x03) shl 4) or (b1 shr 4)])
            sb.append(if (i + 1 < bytes.size) alphabet[((b1 and 0x0F) shl 2) or (b2 shr 6)] else '=')
            sb.append(if (i + 2 < bytes.size) alphabet[b2 and 0x3F] else '=')
            i += 3
        }
        return sb.toString()
    }
}
