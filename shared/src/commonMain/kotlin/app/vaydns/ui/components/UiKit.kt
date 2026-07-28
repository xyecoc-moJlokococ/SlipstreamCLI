package app.vaydns.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import app.vaydns.AppVersion
import app.vaydns.ui.theme.SlipnetAccent
import app.vaydns.ui.theme.SlipnetBg
import app.vaydns.ui.theme.SlipnetButtonTextPrimary
import app.vaydns.ui.theme.SlipnetCard
import app.vaydns.ui.theme.SlipnetCardSoft
import app.vaydns.ui.theme.SlipnetInput
import app.vaydns.ui.theme.SlipnetStroke
import app.vaydns.ui.theme.SlipnetTextMuted
import app.vaydns.ui.theme.SlipnetTextPrimary
import app.vaydns.ui.theme.SlipnetTextSecondary

/** Clickable with hand cursor, no Material ripple / light-flash hover. For actual buttons. */
@Composable
fun Modifier.handClickable(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this
        .pointerHoverIcon(PointerIcon.Hand, enabled)
        .clickable(
            enabled = enabled,
            interactionSource = interaction,
            indication = null,
            role = Role.Button,
            onClick = onClick
        )
}

/**
 * Clickable that keeps the normal arrow cursor — for large selectable surfaces such as a profile
 * row. The hand cursor is reserved for the controls inside them (delete, ⋮), which do keep it:
 * `pointerHoverIcon` on a child wins over an ancestor's.
 */
@Composable
fun Modifier.surfaceClickable(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.clickable(
        enabled = enabled,
        interactionSource = interaction,
        indication = null,
        onClick = onClick
    )
}

/**
 * Drop-down menu drawn **inside the normal composition** — deliberately not a
 * [androidx.compose.ui.window.Popup].
 *
 * A Popup is a second Android window: opening one costs a window relayout, a focus
 * hand-off, and a cross-window `postAndWait` on every animated frame, and it keeps
 * asking for vsync callbacks while it is open. Drawing the panel as a plain overlay
 * in the host screen costs a single layout pass instead.
 *
 * Place this last inside the screen's root Box so it paints over everything.
 * The panel's top corner sits at [anchorY] (root coordinates), 8dp in from the right
 * edge — or at [anchorX] when given — clamped so it never runs off the screen.
 */
@Composable
fun MenuLayer(
    visible: Boolean,
    anchorY: Int,
    onDismiss: () -> Unit,
    /**
     * Panel's left edge in root coordinates, for anchors that do not sit on the right edge
     * (a folder tab). Null keeps the right-aligned placement the + and ⋮ buttons use.
     */
    anchorX: Int? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    // One float drives fade + scale, so showing/hiding is a layer property change
    // rather than an AnimatedVisibility transition tree. [mounted] flips only at the
    // two ends of the animation — anim.value itself is read inside graphicsLayer, so
    // the rows are not recomposed on every frame.
    val anim = remember { Animatable(0f) }
    var mounted by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            mounted = true
            anim.animateTo(1f, tween(140, easing = LinearOutSlowInEasing))
        } else {
            anim.animateTo(0f, tween(110, easing = LinearOutSlowInEasing))
            mounted = false
        }
    }
    if (!mounted) return

    val gutterPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    Box(Modifier.fillMaxSize()) {
        // Outside tap closes the menu (same as a Popup's dismiss-on-outside-touch).
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )
        Layout(
            content = {
                Column(
                    modifier = Modifier
                        .widthIn(min = 168.dp, max = 280.dp)
                        .graphicsLayer {
                            val p = anim.value
                            alpha = p
                            // Grow out of the control that opened it: the + / ⋮ on the right
                            // edge, or the left edge of a folder tab.
                            scaleX = 0.94f + 0.06f * p
                            scaleY = 0.94f + 0.06f * p
                            transformOrigin = TransformOrigin(if (anchorX == null) 1f else 0f, 0f)
                        }
                        // Square corners, panel painted in the app background, no outline —
                        // only a light drop shadow lifts it off the content underneath.
                        .shadow(6.dp, RectangleShape)
                        .background(SlipnetBg)
                        .padding(vertical = 4.dp),
                    content = content
                )
            }
        ) { measurables, constraints ->
            val panel = measurables.first().measure(
                constraints.copy(minWidth = 0, minHeight = 0)
            )
            layout(constraints.maxWidth, constraints.maxHeight) {
                val maxX = (constraints.maxWidth - panel.width).coerceAtLeast(0)
                panel.place(
                    x = anchorX?.coerceIn(0, maxX) ?: (maxX - gutterPx).coerceAtLeast(0),
                    y = anchorY.coerceIn(
                        0,
                        (constraints.maxHeight - panel.height).coerceAtLeast(0)
                    )
                )
            }
        }
    }
}

