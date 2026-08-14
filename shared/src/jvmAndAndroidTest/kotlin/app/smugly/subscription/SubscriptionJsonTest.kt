package app.smugly.subscription

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubscriptionJsonTest {

    @Test
    fun empty_folder_survives_round_trip() {
        val saved = Subscription(
            id = "folder-1",
            name = "Manual",
            url = "",
            enabled = false,
            updateIntervalMinutes = 0,
            allowReorder = true,
            showInfo = false
        )
        val parsed = SubscriptionJson.listFromString(
            SubscriptionJson.listToString(listOf(saved))
        )
        assertEquals(1, parsed.size)
        val loaded = parsed.single()
        assertEquals("folder-1", loaded.id)
        assertEquals("Manual", loaded.name)
        assertEquals("", loaded.url)
        assertTrue(!loaded.enabled)
        assertEquals(0, loaded.updateIntervalMinutes)
        assertTrue(loaded.allowReorder)
        assertTrue(!loaded.showInfo)
        assertTrue(!loaded.showsInfoCard)
    }

    @Test
    fun blank_url_drops_info_card_on_load() {
        val raw = """[{"id":"f1","name":"Manual","url":"","showInfo":true}]"""
        val loaded = SubscriptionJson.listFromString(raw).single()
        assertTrue(!loaded.showInfo)
        assertTrue(!loaded.showsInfoCard)
    }

    @Test
    fun subscription_with_url_still_loads() {
        val saved = Subscription(
            id = "sub-1",
            name = "Panel",
            url = "https://panel.example/sub"
        )
        val parsed = SubscriptionJson.listFromString(
            SubscriptionJson.listToString(listOf(saved))
        )
        assertEquals("https://panel.example/sub", parsed.single().url)
        assertEquals("Panel", parsed.single().name)
    }

    @Test
    fun nameless_url_less_record_is_dropped() {
        val raw = """[{"id":"x","name":"","url":""}]"""
        assertTrue(SubscriptionJson.listFromString(raw).isEmpty())
    }

    @Test
    fun record_without_id_is_dropped() {
        val raw = """[{"id":"","name":"Manual","url":""}]"""
        assertTrue(SubscriptionJson.listFromString(raw).isEmpty())
    }
}
