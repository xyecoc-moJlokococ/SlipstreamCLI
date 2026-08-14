package app.smugly.subscription

import app.smugly.Config
import app.smugly.ConfigProfile
import app.smugly.defaultConfig
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubscriptionRepositoryTest {

    /** In-memory Storage so the repository's rules can be checked without a real store. */
    private class FakeStorage : SubscriptionRepository.Storage {
        var subs = mutableListOf<Subscription>()
        var profiles = mutableListOf<ConfigProfile>()
        var collapsed = mutableSetOf<String>()
        var now = 1_000_000L
        private var seq = 0

        override fun loadSubscriptions() = subs.toList()
        override fun saveSubscriptions(subs: List<Subscription>) {
            this.subs = subs.toMutableList()
        }
        override fun loadProfiles() = profiles.toList()
        override fun writeProfiles(profiles: List<ConfigProfile>) {
            this.profiles = profiles.toMutableList()
        }
        override fun baseConfig(): Config = defaultConfig(mode = Config.Mode.PROXY)
        override fun newId(): String = "gen-${++seq}"
        override fun nowMs(): Long = now
        override fun profileFromLink(uri: String, name: String): ConfigProfile? =
            if (uri.startsWith("vless://")) {
                ConfigProfile("tmp", name.ifBlank { "link" }, baseConfig())
            } else {
                null
            }
        override fun loadCollapsedCategories(): Set<String> = collapsed.toSet()
        override fun saveCollapsedCategories(ids: Set<String>) {
            collapsed = ids.toMutableSet()
        }
    }

    private lateinit var server: HttpServer
    private lateinit var storage: FakeStorage
    private lateinit var repo: SubscriptionRepository
    private var payload: String = ""
    private var payloadHeaders: Map<String, String> = emptyMap()

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun config(name: String) =
        """{"remarks":"$name","outbounds":[{"tag":"proxy","protocol":"vless"}]}"""

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/sub") { ex ->
            payloadHeaders.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            val bytes = payload.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        server.createContext("/broken") { ex ->
            ex.sendResponseHeaders(500, -1)
            ex.close()
        }
        server.start()
        storage = FakeStorage()
        repo = SubscriptionRepository(storage)
        payload = "[${config("Spain")},${config("Estonia")}]"
        payloadHeaders = emptyMap()
    }

    @AfterTest
    fun tearDown() = server.stop(0)

    @Test
    fun addImportsProfilesTaggedWithTheSubscription() {
        val result = repo.add(url("/sub"), name = "My VPN")
        assertTrue(result.isSuccess, "add failed: ${result.error}")
        assertEquals(2, result.profileCount)

        val profiles = repo.profilesOf(result.subscription.id)
        assertEquals(listOf("Spain", "Estonia"), profiles.map { it.name })
        assertTrue(profiles.all { it.config.protocol == Config.TunnelProtocol.XRAY })
        assertTrue(profiles.all { it.subscriptionId == result.subscription.id })
    }

    @Test
    fun refreshReplacesOnlyItsOwnProfiles() {
        val handMade = ConfigProfile("mine", "Hand made", storage.baseConfig())
        storage.profiles.add(handMade)
        val first = repo.add(url("/sub"))
        val otherSub = repo.add(url("/sub?x=2"))

        // The panel drops one server and adds another.
        payload = "[${config("Spain")},${config("Germany")}]"
        val refreshed = repo.refresh(first.subscription.id)
        assertTrue(refreshed.isSuccess, "refresh failed: ${refreshed.error}")

        assertEquals(listOf("Spain", "Germany"), repo.profilesOf(first.subscription.id).map { it.name })
        // Untouched: the user's own profile and the other subscription.
        assertEquals(listOf("Hand made"), repo.ownProfiles().map { it.name })
        assertEquals(2, repo.profilesOf(otherSub.subscription.id).size)
    }

    @Test
    fun refreshKeepsIdsOfServersThatSurvived() {
        val added = repo.add(url("/sub"))
        val spainIdBefore = repo.profilesOf(added.subscription.id).first { it.name == "Spain" }.id

        payload = "[${config("Spain")},${config("Germany")}]"
        repo.refresh(added.subscription.id)

        // Same server, same id — otherwise the active selection would be lost on every refresh.
        assertEquals(spainIdBefore, repo.profilesOf(added.subscription.id).first { it.name == "Spain" }.id)
    }

    @Test
    fun failedRefreshLeavesExistingProfilesAlone() {
        val added = repo.add(url("/sub"))
        val before = repo.profilesOf(added.subscription.id)

        storage.saveSubscriptions(
            storage.loadSubscriptions().map { it.copy(url = url("/broken")) }
        )
        val result = repo.refresh(added.subscription.id)

        assertTrue(!result.isSuccess)
        // A panel outage must never wipe the user's servers.
        assertEquals(before.map { it.name }, repo.profilesOf(added.subscription.id).map { it.name })
        assertTrue(result.subscription.lastError.isNotBlank())
    }

    @Test
    fun addingTheSameUrlTwiceRefreshesInsteadOfDuplicating() {
        val first = repo.add(url("/sub"))
        val second = repo.add(url("/sub"))
        assertEquals(first.subscription.id, second.subscription.id)
        assertEquals(1, repo.list().size)
        assertEquals(2, repo.profilesOf(first.subscription.id).size)
    }

    @Test
    fun deleteRemovesSubscriptionAndItsProfilesOnly() {
        storage.profiles.add(ConfigProfile("mine", "Hand made", storage.baseConfig()))
        val added = repo.add(url("/sub"))

        repo.delete(added.subscription.id)

        assertTrue(repo.list().isEmpty())
        assertTrue(repo.profilesOf(added.subscription.id).isEmpty())
        assertEquals(listOf("Hand made"), repo.ownProfiles().map { it.name })
    }

    @Test
    fun linkSubscriptionsGoThroughThePlatformImporter() {
        payload = "vless://uuid@example.com:443#Spain\nvless://uuid@example.net:443#Estonia"
        val result = repo.add(url("/sub"))
        assertTrue(result.isSuccess, "add failed: ${result.error}")
        assertEquals(listOf("Spain", "Estonia"), repo.profilesOf(result.subscription.id).map { it.name })
    }

    @Test
    fun rejectsThingsThatAreNotSubscriptionUrls() {
        val result = repo.add("vless://uuid@host:443#not-a-sub")
        assertTrue(!result.isSuccess)
        assertTrue(repo.list().isEmpty())
    }

    @Test
    fun refreshDuePicksOnlyStaleOnes() {
        val added = repo.add(url("/sub"))
        assertTrue(repo.refreshDue().isEmpty(), "just-added subscription is not due")
    }

    @Test
    fun empty_folder_without_url_is_allowed() {
        val err = repo.save(
            id = null,
            name = "Manual",
            rawUrl = "",
            enabled = true,
            updateIntervalMinutes = 60,
            allowReorder = true,
            showInfo = false
        )
        assertNull(err)
        val sub = repo.list().single { it.name == "Manual" }
        assertEquals("", sub.url)
        assertTrue(!sub.enabled, "empty folders cannot auto-update")
        assertEquals(0, sub.updateIntervalMinutes)
        assertTrue(sub.allowReorder)
        assertTrue(!sub.showInfo, "empty folders have no quota/refresh card")
        assertTrue(!sub.showsInfoCard)
        assertTrue(repo.refreshDue().isEmpty(), "empty folder is never due")
        storage.now += 25L * 60 * 60 * 1000
        assertTrue(repo.refreshDue().isEmpty(), "empty folder stays never-due after time passes")
    }

    @Test
    fun empty_folder_is_still_there_after_json_reload() {
        assertNull(
            repo.save(
                id = null,
                name = "Manual",
                rawUrl = "",
                enabled = true,
                updateIntervalMinutes = 60,
                allowReorder = true,
                showInfo = false
            )
        )
        // Same path Android prefs / desktop files take: write JSON, read JSON.
        storage.subs = SubscriptionJson.listFromString(
            SubscriptionJson.listToString(storage.subs)
        ).toMutableList()
        val sub = repo.list().single()
        assertEquals("Manual", sub.name)
        assertEquals("", sub.url)
        assertTrue(!sub.showsInfoCard)
    }

    @Test
    fun empty_folder_cannot_keep_info_card_even_if_asked() {
        assertNull(
            repo.save(
                id = null,
                name = "Manual",
                rawUrl = "",
                enabled = true,
                updateIntervalMinutes = 60,
                allowReorder = true,
                showInfo = true
            )
        )
        assertTrue(!repo.list().single().showInfo)
        assertTrue(!repo.list().single().showsInfoCard)
    }

    @Test
    fun renameAndDisableArePersisted() {
        val added = repo.add(url("/sub")).subscription
        repo.rename(added.id, "Renamed")
        repo.setEnabled(added.id, false)

        val stored = repo.find(added.id)
        assertEquals("Renamed", stored?.name)
        assertEquals(false, stored?.enabled)
        // A disabled subscription is never auto-refreshed.
        storage.now += 25L * 60 * 60 * 1000
        assertTrue(repo.refreshDue().isEmpty())
    }

    @Test
    fun unknownIdIsReportedNotCrashed() {
        val result = repo.refresh("nope")
        assertTrue(!result.isSuccess)
        assertNull(repo.find("nope"))
    }

    @Test
    fun adoptsThePanelTitleAsTheFolderName() {
        // Regression: a blank name used to be persisted as the URL, so the refresh saw a
        // non-blank name and never picked up profile-title.
        payloadHeaders = mapOf("profile-title" to "base64:0JHQsNC70LTRkdC20L3Ri9C5IFZQTg==")
        val result = repo.add(url("/sub"))
        assertEquals("Балдёжный VPN", result.subscription.name)
        assertEquals("Балдёжный VPN", repo.list().single().name)
    }

    @Test
    fun categoriesLandOnTheFolderAndOnEveryProfile() {
        payload = """
            {"categories":[
              {"name":"Повседневный обход","description":"Обычный интернет",
               "configs":[${config("Spain")}]},
              {"name":"Обход БС","configs":[${config("Estonia")}]}
            ]}
        """.trimIndent()
        val added = repo.add(url("/sub"))
        assertTrue(added.isSuccess, "add failed: ${added.error}")

        val stored = repo.list().single()
        assertEquals(listOf("Повседневный обход", "Обход БС"), stored.categories.map { it.name })
        assertEquals("Обычный интернет", stored.categories.first().description)
        assertEquals(
            listOf(stored.categories[0].id, stored.categories[1].id),
            repo.profilesOf(stored.id).map { it.categoryId }
        )
    }

    @Test
    fun droppingCategoriesFromThePanelClearsThemHere() {
        payload = """{"categories":[{"name":"Everyday","configs":[${config("Spain")}]}]}"""
        val added = repo.add(url("/sub"))
        assertEquals(1, repo.list().single().categories.size)

        // The panel goes back to a plain list: the folder must stop offering a group that is gone.
        payload = "[${config("Spain")}]"
        repo.refresh(added.subscription.id)
        assertTrue(repo.list().single().categories.isEmpty())
        assertNull(repo.profilesOf(added.subscription.id).single().categoryId)
    }

    @Test
    fun aUserChosenNameSurvivesRefresh() {
        payloadHeaders = mapOf("profile-title" to "Panel name")
        val added = repo.add(url("/sub"), name = "My own")
        repo.refresh(added.subscription.id)
        assertEquals("My own", repo.list().single().name)
    }

    @Test
    fun defaultOpenCategoryCollapsesTheOthersOnImport() {
        payload = """
            {"categories":[
              {"name":"Everyday","defaultOpen":true,"configs":[${config("Spain")}]},
              {"name":"Bypass","configs":[${config("Estonia")}]}
            ]}
        """.trimIndent()
        val added = repo.add(url("/sub"))
        assertTrue(added.isSuccess, "add failed: ${added.error}")
        val sub = repo.list().single()
        assertTrue(sub.categories[0].defaultOpen)
        // Only the non-default group is folded.
        assertEquals(
            setOf("${sub.id}/${sub.categories[1].id}"),
            storage.collapsed
        )
    }

    @Test
    fun withoutDefaultOpenMarkerAllCategoriesStartExpanded() {
        payload = """
            {"categories":[
              {"name":"Everyday","configs":[${config("Spain")}]},
              {"name":"Bypass","configs":[${config("Estonia")}]}
            ]}
        """.trimIndent()
        // Pretend the user had something folded before the import.
        storage.collapsed.add("stale/old")
        val added = repo.add(url("/sub"))
        val sub = repo.list().single()
        // Keys for this subscription are cleared (all open); unrelated keys stay.
        assertTrue(storage.collapsed.none { it.startsWith(sub.id + "/") })
        assertTrue("stale/old" in storage.collapsed)
    }

    @Test
    fun refreshDoesNotReseedCategoryCollapse() {
        payload = """
            {"categories":[
              {"name":"Everyday","defaultOpen":true,"configs":[${config("Spain")}]},
              {"name":"Bypass","configs":[${config("Estonia")}]}
            ]}
        """.trimIndent()
        val added = repo.add(url("/sub"))
        val subId = added.subscription.id
        val everyday = repo.list().single().categories[0].id
        val bypass = repo.list().single().categories[1].id
        // Import folded Bypass; user then opens Bypass and folds Everyday instead.
        storage.collapsed = mutableSetOf("$subId/$everyday")
        assertTrue("$subId/$bypass" !in storage.collapsed)

        repo.refresh(subId)
        // Same payload again — user's fold choice must survive.
        assertEquals(setOf("$subId/$everyday"), storage.collapsed)
    }

    @Test
    fun refreshDropsCollapseKeysForCategoriesThePanelRemoved() {
        payload = """
            {"categories":[
              {"name":"Everyday","defaultOpen":true,"configs":[${config("Spain")}]},
              {"name":"Bypass","configs":[${config("Estonia")}]}
            ]}
        """.trimIndent()
        val added = repo.add(url("/sub"))
        val subId = added.subscription.id
        val bypass = repo.list().single().categories[1].id
        assertTrue("$subId/$bypass" in storage.collapsed)

        // Panel drops Bypass; its collapse key must not linger.
        payload = """
            {"categories":[
              {"name":"Everyday","defaultOpen":true,"configs":[${config("Spain")}]}
            ]}
        """.trimIndent()
        repo.refresh(subId)
        assertTrue(storage.collapsed.none { it.startsWith("$subId/") })
    }
}