/** Menu row without Material hover flash — subtle soft fill only while hovered. */
@Composable
fun MenuRow(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // fillMaxWidth is safe here: parent Column already has a finite max width (widthIn).
    Text(
        text = text,
        color = SlipnetTextPrimary,
        fontSize = 14.sp,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .background(if (hovered) SlipnetCardSoft else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

/** Edge icon hit target — flush to window edges (design: no extra gutter outside). */
/** Phone-friendly touch target (Material min 48; we go larger for fat-finger taps). */
private val TopBarIconSize = 56.dp
private val TopBarIconGlyph = 28.dp

@Composable
fun TopBar(
    title: String,
    onMenu: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onAdd: (() -> Unit)? = null,
    /** Reports the + button's top edge in root coords so the screen can anchor its menu. */
    onAddAnchor: (Int) -> Unit = {}
) {
    // Full-bleed bar: menu / + sit against the physical window edges; 56dp targets.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TopBarIconSize)
            .background(SlipnetBg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            onBack != null -> {
                Box(
                    Modifier
                        .size(TopBarIconSize)
                        .handClickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        null,
                        tint = SlipnetTextPrimary,
                        modifier = Modifier.size(TopBarIconGlyph)
                    )
                }
            }
            onMenu != null -> {
                Box(
                    Modifier
                        .size(TopBarIconSize)
                        .handClickable(onClick = onMenu),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Menu,
                        null,
                        tint = SlipnetTextPrimary,
                        modifier = Modifier.size(TopBarIconGlyph)
                    )
                }
            }
            else -> Spacer(Modifier.width(8.dp))
        }
        Text(
            text = title,
            color = SlipnetTextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
        )
        if (onAdd != null) {
            Box(
                Modifier
                    .size(TopBarIconSize)
                    .onGloballyPositioned { onAddAnchor(it.positionInRoot().y.toInt()) }
                    .handClickable(onClick = onAdd),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Add,
                    null,
                    tint = SlipnetTextPrimary,
                    modifier = Modifier.size(TopBarIconGlyph)
                )
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        color = SlipnetTextPrimary,
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
    )
}

@Composable
fun HintText(text: String) {
    Text(
        text = text,
        color = SlipnetTextMuted,
        fontSize = 12.sp,
        lineHeight = 15.sp,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
fun LabeledField(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Text(
            text = label,
            color = SlipnetTextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        content()
    }
}

@Composable
fun SlipnetTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    singleLine: Boolean = true,
    password: Boolean = false,
    number: Boolean = false,
    minLines: Int = 1,
    monospace: Boolean = false
) {
    val shape = RoundedCornerShape(10.dp)
    // External modifier (e.g. weight) must sit on BasicTextField itself — not only on
    // decorationBox — otherwise Row.weight is ignored and layout blows up.
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                number -> KeyboardType.Number
                password -> KeyboardType.Password
                else -> KeyboardType.Text
            }
        ),
        textStyle = TextStyle(
            color = SlipnetTextPrimary,
            fontSize = if (monospace) 12.sp else 15.sp,
            // Inherit Roboto from theme; keep monospaced only for logs/JSON.
            fontFamily = if (monospace) FontFamily.Monospace else null
        ),
        cursorBrush = SolidColor(SlipnetAccent),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(SlipnetInput)
                    .border(1.dp, SlipnetStroke, shape)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty() && hint.isNotEmpty()) {
                    Text(hint, color = SlipnetTextMuted, fontSize = 15.sp)
                }
                inner()
            }
        }
    )
}

@Composable
fun PillSelector(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelected: (Int) -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SlipnetCard)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) SlipnetAccent else SlipnetCard)
                    .handClickable { onSelected(index) }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (selected) SlipnetButtonTextPrimary else SlipnetTextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SlipnetAccent)
            .handClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = SlipnetButtonTextPrimary, fontWeight = FontWeight.Normal, fontSize = 15.sp)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, SlipnetStroke, RoundedCornerShape(10.dp))
            .handClickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = SlipnetTextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
fun AccentLinkButton(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = SlipnetAccent,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .handClickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp)
    )
}

