package app.smugly.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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

        // The panel's announce line. It is the only channel an operator has to say anything to
        // their users inside the app ("questions -> @support"), and it was parsed and stored all
        // along but never drawn anywhere.
        if (info.announce.isNotBlank()) {
            Text(
                text = info.announce.trim(),
                color = SmuglyTextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
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

/**
 * One sub-group inside a subscription folder: a text-only header card, then the servers as their
 * own cards underneath. The header is not a wrapper around the rows — that was cards-in-cards.
 *
 * Text only: name, optional description, chevron. No icon, no marker.
 *
 * Tapping the header folds the group away. Folding is clipped by [AnimatedVisibility], so the
 * rows slide up into the heading instead of over the next group's text.
 */
@Composable
fun CategorySection(
    name: String,
    description: String,
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    // Points down when the group is open and right when it is folded; it travels rather than
    // swapping, so the state change reads as one movement.
    val angle by animateFloatAsState(
        targetValue = if (collapsed) -90f else 0f,
        animationSpec = tween(CategoryFoldMs, easing = FastOutSlowInEasing),
        label = "categoryChevron"
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(SmuglyCard)
                .handClickable(onClick = onToggle)
                .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    color = SmuglyTextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        color = SmuglyTextMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = SmuglyTextSecondary,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .height(18.dp)
                    .graphicsLayer { rotationZ = angle }
            )
        }
        AnimatedVisibility(
            visible = !collapsed,
            // Towards the top, i.e. **under the heading** — the default anchors the content to the
            // bottom, so folding pulled the first servers out of sight first and left the last one
            // sitting under the title until the very end. Folding should look like the group
            // sliding up into the row you just tapped.
            enter = expandVertically(
                animationSpec = tween(CategoryFoldMs, easing = FastOutSlowInEasing),
                expandFrom = Alignment.Top
            ),
            exit = shrinkVertically(
                animationSpec = tween(CategoryFoldMs, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.Top
            )
        ) {
            // A hair of inset — same card size as the heading, just enough to read as children.
            Column(Modifier.padding(horizontal = 4.dp)) { content() }
        }
    }
}

/** How long a category takes to fold away — and how long its chevron takes to turn. */
private const val CategoryFoldMs = 180

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
 * Folder tabs: local configs plus one per subscription. Hidden entirely when there are no
 * subscriptions, so a user who never imports one sees the app exactly as before.
 *
 * v2rayNG-style — plain labels with a single underline that *travels* to the tab you pick rather
 * than a highlight appearing in a new place. Tabs keep their natural width (folder names run long)
 * and the row scrolls sideways when they do not fit, so the indicator has to be measured rather
 * than computed from an equal-width grid.
 *
 * Gestures:
 * - Tap / LMB click → select tab; on the tab that is **already open**, the folder menu
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
    val selectedNow by rememberUpdatedState(selectedIndex)
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
    // How far tab [index] steps aside for the tab being carried. The OPEN folder's underline
    // must use the same value (animated) or the red bar jumps while the label eases aside.
    fun shiftOf(index: Int): Float {
        if (!dragging || dragTo < 0 || index == dragFrom) return 0f
        val held = (spans.getOrNull(dragFrom)?.second ?: 0f) + gapPx
        return when {
            index in (dragFrom + 1)..dragTo -> -held
            index in dragTo until dragFrom -> held
            else -> 0f
        }
    }
    // Same curve as each tab's shiftAnimated — indicator used to read shiftOf() raw, so the bar
    // teleported to the gap as soon as dragTo flipped while the active label still eased over.
    val selectedShiftTarget =
        if (dragging && dragFrom != selectedIndex) shiftOf(selectedIndex) else 0f
    val selectedShiftAnim by animateFloatAsState(
        targetValue = selectedShiftTarget,
        animationSpec = tween(TabIndicatorMs, easing = FastOutSlowInEasing),
        label = "selectedTabShift"
    )
    val scope = rememberCoroutineScope()
    val target = spans.getOrElse(selectedIndex) { 0f to 0f }
    // The underline travels when the user picks a different tab, and teleports when the tabs
    // themselves are rearranged — in a reorder the tab it belongs to is already in its new place,
    // so sliding there afterwards plays the move a second time. Both cases change the measured
    // target, so they are told apart by whether the tab ORDER changed; the flag is held until the
    // new measurement actually lands, because that is a layout pass later than the reorder.
    // "A reorder is settling" — held for a couple of frames rather than for one state change.
    // Everything that keys off the SELECTED SLOT has to freeze during it: the slot number changes
    // as soon as the folders are rearranged, but the row is measured a layout pass later, so for
    // those frames an index points at the wrong tab. Anything animated across that gap plays out
    // on the wrong tab — the underline slid in from the old tab's width, and the highlight lit up
    // whichever folder happened to be sitting at the destination.
    var settling by remember { mutableStateOf(false) }
    val lastNames = remember { arrayOfNulls<List<String>>(1) }
    if (lastNames[0] != names) {
        if (lastNames[0] != null) settling = true
        lastNames[0] = names
    }
    LaunchedEffect(names) {
        if (!settling) return@LaunchedEffect
        // One frame for the new order, one for the measurements it produces.
        withFrameNanos { }
        withFrameNanos { }
        settling = false
    }
    val indicatorAnimX = remember { Animatable(0f) }
    val indicatorAnimW = remember { Animatable(0f) }
    LaunchedEffect(target.first, target.second) {
        if (target.second <= 0f) return@LaunchedEffect
        // First real measurement (or remount after leaving Home for Settings/Diagnostics):
        // Animatable starts at 0, and animating 0→tab reads as the bar "fading/sliding in".
        // Only travel between tabs once the underline is already on screen.
        val firstPlace = indicatorAnimW.value <= 0f
        if (settling || firstPlace) {
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
    /** Width of the visible strip, in px — the row scrolls, so its own size is the viewport. */
    var rowWidth by remember { mutableStateOf(0) }
    // Bring the open folder's tab into view. Swiping the pager past the edge of the strip used to
    // leave the tabs where they were: the folder changed, the underline travelled off-screen, and
    // the row still showed the folders you had left. Follows any selection, not just a swipe —
    // launching into a folder that was saved from the far end lands on it already visible.
    LaunchedEffect(selectedIndex, target.first, target.second, rowWidth, rowScroll.maxValue) {
        if (dragging || rowWidth <= 0) return@LaunchedEffect
        val (tabX, tabWidth) = spans.getOrNull(selectedIndex) ?: return@LaunchedEffect
        if (tabWidth <= 0f) return@LaunchedEffect
        // Stop a little short of the edge so the neighbouring tab peeks out and the row still
        // reads as scrollable.
        val margin = with(density) { 28.dp.toPx() }
        val from = rowScroll.value.toFloat()
        val scrollTo = when {
            tabX - margin < from -> tabX - margin
            tabX + tabWidth + margin > from + rowWidth -> tabX + tabWidth + margin - rowWidth
            else -> return@LaunchedEffect
        }
        rowScroll.animateScrollTo(
            scrollTo.coerceIn(0f, rowScroll.maxValue.toFloat()).toInt(),
            tween(TabIndicatorMs, easing = FastOutSlowInEasing)
        )
    }
    Column(modifier = modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { rowWidth = it.size.width }
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
            // Colour/weight state must follow the FOLDER, not the slot index. Without this, a
            // reorder leaves the old "selected" Color animation on the destination index for a
            // frame — the tab you just dropped there flashes white as if it were active.
            key(name) {
            val selected = index == selectedIndex
            val anchor = remember { intArrayOf(0, 0) }
            val labelColor by animateColorAsState(
                targetValue = if (selected) SmuglyTextPrimary else SmuglyTextSecondary,
                // Cross-fades when the user picks another tab, snaps when the tabs are rearranged.
                // The highlight belongs to the folder, not to the slot: after a reorder both tabs
                // hold new text, so fading between the old and new colour lights up the wrong tab
                // for the length of the fade.
                animationSpec = if (settling) {
                    snap()
                } else {
                    tween(TabIndicatorMs, easing = FastOutSlowInEasing)
                },
                label = "tabLabel"
            )
            // Step aside while the held tab is over this slot, so the gap follows the finger.
            val shiftTarget = shiftOf(index)
            val shiftAnimated by animateFloatAsState(
                targetValue = shiftTarget,
                animationSpec = tween(TabIndicatorMs, easing = FastOutSlowInEasing),
                label = "tabShift"
            )
            // Applied only while a tab is actually being carried, and read as a plain 0 otherwise.
            // Animating it back — even with `snap()` — costs a frame, and that frame lands after
            // the reordered layout: every neighbour flicks back to where it used to be for one
            // frame before settling.
            val shift = if (dragging) shiftAnimated else 0f
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
                        detectTapGestures(
                            onTap = {
                                // Tapping the folder you are already in opens its menu — the same
                                // panel a long-press opens. Selecting it again does nothing
                                // visible, so the tap is free, and the management actions stop
                                // being hidden behind a gesture nobody discovers.
                                if (index == selectedNow) {
                                    menuNow(index, anchor[0], anchor[1])
                                } else {
                                    selectNow(index)
                                }
                            }
                        )
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
                                            // Nothing reordered, so no new list is coming to clear
                                            // this for us.
                                            dragFrom = -1
                                            dragTo = -1
                                            dragDx = 0f
                                            dragNames = null
                                        }
                                    }
                                )
                                dragActiveNow(false)
                            },
                            onDragCancel = {
                                settle(
                                    scope, spans, index, index, dragDx,
                                    onOffset = { dragDx = it },
                                    onFinished = {
                                        dragFrom = -1
                                        dragTo = -1
                                        dragDx = 0f
                                        dragNames = null
                                    }
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
    }
        // Drawn, not laid out. The bar lives outside the scrolling row (so it can travel between
        // tabs of different widths), which means it depends on positions the row reports during
        // layout — and reading those in composition is always one frame behind: on the frame a
        // reorder lands, the tabs are already in their new places and the bar is still drawing
        // itself against the old ones. Reading them in the DRAW phase closes that gap: the draw
        // runs after layout, in the same frame, and re-runs on its own when a position changes.
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .padding(horizontal = 10.dp)
                .drawBehind {
                    val sel = spans.getOrNull(selectedIndex) ?: return@drawBehind
                    // Underline follows the OPEN folder: finger-drag when that tab is held, the
                    // same eased shift the label uses when a neighbour is shoved through its slot
                    // (never the raw target — that is the jump while text still animates).
                    val carried = when {
                        !dragging -> 0f
                        dragFrom == selectedIndex -> dragDx
                        else -> selectedShiftAnim
                    }
                    // Fresh measurements while a reorder settles, the travelling animation
                    // otherwise — that animation is what makes picking another tab read as one
                    // bar moving rather than two bars blinking.
                    val x = (if (settling) sel.first else indicatorAnimX.value) +
                        carried - rowScroll.value
                    val w = if (settling) sel.second else indicatorAnimW.value
                    if (w <= 0f) return@drawBehind
                    drawRoundRect(
                        color = SmuglyAccent,
                        topLeft = Offset(x, 0f),
                        size = Size(w, size.height),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    )
                }
        )
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
    // Where the held tab actually ends up — which is NOT the target tab's old left edge unless
    // the two happen to be the same width.
    //
    // Moving right, everything it passes slides left by its own width, so it comes to rest against
    // the target's right edge: x_target + w_target - w_held. Moving left it does take the target's
    // left edge, because the block it displaces slides right instead. Using the left edge for both
    // is why a rightward drop settled short of its slot — by exactly the width difference — and
    // then jumped once the real layout arrived.
    val fromX = spans.getOrNull(from)?.first ?: 0f
    val fromW = spans.getOrNull(from)?.second ?: 0f
    val targetX = spans.getOrNull(target)?.first ?: 0f
    val targetW = spans.getOrNull(target)?.second ?: 0f
    val land = when {
        target == from -> 0f
        target > from -> (targetX + targetW - fromW) - fromX
        else -> targetX - fromX
    }
    scope.launch {
        Animatable(current).animateTo(land, tween(180, easing = FastOutSlowInEasing)) {
            onOffset(value)
        }
        // Only now does the tab stop being "the dragged one" — clearing that first is what made
        // the release look like a snap-back: the offset was still animating but nothing was
        // applying it any more.
        //
        // The offset is deliberately NOT zeroed here. When a move was reported, the reordered list
        // is still travelling back through the caller, and dropping the offset before it lands put
        // the tab in its old slot for those frames — the flick you see right after the animation
        // stops. Whoever asked for the settle clears it: at once when nothing moved, and when the
        // new order arrives if something did.
        onFinished()
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
