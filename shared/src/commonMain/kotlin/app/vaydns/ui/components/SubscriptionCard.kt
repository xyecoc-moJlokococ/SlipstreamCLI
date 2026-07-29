package app.vaydns.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
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
 * Folder tabs: "Home" plus one per subscription. Hidden entirely when there are no subscriptions,
 * so a user who never imports one sees the app exactly as before.
 *
 * v2rayNG-style — plain labels with a single underline that *travels* to the tab you pick rather
 * than a highlight appearing in a new place. Tabs keep their natural width (folder names run long)
 * and the row scrolls sideways when they do not fit, so the indicator has to be measured rather
 * than computed from an equal-width grid.
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
    /**
     * A tab was dragged to a new slot. Reported once, on release: reordering live would change
     * each tab's index, and the index is a `pointerInput` key — the drag would cancel itself.
     */
    onMove: (from: Int, to: Int) -> Unit = { _, _ -> },
    /** The long-press menu opens on pick-up; this closes it once the tab is actually moving. */
    onMenuDismiss: () -> Unit = {},
    /** True while a tab is held: whoever owns competing horizontal gestures must stand down. */
    onDragActive: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (names.size <= 1) return
    val haptics = LocalHapticFeedback.current
    // Desktop has a second mouse button, so holding the first one down means nothing there.
    val useRightClick = remember { currentHostPlatform().isDesktop() }
    val density = LocalDensity.current
    // Where each tab sits inside the row, in px. Snapshot state because the indicator animates
    // off it; written only when a value actually changes, so a layout pass that moves nothing
    // does not recompose.
    // Keyed on the names themselves, not just how many there are: after a reorder the count is
    // identical but every measured position belongs to a different tab, and a stale span makes
    // the drop-target maths point at the wrong slot.
    val spans = remember(names) { mutableStateListOf(*Array(names.size) { 0f to 0f }) }
    /** Index of the tab being dragged, or -1. */
    var dragFrom by remember { mutableStateOf(-1) }
    var dragDx by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    val target = spans.getOrElse(selectedIndex) { 0f to 0f }
    val indicatorX by animateFloatAsState(
        targetValue = target.first,
        animationSpec = tween(TabIndicatorMs, easing = FastOutSlowInEasing),
        label = "tabIndicatorX"
    )
    val indicatorWidth by animateFloatAsState(
        targetValue = target.second,
        animationSpec = tween(TabIndicatorMs, easing = FastOutSlowInEasing),
        label = "tabIndicatorW"
    )
    Column(modifier = modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        names.forEachIndexed { index, name ->
            val selected = index == selectedIndex
            val anchor = remember { intArrayOf(0, 0) }
            val labelColor by animateColorAsState(
                targetValue = if (selected) SlipnetTextPrimary else SlipnetTextSecondary,
                animationSpec = tween(TabIndicatorMs, easing = FastOutSlowInEasing),
                label = "tabLabel"
            )
            Box(
                modifier = Modifier
                    .onGloballyPositioned {
                        val pos = it.positionInRoot()
                        anchor[0] = pos.x.toInt()
                        anchor[1] = (pos.y + it.size.height).toInt()
                        val span = it.positionInParent().x to it.size.width.toFloat()
                        if (index < spans.size && spans[index] != span) spans[index] = span
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
                            detectTapGestures(onTap = { onSelect(index) })
                        }
                    }
                    .then(
                        if (useRightClick) Modifier else Modifier.pointerInput(index, names.size) {
                            val slop = viewConfiguration.touchSlop
                            var moved = false
                            // Long press does double duty, the way a home-screen icon does: the
                            // menu opens straight away, and it gets out of the way the moment the
                            // finger actually starts carrying the tab somewhere.
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    dragFrom = index
                                    dragDx = 0f
                                    moved = false
                                    onDragActive(true)
                                    onMenu(index, anchor[0], anchor[1])
                                },
                                onDrag = { change, delta ->
                                    change.consume()
                                    dragDx += delta.x
                                    if (!moved && abs(dragDx) > slop) {
                                        moved = true
                                        onMenuDismiss()
                                    }
                                },
                                onDragEnd = {
                                    val target = if (moved) slotAt(spans, index, dragDx) else index
                                    settle(
                                        scope, spans, index, target, dragDx,
                                        onOffset = { dragDx = it },
                                        onFinished = {
                                            dragFrom = -1
                                            if (target != index) onMove(index, target)
                                        }
                                    )
                                    onDragActive(false)
                                },
                                onDragCancel = {
                                    settle(
                                        scope, spans, index, index, dragDx,
                                        onOffset = { dragDx = it },
                                        onFinished = { dragFrom = -1 }
                                    )
                                    onDragActive(false)
                                }
                            )
                        }
                    )
                    .zIndex(if (dragFrom == index) 1f else 0f)
                    .graphicsLayer { if (dragFrom == index) translationX = dragDx }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = name,
                    color = labelColor,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
        // The indicator lives outside the scrolling row so it can be placed by absolute px;
        // both edges are animated, so it stretches as it travels between labels of different
        // widths instead of jumping to the new size.
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .padding(horizontal = 10.dp)
        ) {
            // While the selected tab is being carried, the underline goes with it — it belongs to
            // that tab, not to the slot it happens to be sitting in.
            val carried = if (dragFrom == selectedIndex) dragDx else 0f
            Box(
                Modifier
                    .width(with(density) { indicatorWidth.toDp() })
                    .fillMaxHeight()
                    .graphicsLayer { translationX = indicatorX + carried }
                    .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                    .background(SlipnetAccent)
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** How long the folder underline takes to travel to the tab you picked. */
private const val TabIndicatorMs = 250

/**
 * Ride the held tab into [target]'s slot instead of teleporting it there. Releasing used to reset
 * the offset in one frame, which read as the tab snapping back rather than being put down.
 */
private fun settle(
    scope: CoroutineScope,
    spans: List<Pair<Float, Float>>,
    from: Int,
    target: Int,
    current: Float,
    onOffset: (Float) -> Unit,
    onFinished: () -> Unit
) {
    val land = if (target == from) {
        0f
    } else {
        (spans.getOrNull(target)?.first ?: 0f) - (spans.getOrNull(from)?.first ?: 0f)
    }
    scope.launch {
        Animatable(current).animateTo(land, tween(180, easing = FastOutSlowInEasing)) {
            onOffset(value)
        }
        // Only now does the tab stop being "the dragged one" — clearing that first is what made
        // the release look like a snap-back: the offset was still animating but nothing was
        // applying it any more.
        onFinished()
        onOffset(0f)
    }
}

/**
 * Which slot a tab dragged by [dx] should land in: the one whose measured span contains the
 * dragged tab's centre. Falls back to where it started when the tabs have not been measured yet.
 */
private fun slotAt(spans: List<Pair<Float, Float>>, from: Int, dx: Float): Int {
    val own = spans.getOrNull(from) ?: return from
    if (own.second <= 0f) return from
    val centre = own.first + own.second / 2f + dx
    // Nearest centre, not "whose box contains this point": containment leaves dead zones between
    // tabs and past the ends, and landing in one meant the drop silently did nothing.
    var best = from
    var bestDistance = Float.MAX_VALUE
    spans.forEachIndexed { index, (x, width) ->
        if (width <= 0f) return@forEachIndexed
        val distance = abs(centre - (x + width / 2f))
        if (distance < bestDistance) {
            bestDistance = distance
            best = index
        }
    }
    return best
}
