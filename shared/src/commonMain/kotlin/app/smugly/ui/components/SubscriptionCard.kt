package app.smugly.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import app.smugly.S
import app.smugly.currentHostPlatform
import app.smugly.isDesktop
import app.smugly.subscription.Subscription
import app.smugly.subscription.formatBytes
import app.smugly.t
import app.smugly.ui.theme.SmuglyAccent
import app.smugly.ui.theme.SmuglyCard
import app.smugly.ui.theme.SmuglyCardSoft
import app.smugly.ui.theme.SmuglyTextMuted
import app.smugly.ui.theme.SmuglyTextPrimary
import app.smugly.ui.theme.SmuglyTextSecondary

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
            .background(SmuglyCard)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = subscription.name.ifBlank { hostOf(subscription.url) },
                    color = SmuglyTextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle(subscription, nowMs),
                    color = SmuglyTextMuted,
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
                    tint = if (refreshing) SmuglyAccent else SmuglyTextSecondary,
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
                    color = SmuglyTextPrimary,
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
                    color = if (info.daysLeft(nowMs)?.let { d -> d < 0 } == true) SmuglyAccent else SmuglyTextSecondary,
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
                    .background(SmuglyCardSoft)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(2.dp))
                        .background(SmuglyAccent)
                )
            }
        }

        if (subscription.lastError.isNotBlank()) {
            Text(
                text = subscription.lastError,
                color = SmuglyAccent,
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
 *
 * Gestures:
 * - Tap / LMB click → select tab
 * - Long-press + drag (touch **and** mouse) → reorder tabs
 * - Long-press on touch → also opens the folder menu (dismissed once the tab moves)
 * - Right-click on desktop → folder menu (Edit / Delete subscription)
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
    // Gesture handlers below live inside `pointerInput`, whose block is only restarted when its
    // keys change. The callbacks close over the caller's *current* folder list, so a block that
    // survives a reorder keeps calling yesterday's lambda: the first drag after launch worked and
    // every one after it re-applied the first drag's outcome, which looked exactly like the tab
    // springing back to where it started. Always call through the latest ones.
    // Same reason as the callbacks below: `pointerInput` keeps the closure it started with, so a
    // drag begun after a reorder would stamp itself with the OLD tab order, decide it was stale on
    // the spot, and run with no visuals at all — the tab did not follow the finger and the new
    // position simply appeared on release.
    val namesNow by rememberUpdatedState(names)
    val selectNow by rememberUpdatedState(onSelect)
    val menuNow by rememberUpdatedState(onMenu)
    val moveNow by rememberUpdatedState(onMove)
    val menuDismissNow by rememberUpdatedState(onMenuDismiss)
    val dragActiveNow by rememberUpdatedState(onDragActive)
    val haptics = LocalHapticFeedback.current
    // Desktop: menu via right-click; long-press still reorders (same as phone).
    val isDesktop = remember { currentHostPlatform().isDesktop() }
    val density = LocalDensity.current
    // Where each tab sits inside the row, in px. Snapshot state because the indicator animates
    // off it; written only when a value actually changes, so a layout pass that moves nothing
    // does not recompose.
    // Keyed on the COUNT, not on the names. Rebuilding this on every reorder looked safer, but the
    // replacement starts as zeros and is only filled on the next layout pass — so for a frame the
    // indicator aimed at x=0, which is the underline snapping to the first tab and then sliding
    // back. Worse, the gesture below captures this list, so a fresh instance left the drag reading
    // positions nothing writes to any more. Reordering re-measures every tab anyway.
    val spans = remember(names.size) { mutableStateListOf(*Array(names.size) { 0f to 0f }) }
    /** Index of the tab being dragged, or -1. */
    var dragFrom by remember { mutableStateOf(-1) }
    /**
     * Tab order as it was when the drag started. Reordering is not instant — the new list comes
     * back through the caller, its store and a reload — and clearing the drag on release meant a
     * few frames of the OLD order with no offsets applied: the tab appeared to snap back to where
     * it came from and only then slide across. Comparing against this makes the drag's visual
     * state expire exactly when the new order arrives, in the same pass that lays it out.
     */
    var dragNames by remember { mutableStateOf<List<String>?>(null) }
    /** Slot the held tab would land in right now — drives the live gap. */
    var dragTo by remember { mutableStateOf(-1) }
    var dragDx by remember { mutableStateOf(0f) }
    // Gap the other tabs open up as the held one passes over them. Without it the drag was
    // invisible: only the held tab moved, nothing showed where it would land, and a drop that
    // did not travel far enough to change slots looked exactly like a drag that did nothing.
    val gapPx = with(density) { 2.dp.toPx() }
    val dragging = dragFrom >= 0 && dragNames == names
    // The new order is here; `dragging` already reads false, so this just tidies the state up.
    LaunchedEffect(names) {
        dragFrom = -1
        dragTo = -1
        dragDx = 0f
        dragNames = null
    }
    // How far tab [index] steps aside for the tab being carried. Shared with the indicator below:
    // when the OPEN folder is the one stepping aside, its underline has to go with it, or the tab
    // moves and the red bar stays behind.
    fun shiftOf(index: Int): Float {
        if (!dragging || dragTo < 0 || index == dragFrom) return 0f
        val held = (spans.getOrNull(dragFrom)?.second ?: 0f) + gapPx
        return when {
            index in (dragFrom + 1)..dragTo -> -held
            index in dragTo until dragFrom -> held
            else -> 0f
        }
    }
    val scope = rememberCoroutineScope()
    val target = spans.getOrElse(selectedIndex) { 0f to 0f }
    // The underline travels when the user picks a different tab, and teleports when the tabs
    // themselves are rearranged — in a reorder the tab it belongs to is already in its new place,
    // so sliding there afterwards plays the move a second time. Both cases change the measured
    // target, so they are told apart by whether the tab ORDER changed; the flag is held until the
    // new measurement actually lands, because that is a layout pass later than the reorder.
    val orderMark = remember { object { var names: List<String>? = null; var reordered = false } }
    if (orderMark.names != names) {
        orderMark.reordered = orderMark.names != null
        orderMark.names = names
    }
    val indicatorAnimX = remember { Animatable(0f) }
    val indicatorAnimW = remember { Animatable(0f) }
    LaunchedEffect(target.first, target.second) {
        if (orderMark.reordered) {
            orderMark.reordered = false
            indicatorAnimX.snapTo(target.first)
            indicatorAnimW.snapTo(target.second)
        } else {
            launch {
                indicatorAnimX.animateTo(
                    target.first,
                    tween(TabIndicatorMs, easing = FastOutSlowInEasing)
                )
            }
            indicatorAnimW.animateTo(
                target.second,
                tween(TabIndicatorMs, easing = FastOutSlowInEasing)
            )
        }
    }
    val indicatorX = indicatorAnimX.value
    val indicatorWidth = indicatorAnimW.value
    val rowScroll = rememberScrollState()
    Column(modifier = modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Only scrollable when the tabs actually overflow. A scrollable that has nothing
            // to scroll still competes for the same horizontal drag as the reorder gesture,
            // and it wins as soon as the finger moves — the row twitched sideways and the tab
            // never picked up. `enabled` cannot fix that after the fact: by the time the long
            // press sets `dragFrom`, the scroll has already claimed the pointer.
            .horizontalScroll(rowScroll, enabled = !dragging && rowScroll.maxValue > 0)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        names.forEachIndexed { index, name ->
            val selected = index == selectedIndex
            val anchor = remember { intArrayOf(0, 0) }
            val labelColor by animateColorAsState(
                targetValue = if (selected) SmuglyTextPrimary else SmuglyTextSecondary,
                animationSpec = tween(TabIndicatorMs, easing = FastOutSlowInEasing),
                label = "tabLabel"
            )
            // Step aside while the held tab is over this slot, so the gap follows the finger.
            val shiftTarget = shiftOf(index)
            val shift by animateFloatAsState(
                targetValue = shiftTarget,
                // Animated only while a tab is actually being carried. Once the reorder lands the
                // layout already puts every tab where it belongs, so animating the offset away
                // would play the move a second time, from the wrong side.
                animationSpec = if (dragging) {
                    tween(TabIndicatorMs, easing = FastOutSlowInEasing)
                } else {
                    snap()
                },
                label = "tabShift"
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
                    // Desktop right-click: intercept on the Initial pass so it never becomes a
                    // select, and so the system context menu does not steal the event.
                    .then(
                        if (isDesktop) {
                            Modifier.pointerInput(index) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        if (event.type == PointerEventType.Press &&
                                            event.buttons.isSecondaryPressed
                                        ) {
                                            event.changes.forEach { it.consume() }
                                            menuNow(index, anchor[0], anchor[1])
                                        }
                                    }
                                }
                            }
                        } else Modifier
                    )
                    .pointerInput(index) {
                        detectTapGestures(onTap = { selectNow(index) })
                    }
                    // Long-press + drag reorders on every platform (phone and mouse).
                    .pointerInput(index, names.size, isDesktop) {
                        val slop = viewConfiguration.touchSlop
                        var moved = false
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                dragFrom = index
                                dragTo = index
                                dragNames = namesNow
                                dragDx = 0f
                                moved = false
                                dragActiveNow(true)
                            },
                            onDrag = { change, delta ->
                                change.consume()
                                dragDx += delta.x
                                if (!moved && abs(dragDx) > slop) {
                                    moved = true
                                    menuDismissNow()
                                }
                                // Recomputed on every frame, not on release: this is what the
                                // other tabs animate off, so the user can see the slot open up
                                // before letting go.
                                val next = slotAt(spans, index, dragDx)
                                if (next != dragTo) {
                                    dragTo = next
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            },
                            onDragEnd = {
                                val drop = if (moved) dragTo else index
                                // The folder menu opens on a hold that did NOT become a drag.
                                // Opening it on pick-up (the old behaviour) dropped a full-screen
                                // panel over the tabs the moment the finger settled, so the drag
                                // that followed happened underneath it, unseen.
                                if (!moved && !isDesktop) menuNow(index, anchor[0], anchor[1])
                                settle(
                                    scope, spans, index, drop, dragDx,
                                    onOffset = { dragDx = it },
                                    onFinished = {
                                        if (drop != index) {
                                            // Deliberately does NOT clear the drag here: the
                                            // reordered list is still on its way back, and
                                            // dropping the offsets first is what made the tab
                                            // flick back to its old slot before moving.
                                            moveNow(index, drop)
                                        } else {
                                            dragFrom = -1
                                            dragTo = -1
                                        }
                                    }
                                )
                                dragActiveNow(false)
                            },
                            onDragCancel = {
                                settle(
                                    scope, spans, index, index, dragDx,
                                    onOffset = { dragDx = it },
                                    onFinished = { dragFrom = -1; dragTo = -1 }
                                )
                                dragActiveNow(false)
                            }
                        )
                    }
                    .zIndex(if (dragging && dragFrom == index) 1f else 0f)
                    .graphicsLayer {
                        translationX = if (dragging && dragFrom == index) dragDx else shift
                    }
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
            val carriedTarget = when {
                !dragging -> 0f
                // The open folder is the one in hand: the underline rides along with it.
                dragFrom == selectedIndex -> dragDx
                // Something else is being carried past it: the open tab steps aside, so does this.
                else -> shiftOf(selectedIndex)
            }
            val carried by animateFloatAsState(
                targetValue = carriedTarget,
                // Following a finger must not lag, and once the reorder lands the measured
                // position is already right — so only the step-aside is animated.
                animationSpec = if (dragging && dragFrom != selectedIndex) {
                    tween(TabIndicatorMs, easing = FastOutSlowInEasing)
                } else {
                    snap()
                },
                label = "tabIndicatorCarry"
            )
            Box(
                Modifier
                    .width(with(density) { indicatorWidth.toDp() })
                    .fillMaxHeight()
                    // Tab positions are measured inside the scrolling row, so they are content
                    // coordinates; the indicator is drawn outside it and has to be shifted by
                    // however far the row has scrolled, or it sits under the wrong tab (or under
                    // no tab at all) as soon as the row moves.
                    .graphicsLayer { translationX = indicatorX + carried - rowScroll.value }
                    .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                    .background(SmuglyAccent)
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
