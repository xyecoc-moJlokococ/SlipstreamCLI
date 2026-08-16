package app.smugly.ui

import app.smugly.ConfigProfile
import app.smugly.subscription.Subscription

/**
 * Whether the "My configs" (Home) folder should exist.
 *
 * Home is not created up front. It appears only once the user has a profile that does
 * not belong to a subscription — an imported or hand-made config.
 */
fun hasHomeFolder(profiles: List<ConfigProfile>): Boolean =
    profiles.any { it.subscriptionId == null }

/**
 * Tab slots in display order. Null is Home.
 *
 * Home is omitted until [showHomeFolder] is true, so a fresh install (and a user who
 * only imported URL/file subscriptions) never sees an empty "My configs" tab.
 */
fun folderSlots(
    subscriptions: List<Subscription>,
    homeFolderIndex: Int,
    showHomeFolder: Boolean
): List<Subscription?> {
    if (!showHomeFolder) return subscriptions
    return subscriptions.toMutableList<Subscription?>().also {
        it.add(homeFolderIndex.coerceIn(0, subscriptions.size), null)
    }
}