@Composable
fun SlipnetCheckbox(checked: Boolean, label: String, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .handClickable { onCheckedChange(!checked) }
            .padding(vertical = 0.dp)
            .height(36.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.size(32.dp),
            colors = CheckboxDefaults.colors(
                checkedColor = SlipnetAccent,
                uncheckedColor = SlipnetTextMuted,
                checkmarkColor = SlipnetButtonTextPrimary
            )
        )
        Text(
            label,
            color = SlipnetTextSecondary,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 2.dp)
        )
    }
}

/** Hold-shrink while reordering — matches original PROFILE_DRAG_SCALE = 0.96f. */
private const val ProfileDragScale = 0.96f

@Composable
fun ProfileCard(
    name: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    /** Null hides the delete button — subscription servers are owned by the subscription. */
    onDelete: (() -> Unit)?,
    onMoreClick: () -> Unit,
    /** Reports the ⋮ button's top edge in root coords so the screen can anchor its menu. */
    onMoreAnchor: (Int) -> Unit,
    /** Vertical drag offset while this card is the active reorder target. */
    dragOffsetY: Float = 0f,
    isDragging: Boolean = false,
    /** Sibling shift to open a gap during reorder (px). */
    gapOffsetY: Float = 0f,
    onLongPressDragStart: () -> Unit = {},
    onLongPressDrag: (dy: Float) -> Unit = {},
    onLongPressDragEnd: () -> Unit = {},
    onLongPressDragCancel: () -> Unit = {},
    enableReorder: Boolean = true
) {
    val shape = RoundedCornerShape(12.dp)
    val haptics = LocalHapticFeedback.current
    // Selection border / marker cross-fade onto the new card (snappy, not a hard cut).
    val selectAnim = tween<Color>(durationMillis = 200, easing = FastOutSlowInEasing)
    val selectFloatAnim = tween<Float>(durationMillis = 200, easing = FastOutSlowInEasing)
    val selectDpAnim = tween<androidx.compose.ui.unit.Dp>(durationMillis = 200, easing = FastOutSlowInEasing)
    val borderColor by animateColorAsState(
        targetValue = if (selected) SlipnetAccent else Color.Transparent,
        animationSpec = selectAnim,
        label = "profileBorder"
    )
    val markerFill by animateColorAsState(
        targetValue = if (selected) SlipnetAccent else Color.Transparent,
        animationSpec = selectAnim,
        label = "profileMarkerFill"
    )
    val markerRing by animateFloatAsState(
        targetValue = if (selected) 0f else 1f,
        animationSpec = selectFloatAnim,
        label = "profileMarkerRing"
    )
    val markerSize by animateDpAsState(
        targetValue = if (selected) 10.dp else 9.dp,
        animationSpec = selectDpAnim,
        label = "profileMarkerSize"
    )
    val scale by animateFloatAsState(
        targetValue = if (isDragging) ProfileDragScale else 1f,
        animationSpec = tween(140, easing = LinearOutSlowInEasing),
        label = "profileDragScale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (isDragging) 0.92f else 1f,
        animationSpec = tween(140, easing = LinearOutSlowInEasing),
        label = "profileDragAlpha"
    )
    val elev by animateFloatAsState(
        targetValue = if (isDragging) 12f else 0f,
        animationSpec = tween(140),
        label = "profileDragElev"
    )
    val gapAnim by animateFloatAsState(
        targetValue = gapOffsetY,
        animationSpec = tween(160, easing = LinearOutSlowInEasing),
        label = "profileGap"
    )
    val translationY = if (isDragging) dragOffsetY else gapAnim

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .zIndex(if (isDragging) 2f else 0f)
            .graphicsLayer {
                this.translationY = translationY
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                shadowElevation = elev
            }
            .clip(shape)
            .background(SlipnetCard)
            // Always draw border; alpha/color animates so selection "moves" smoothly.
            .border(1.dp, borderColor, shape)
            .then(
                if (enableReorder) {
                    Modifier.pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                // One tick the moment the card is picked up, so reordering is
                                // confirmed by feel rather than by watching the card move.
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLongPressDragStart()
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onLongPressDrag(dragAmount.y)
                            },
                            onDragEnd = { onLongPressDragEnd() },
                            onDragCancel = { onLongPressDragCancel() }
                        )
                    }
                } else Modifier
            )
            // The card is a selectable surface, not a button — arrow cursor. The delete and ⋮
            // controls inside it still use handClickable and override the cursor themselves.
            .surfaceClickable(onClick = onClick)
            .padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Marker centered in the gutter between card edge and title text.
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(40.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(markerSize)
                    .clip(CircleShape)
                    .background(markerFill)
                    .border(
                        width = 1.5.dp,
                        color = SlipnetAccent.copy(alpha = markerRing),
                        shape = CircleShape
                    )
            )
        }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                name,
                color = SlipnetTextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Xray and empty subtitles: no second line (avoids "{..." junk).
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = SlipnetTextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (onDelete != null) {
            Box(
                Modifier
                    .size(48.dp)
                    .handClickable(onClick = onDelete),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Delete,
                    null,
                    tint = SlipnetTextSecondary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        // ⋮ menu: Edit + Export (pencil removed from the row). The panel itself is
        // drawn by the host screen — a card sits inside a clipping scroll container.
        Box(
            Modifier
                .size(48.dp)
                .onGloballyPositioned { onMoreAnchor(it.positionInRoot().y.toInt()) }
                .handClickable(onClick = onMoreClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.MoreVert,
                null,
                tint = SlipnetTextSecondary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * Bottom status strip + floating connect button.
 * Button sits half on the bar and half above it (over the profile list).
 */
@Composable
fun BottomConnectBar(
    status: String,
    traffic: String,
    running: Boolean,
    loading: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val barHeight = 56.dp
    val buttonSize = 66.dp
    val totalHeight = barHeight + buttonSize / 2
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(totalHeight)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .align(Alignment.BottomCenter)
                .background(SlipnetCard)
                .padding(start = 16.dp, end = 100.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    status,
                    color = SlipnetAccent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )
                Text(
                    traffic,
                    color = SlipnetTextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 13.sp
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = barHeight / 2 - 2.dp)
                .size(buttonSize)
                .clip(CircleShape)
                .background(SlipnetAccent)
                .handClickable(enabled = !loading, onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = SlipnetButtonTextPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    text = if (running) "■" else "▶",
                    color = SlipnetButtonTextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Normal
                )
            }
        }
    }
}

@Composable
fun DrawerPanel(
    selected: AppDrawerItem,
    showDiagnostics: Boolean,
    onSelect: (AppDrawerItem) -> Unit,
    modifier: Modifier = Modifier,
    /** Branding line under nav items, e.g. "0.1.0 · 2026-07-27". */
    versionLabel: String = AppVersion.name + " · " + AppVersion.buildDate
) {
    val items = buildList {
        add(AppDrawerItem.HOME)
        if (showDiagnostics) add(AppDrawerItem.DIAGNOSTICS)
        add(AppDrawerItem.SETTINGS)
    }
    Column(
        modifier = modifier
            .background(SlipnetCard)
            .padding(top = 20.dp, bottom = 16.dp, start = 12.dp, end = 12.dp)
    ) {
        items.forEach { item ->
            val isSelected = item == selected
            val interaction = remember(item) { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            val bg = when {
                isSelected -> SlipnetAccent.copy(alpha = 0.22f)
                hovered -> SlipnetCardSoft
                else -> Color.Transparent
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg)
                    .hoverable(interaction)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onSelect(item) }
                    )
                    // Tall row (~52dp) so drawer items are easy to hit on phone.
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                Text(
                    text = item.label(),
                    color = if (isSelected) SlipnetAccent else SlipnetTextPrimary,
                    fontWeight = FontWeight.Normal,
                    fontSize = 17.sp
                )
            }
        }
        Spacer(Modifier.weight(1f))
        // Branding + version pinned to the bottom of the drawer.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                "Slipstream",
                color = SlipnetTextPrimary,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp
            )
            Text(
                versionLabel,
                color = SlipnetTextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

enum class AppDrawerItem {
    HOME, DIAGNOSTICS, SETTINGS;

    @Composable
    fun label(): String = when (this) {
        HOME -> app.vaydns.t(app.vaydns.S.HOME)
        DIAGNOSTICS -> app.vaydns.t(app.vaydns.S.DIAGNOSTICS)
        SETTINGS -> app.vaydns.t(app.vaydns.S.SETTINGS)
    }
}

/**
 * Modal card overlay with the same enter/exit feel as [MenuLayer]
 * (fade + slight scale). Stays composed until the exit animation finishes —
 * parent must pass [visible] and keep composing while animating out.
 */
@Composable
fun AnimatedModalCard(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: Modifier = Modifier.padding(18.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = visible
    if (!visibleState.currentState && !visibleState.targetState) return

    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Dim background — click to dismiss (same as Android dialog outside-tap).
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn(animationSpec = tween(140, easing = LinearOutSlowInEasing)),
            exit = fadeOut(animationSpec = tween(120, easing = LinearOutSlowInEasing))
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismissRequest
                    )
            )
        }
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn(animationSpec = tween(140, easing = LinearOutSlowInEasing)) + scaleIn(
                initialScale = 0.94f,
                animationSpec = tween(200, easing = LinearOutSlowInEasing),
                transformOrigin = TransformOrigin(0.5f, 0.5f)
            ),
            exit = fadeOut(animationSpec = tween(120, easing = LinearOutSlowInEasing)) + scaleOut(
                targetScale = 0.96f,
                animationSpec = tween(160, easing = LinearOutSlowInEasing),
                transformOrigin = TransformOrigin(0.5f, 0.5f)
            )
        ) {
            // Side gutters so the card never kisses window edges (narrow desktop).
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = modifier
                        .fillMaxWidth()
                        .shadow(12.dp, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .background(SlipnetCard)
                        .border(1.dp, SlipnetStroke, RoundedCornerShape(14.dp))
                        // Don't dismiss when tapping the card itself.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                        .then(contentPadding),
                    content = content
                )
            }
        }
    }
}

