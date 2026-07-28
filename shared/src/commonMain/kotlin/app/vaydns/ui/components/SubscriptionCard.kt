package app.vaydns.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vaydns.S
import app.vaydns.currentHostPlatform
import app.vaydns.isDesktop
import app.vaydns.subscription.Subscription
import app.vaydns.subscription.formatBytes
import app.vaydns.t
import app.vaydns.ui.theme.SlipnetAccent
import app.vaydns.ui.theme.SlipnetCard
import app.vaydns.ui.theme.SlipnetCardSoft
import app.vaydns.ui.theme.SlipnetTextMuted
import app.vaydns.ui.theme.SlipnetTextPrimary
import app.vaydns.ui.theme.SlipnetTextSecondary

/**
 * Header shown above a subscription's servers: what the plan is, how much of it is left, and when
 * it last refreshed.
 *
 * The quota bar is drawn **only for limited plans**. An unlimited plan (`total = 0`) reports usage
 * as a plain number — a bar with no ceiling would either read as "full" or as "empty", and both are
 * wrong.
 */
@Composable
fun SubscriptionCard(
    subscription: Subscription,
    nowMs: Long,
    onRefresh: () -> Unit,
    /** Spins the refresh icon and blocks re-taps while a fetch is in flight. */
    refreshing: Boolean = false,
    modifier: Modifier = Modifier
) {
    val info = subscription.info
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SlipnetCard)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = subscription.name.ifBlank { hostOf(subscription.url) },
                    color = SlipnetTextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle(subscription, nowMs),
                    color = SlipnetTextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Continuous rotation while fetching; the transition is restarted rather than
            // animated to a target so it never stutters at the wrap-around point.
            val spin = rememberInfiniteTransition(label = "subRefresh")
            val angle by spin.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(900, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "subRefreshAngle"
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .handClickable(enabled = !refreshing, onClick = onRefresh)
                    .padding(8.dp)
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = t(S.SUBSCRIPTION_REFRESH),
                    tint = if (refreshing) SlipnetAccent else SlipnetTextSecondary,
                    modifier = Modifier
                        .height(20.dp)
                        .graphicsLayer { if (refreshing) rotationZ = angle }
                )
            }
        }

        // Two separate reasons to have no traffic line: never loaded (printing "0 B used" next to
        // an error just looks broken), or loaded from a plain config list that carries no
        // subscription-userinfo at all. Expiry stands on its own — a plan can announce one without
        // reporting any bytes.
        val loaded = subscription.lastUpdatedMs > 0
        val showTraffic = loaded && info.hasTraffic
        val expiry = expiryText(subscription, nowMs)
        if (showTraffic || expiry != null) Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showTraffic) {
                Text(
                    text = trafficText(subscription),
                    color = SlipnetTextPrimary,
                    fontSize = 13.sp,
                    maxLines = 1
                )
            } else {
                // Keeps the expiry on the right where it always sits.
                Spacer(Modifier.weight(1f))
            }
            expiry?.let {
                Text(
                    text = it,
                    color = if (info.daysLeft(nowMs)?.let { d -> d < 0 } == true) SlipnetAccent else SlipnetTextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1
                )
            }
        }

        // Limited plans only — see the class comment.
        if (loaded) info.usedFraction()?.let { fraction ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(SlipnetCardSoft)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(2.dp))
                        .background(SlipnetAccent)
                )
            }
        }

        if (subscription.lastError.isNotBlank()) {
            Text(
                text = subscription.lastError,
                color = SlipnetAccent,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/** `https://host/long/path?token=…` -> `host`, so an unnamed folder still reads as a name. */
private fun hostOf(url: String): String {
    val afterScheme = url.substringAfter("://", url)
    return afterScheme.substringBefore('/').substringBefore('?').ifBlank { url }
}

/** "258.0 GB used" for unlimited plans, "12.0 GB / 100.0 GB" for limited ones. */
private fun trafficText(subscription: Subscription): String {
    val info = subscription.info
    return if (info.hasQuota) {
        "${formatBytes(info.usedBytes)} / ${formatBytes(info.totalBytes)}"
    } else {
        "${formatBytes(info.usedBytes)} ${t(S.SUBSCRIPTION_USED)}"
    }
}

private fun expiryText(subscription: Subscription, nowMs: Long): String? {
    val days = subscription.info.daysLeft(nowMs) ?: return null
    return when {
        days < 0 -> t(S.SUBSCRIPTION_EXPIRED)
        else -> "${t(S.SUBSCRIPTION_EXPIRES_IN)} $days"
    }
}

private fun subtitle(subscription: Subscription, nowMs: Long): String {
    val parts = mutableListOf<String>()
    if (subscription.lastUpdatedMs > 0) {
        val minutes = (nowMs - subscription.lastUpdatedMs) / 60_000
        parts += when {
            minutes < 1 -> t(S.SUBSCRIPTION_UPDATED_JUST_NOW)
            minutes < 60 -> "${t(S.SUBSCRIPTION_UPDATED)} ${minutes}m"
            else -> "${t(S.SUBSCRIPTION_UPDATED)} ${minutes / 60}h"
        }
    }
    if (subscription.updateIntervalMinutes > 0) {
        val hours = subscription.updateIntervalMinutes / 60
        parts += if (hours >= 1) {
            "${t(S.SUBSCRIPTION_AUTO_UPDATE)} ${hours}h"
        } else {
            "${t(S.SUBSCRIPTION_AUTO_UPDATE)} ${subscription.updateIntervalMinutes}m"
        }
    }
    return parts.joinToString(" · ")
}

/**
 * Row of folder buttons: "Home" plus one per subscription. Hidden entirely when there are no
 * subscriptions, so a user who never imports one sees the app exactly as before.
 */
@Composable
fun FolderTabs(
    names: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    /**
     * Opens folder management — long-press on touch, **right-click on desktop**. Reports the tab's
     * bottom-left corner in root coordinates so the caller can drop its menu right under the tab.
     * The position lands in a plain (non-snapshot) holder — written on every layout pass, read only
     * when the menu is actually asked for.
     */
    onMenu: (index: Int, anchorX: Int, anchorY: Int) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    if (names.size <= 1) return
    val haptics = LocalHapticFeedback.current
    // Desktop has a second mouse button, so holding the first one down means nothing there.
    val useRightClick = remember { currentHostPlatform().isDesktop() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        names.forEachIndexed { index, name ->
            val selected = index == selectedIndex
            val anchor = remember { intArrayOf(0, 0) }
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) SlipnetAccent else SlipnetCard)
                    .onGloballyPositioned {
                        val pos = it.positionInRoot()
                        anchor[0] = pos.x.toInt()
                        anchor[1] = (pos.y + it.size.height).toInt()
                    }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .pointerInput(index, useRightClick) {
                        if (useRightClick) {
                            // awaitEachGesture drains the rest of the gesture itself, so opening
                            // the menu on the press and returning is enough.
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                if (currentEvent.buttons.isSecondaryPressed) {
                                    // Consume so the press cannot also read as a folder switch.
                                    down.consume()
                                    onMenu(index, anchor[0], anchor[1])
                                } else if (waitForUpOrCancellation() != null) {
                                    onSelect(index)
                                }
                            }
                        } else {
                            detectTapGestures(
                                onTap = { onSelect(index) },
                                onLongPress = {
                                    // Tick on pick-up, same as grabbing a profile card: the menu
                                    // appears under the finger, so the hand hides the feedback.
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onMenu(index, anchor[0], anchor[1])
                                }
                            )
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = name,
                    color = if (selected) SlipnetTextPrimary else SlipnetTextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
