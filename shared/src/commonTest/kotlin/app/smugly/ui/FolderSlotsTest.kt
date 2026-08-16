package app.smugly.ui

import app.smugly.ConfigProfile
import app.smugly.defaultConfig
import app.smugly.subscription.Subscription
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FolderSlotsTest {

    private fun sub(id: String) = Subscription(id = id, name = id, url = "https://$id.example")

    private fun localProfile(id: String) = ConfigProfile(id, id, defaultConfig())

    private fun subProfile(id: String, subscriptionId: String) =
        ConfigProfile(id, id, defaultConfig(), subscriptionId = subscriptionId)

    @Test
    fun homeIsHiddenWhenThereAreNoProfiles() {
        assertFalse(hasHomeFolder(emptyList()))
        val slots = folderSlots(emptyList(), homeFolderIndex = 0, showHomeFolder = false)
        assertTrue(slots.isEmpty())
    }

    @Test
    fun homeIsHiddenWhenOnlySubscriptionProfilesExist() {
        val profiles = listOf(subProfile("a", "sub1"), subProfile("b", "sub1"))
        assertFalse(hasHomeFolder(profiles))
        val slots = folderSlots(listOf(sub("sub1")), homeFolderIndex = 0, showHomeFolder = false)
        assertEquals(listOf("sub1"), slots.map { it?.id })
    }

    @Test
    fun homeAppearsOnceALocalProfileExists() {
        assertTrue(hasHomeFolder(listOf(localProfile("mine"))))
        val slots = folderSlots(emptyList(), homeFolderIndex = 0, showHomeFolder = true)
        assertEquals(1, slots.size)
        assertNull(slots.single())
    }

    @Test
    fun homeIsInsertedAtConfiguredIndexAmongSubscriptions() {
        val subs = listOf(sub("a"), sub("b"))
        val atStart = folderSlots(subs, homeFolderIndex = 0, showHomeFolder = true)
        assertEquals(listOf(null, "a", "b"), atStart.map { it?.id })
        val atEnd = folderSlots(subs, homeFolderIndex = 2, showHomeFolder = true)
        assertEquals(listOf("a", "b", null), atEnd.map { it?.id })
        val inMiddle = folderSlots(subs, homeFolderIndex = 1, showHomeFolder = true)
        assertEquals(listOf("a", null, "b"), inMiddle.map { it?.id })
    }

    @Test
    fun homeIndexIsClampedWhenOutOfRange() {
        val subs = listOf(sub("a"))
        val tooHigh = folderSlots(subs, homeFolderIndex = 99, showHomeFolder = true)
        assertEquals(listOf("a", null), tooHigh.map { it?.id })
        val negative = folderSlots(subs, homeFolderIndex = -3, showHomeFolder = true)
        assertEquals(listOf(null, "a"), negative.map { it?.id })
    }
}