/**
 * Dark confirm dialog (delete profile, etc.) — matches Android AlertDialog flow,
 * animated like the + / ⋮ popup menu.
 */
@Composable
fun ConfirmDialog(
    visible: Boolean,
    title: String,
    message: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AnimatedModalCard(
        visible = visible,
        onDismissRequest = onDismiss,
        // fillMaxWidth + max cap; outer 16.dp gutters applied in AnimatedModalCard.
        modifier = Modifier.widthIn(max = 360.dp)
    ) {
        Text(title, color = SlipnetTextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Normal)
        Text(
            message,
            color = SlipnetTextSecondary,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 10.dp, bottom = 18.dp)
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                cancelLabel,
                color = SlipnetTextSecondary,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .handClickable(onClick = onDismiss)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                confirmLabel,
                color = SlipnetAccent,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .handClickable(onClick = onConfirm)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }
}

/**
 * Crash report viewer — title + scrollable body + Copy / Close, menu-style animation.
 */
@Composable
fun CrashReportDialog(
    visible: Boolean,
    title: String,
    body: String,
    copyLabel: String,
    closeLabel: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    AnimatedModalCard(
        visible = visible,
        onDismissRequest = onDismiss,
        // fillMaxWidth + max cap; outer 16.dp gutters applied in AnimatedModalCard.
        modifier = Modifier.widthIn(max = 420.dp),
        contentPadding = Modifier.padding(16.dp)
    ) {
        Text(title, color = SlipnetTextPrimary, fontSize = 17.sp)
        Box(
            Modifier
                .fillMaxWidth()
                .height(280.dp)
                .padding(top = 12.dp, bottom = 14.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SlipnetInput)
                .padding(10.dp)
        ) {
            val scroll = rememberScrollState()
            Text(
                text = body,
                color = SlipnetTextSecondary,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                closeLabel,
                color = SlipnetTextSecondary,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .handClickable(onClick = onDismiss)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                copyLabel,
                color = SlipnetAccent,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .handClickable(onClick = onCopy)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }
}

/**
 * In-app toast: appears instantly, fades out on hide.
 * Keep composed with [visible]=false until exit animation ends (like popup menu).
 */
@Composable
fun AppToast(
    visible: Boolean,
    message: String,
    modifier: Modifier = Modifier
) {
    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = visible
    if (!visibleState.currentState && !visibleState.targetState) return

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visibleState = visibleState,
            // Snap in (no enter fade) — user asked for instant appear.
            enter = fadeIn(animationSpec = tween(0)),
            exit = fadeOut(animationSpec = tween(220, easing = LinearOutSlowInEasing))
        ) {
            Text(
                text = message,
                color = SlipnetButtonTextPrimary,
                fontSize = 13.sp,
                modifier = Modifier
                    .padding(bottom = 88.dp, start = 16.dp, end = 16.dp)
                    .shadow(8.dp, RoundedCornerShape(10.dp))
                    .clip(RoundedCornerShape(10.dp))
                    .background(SlipnetCard.copy(alpha = 0.96f))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }
}
