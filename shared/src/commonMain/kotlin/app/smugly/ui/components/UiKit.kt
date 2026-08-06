package app.smugly.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.PinnableContainer
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
import app.smugly.AppVersion
import app.smugly.ui.theme.SmuglyAccent
import app.smugly.ui.theme.SmuglyBg
import app.smugly.ui.theme.SmuglyButtonTextPrimary
import app.smugly.ui.theme.SmuglyCard
import app.smugly.ui.theme.SmuglyCardSoft
import app.smugly.ui.theme.SmuglyInput
import app.smugly.ui.theme.SmuglyStroke
import app.smugly.ui.theme.SmuglyTextMuted
import app.smugly.ui.theme.SmuglyTextPrimary
import app.smugly.ui.theme.SmuglyTextSecondary

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
     * (a folder tab / protocol field). Null keeps the right-aligned placement the + and ⋮
     * buttons use.
     */
    anchorX: Int? = null,
    /**
     * Preferred panel width in px (e.g. match the field that opened it). Clamped to the
     * screen; ignored when null.
     */
    panelWidthPx: Int? = null,
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

    val density = LocalDensity.current
    val gutterPx = with(density) { 8.dp.roundToPx() }
    val minW = with(density) { 200.dp.toPx() }.toInt()
    val defaultMaxW = with(density) { 300.dp.toPx() }.toInt()
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
                        // Hug the longest label. The rows are fillMaxWidth, so without this the
                        // panel measured at its maximum every time and stood there half empty,
                        // eating screen width for nothing.
                        .width(IntrinsicSize.Max)
                        .graphicsLayer {
                            val p = anim.value
                            alpha = p
                            // Grow out of the control that opened it: the + / ⋮ on the right
                            // edge, or the left edge of a folder tab / dropdown field.
                            scaleX = 0.94f + 0.06f * p
                            scaleY = 0.94f + 0.06f * p
                            transformOrigin = TransformOrigin(if (anchorX == null) 1f else 0f, 0f)
                        }
                        // Square corners, panel painted in the app background, no outline —
                        // only a light drop shadow lifts it off the content underneath.
                        .shadow(6.dp, RectangleShape)
                        .background(SmuglyBg)
                        .padding(vertical = 4.dp),
                    content = content
                )
            }
        ) { measurables, constraints ->
            val preferW = panelWidthPx?.coerceIn(minW, constraints.maxWidth) ?: 0
            val maxW = if (preferW > 0) preferW else defaultMaxW.coerceAtMost(constraints.maxWidth)
            val minMeasureW = if (preferW > 0) preferW else minW.coerceAtMost(maxW)
            val panel = measurables.first().measure(
                constraints.copy(
                    minWidth = minMeasureW.coerceAtMost(constraints.maxWidth),
                    maxWidth = maxW.coerceAtLeast(minMeasureW).coerceAtMost(constraints.maxWidth),
                    minHeight = 0
                )
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
        color = SmuglyTextPrimary,
        fontSize = 16.sp,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .background(if (hovered) SmuglyCardSoft else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            // Rows are ~52dp tall: these are the app's primary actions on a phone, and at
            // the old 14sp/12dp they read as a desktop context menu shrunk down. The right
            // side is deliberately tighter — the panel is sized by its longest label, so
            // padding there is width the menu takes off the screen for nothing.
            .padding(start = 18.dp, end = 10.dp, top = 16.dp, bottom = 16.dp)
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
    onAddAnchor: (Int) -> Unit = {},
    /** Confirm action (✓) — the editor's save, in the bar instead of a button at the bottom. */
    onConfirm: (() -> Unit)? = null,
    /** Destructive action (🗑). Sits in the outer corner, where + is on the list screen. */
    onDeleteAction: (() -> Unit)? = null
) {
    // Full-bleed bar: menu / + sit against the physical window edges; 56dp targets.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TopBarIconSize)
            .background(SmuglyBg),
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
                        tint = SmuglyTextPrimary,
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
                        tint = SmuglyTextPrimary,
                        modifier = Modifier.size(TopBarIconGlyph)
                    )
                }
            }
            else -> Spacer(Modifier.width(8.dp))
        }
        Text(
            text = title,
            color = SmuglyTextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
        )
        if (onDeleteAction != null) {
            Box(
                Modifier
                    .size(TopBarIconSize)
                    .handClickable(onClick = onDeleteAction),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Delete,
                    null,
                    // Muted rather than accent: it sits in the corner the thumb lands on, and the
                    // confirmation dialog behind it is what actually protects the profile.
                    tint = SmuglyTextSecondary,
                    modifier = Modifier.size(TopBarIconGlyph)
                )
            }
        }
        if (onConfirm != null) {
            Box(
                Modifier
                    .size(TopBarIconSize)
                    .handClickable(onClick = onConfirm),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Check,
                    null,
                    tint = SmuglyTextPrimary,
                    modifier = Modifier.size(TopBarIconGlyph)
                )
            }
        }
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
                    tint = SmuglyTextPrimary,
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
        color = SmuglyTextPrimary,
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
    )
}

@Composable
fun HintText(text: String) {
    Text(
        text = text,
        color = SmuglyTextMuted,
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
            color = SmuglyTextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        content()
    }
}

@Composable
fun SmuglyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    singleLine: Boolean = true,
    password: Boolean = false,
    number: Boolean = false,
    minLines: Int = 1,
    /**
     * Cap for multi-line fields. Default is unlimited growth — fine for short notes, but a
     * multi-KB Xray JSON would make the parent `verticalScroll` taller than the window and
     * Compose's focus bring-into-view then jerks the page once on first focus.
     */
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    monospace: Boolean = false,
    /**
     * When the field has a fixed height (e.g. Xray JSON box), paint the chrome edge-to-edge.
     * Must stay false for single-line fields — fillMaxHeight in a tall Column blows them up.
     */
    fillContainer: Boolean = false,
    /** Extra display transform (e.g. [JsonSyntaxHighlightTransformation]); ignored when [password]. */
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    val shape = RoundedCornerShape(10.dp)
    // External modifier (e.g. fixed height) must sit on BasicTextField itself — not only on
    // decorationBox — otherwise layout constraints are ignored.
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines.coerceAtLeast(1),
        maxLines = if (singleLine) 1 else maxLines.coerceAtLeast(1),
        visualTransformation = when {
            password -> PasswordVisualTransformation()
            else -> visualTransformation
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                number -> KeyboardType.Number
                password -> KeyboardType.Password
                else -> KeyboardType.Text
            }
        ),
        textStyle = TextStyle(
            color = SmuglyTextPrimary,
            fontSize = if (monospace) 12.sp else 15.sp,
            // Inherit Roboto from theme; keep monospaced only for logs/JSON.
            fontFamily = if (monospace) FontFamily.Monospace else null
        ),
        cursorBrush = SolidColor(SmuglyAccent),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (fillContainer) Modifier.fillMaxHeight() else Modifier)
                    .clip(shape)
                    .background(SmuglyInput)
                    .border(1.dp, SmuglyStroke, shape)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart
            ) {
                if (value.isEmpty() && hint.isNotEmpty()) {
                    Text(hint, color = SmuglyTextMuted, fontSize = 15.sp)
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
    // One pill that slides between the options, rather than one pill per option fading in and
    // out: the highlight is a single object travelling to where you tapped. Options are equal
    // width, so the pill's own width is the whole travel arithmetic — translationX inside
    // graphicsLayer measures in the layer's own size.
    val slide by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = tween(PillSwitchMs, easing = FastOutSlowInEasing),
        label = "pillSlide"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SmuglyCard)
            .padding(3.dp)
    ) {
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier
                    .fillMaxWidth(1f / options.size.coerceAtLeast(1))
                    .fillMaxHeight()
                    .graphicsLayer { translationX = slide * size.width }
                    .clip(RoundedCornerShape(10.dp))
                    .background(SmuglyAccent)
            )
        }
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, label ->
                // Only the text cross-fades — it has to, since the pill arrives underneath it.
                val textColor by animateColorAsState(
                    targetValue = if (index == selectedIndex) {
                        SmuglyButtonTextPrimary
                    } else {
                        SmuglyTextSecondary
                    },
                    animationSpec = tween(PillSwitchMs, easing = FastOutSlowInEasing),
                    label = "pillText"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .handClickable { onSelected(index) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = textColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Closed field for a dropdown. The open list is drawn with [MenuLayer] (same overlay as the
 * + menu) so it does **not** push content below — host owns [open] / anchors / panel.
 */
@Composable
fun DropdownField(
    label: String,
    open: Boolean,
    onClick: () -> Unit,
    /** Bottom-left of the field in root coordinates + width, for [MenuLayer] placement. */
    onAnchor: (x: Int, y: Int, width: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(10.dp)
    // Where the card sits, so the finger's position inside it can be turned into a screen one.
    // The list needs that: judging auto-scroll by the CARD means one picked up near an edge
    // scrolls the instant it is touched, and a finger carried off the list stops steering at all.
    val cardRootY = remember { floatArrayOf(0f) }
    Row(
        modifier = modifier
            .onGloballyPositioned { cardRootY[0] = it.positionInRoot().y }
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                val pos = coords.positionInRoot()
                onAnchor(
                    pos.x.toInt(),
                    (pos.y + coords.size.height).toInt(),
                    coords.size.width
                )
            }
            .clip(shape)
            .background(SmuglyInput)
            .border(1.dp, if (open) SmuglyAccent else SmuglyStroke, shape)
            .handClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = SmuglyTextPrimary,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = SmuglyTextSecondary,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer { rotationZ = if (open) 180f else 0f }
        )
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SmuglyAccent)
            .handClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = SmuglyButtonTextPrimary, fontWeight = FontWeight.Normal, fontSize = 15.sp)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, SmuglyStroke, RoundedCornerShape(10.dp))
            .handClickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = SmuglyTextPrimary,
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
        color = SmuglyAccent,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .handClickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp)
    )
}

@Composable
fun SmuglyCheckbox(checked: Boolean, label: String, onCheckedChange: (Boolean) -> Unit) {
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
                checkedColor = SmuglyAccent,
                uncheckedColor = SmuglyTextMuted,
                checkmarkColor = SmuglyButtonTextPrimary
            )
        )
        Text(
            label,
            color = SmuglyTextSecondary,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 2.dp)
        )
    }
}

/** Hold-shrink while reordering — matches original PROFILE_DRAG_SCALE = 0.96f. */
private const val ProfileDragScale = 0.96f

/** How long the card that just lost selection takes to go dark. Selecting itself is instant. */
private const val SelectFadeOutMs = 350

/** How long the pill takes to slide to the option you tapped. */
private const val PillSwitchMs = 250

/**
 * Latency chip next to a profile name. Deliberately three distinct states — an unreachable
 * server and a slow one are different answers, and one number for both would hide the useful one.
 */
@Composable
private fun LatencyBadge(latency: app.smugly.ui.LatencyUi) {
    // The palette is monochrome plus one red, so red is spent only on "this does not work":
    // a number that is merely large still reads fine as plain text.
    val (text, color) = when {
        latency.measuring -> "…" to SmuglyTextMuted
        latency.failed -> "—" to SmuglyAccent
        latency.ms != null -> "${latency.ms} ms" to
            if (latency.ms < 800) SmuglyTextSecondary else SmuglyTextMuted
        else -> return
    }
    Text(
        text = text,
        color = color,
        fontSize = 12.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(SmuglyCardSoft)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

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
    /** [pointerRootY] is where the finger actually is, in root coordinates. */
    onLongPressDragStart: (pointerRootY: Float) -> Unit = {},
    onLongPressDrag: (dy: Float, pointerRootY: Float) -> Unit = { _, _ -> },
    onLongPressDragEnd: () -> Unit = {},
    onLongPressDragCancel: () -> Unit = {},
    enableReorder: Boolean = true,
    /** Measured round trip to this profile's own server, or null when never measured. */
    latency: app.smugly.ui.LatencyUi? = null,
    /**
     * Card fill. Inside a category panel the cards sit on [SmuglyCard] already, so they are drawn
     * a shade lighter — otherwise the group would be one flat slab with no rows in it.
     */
    containerColor: Color = SmuglyCard,
    /** Applied to the card's root — the list uses it to animate the row into its new slot. */
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    val haptics = LocalHapticFeedback.current
    // A lazy list disposes items that leave the viewport, and disposing the one under the finger
    // cancels the drag with it — which is why a profile could never be carried further than about
    // a screen, however long the folder was. Pinning keeps it composed while it is being held; the
    // card itself stays on screen the whole time, only its slot travels away.
    // `pointerInput(Unit)` keeps the closure it started with, and these callbacks are rebuilt on
    // every recomposition — they capture the card's current index, which now changes DURING a
    // drag as the list reorders under it. Reading them through holders keeps the gesture talking
    // to the current ones instead of the ones from the frame it was born in.
    val dragStartNow by rememberUpdatedState(onLongPressDragStart)
    val dragNow by rememberUpdatedState(onLongPressDrag)
    val dragEndNow by rememberUpdatedState(onLongPressDragEnd)
    val dragCancelNow by rememberUpdatedState(onLongPressDragCancel)
    val pinnable = LocalPinnableContainer.current
    var pinned by remember { mutableStateOf<PinnableContainer.PinnedHandle?>(null) }
    fun unpin() {
        pinned?.release()
        pinned = null
    }
    DisposableEffect(Unit) { onDispose { pinned?.release() } }
    // Asymmetric on purpose: the card the user just tapped lights up on the same frame as the
    // tap, and only the one losing selection fades, over 150ms. Animating both directions made
    // the highlight look like it was sliding between rows instead of following the finger.
    val selectAnim: AnimationSpec<Color> =
        if (selected) snap() else tween(SelectFadeOutMs, easing = FastOutSlowInEasing)
    val selectFloatAnim: AnimationSpec<Float> =
        if (selected) snap() else tween(SelectFadeOutMs, easing = FastOutSlowInEasing)
    val selectDpAnim: AnimationSpec<androidx.compose.ui.unit.Dp> =
        if (selected) snap() else tween(SelectFadeOutMs, easing = FastOutSlowInEasing)
    val borderColor by animateColorAsState(
        targetValue = if (selected) SmuglyAccent else Color.Transparent,
        animationSpec = selectAnim,
        label = "profileBorder"
    )
    val markerFill by animateColorAsState(
        targetValue = if (selected) SmuglyAccent else Color.Transparent,
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

    // Layout coords of the *untranslated* row. Must sit outside graphicsLayer: the list scrolls
    // and reorders under the finger during a drag, and any root Y built from layout origin + local
    // offset that also includes translationY feeds the card's own motion back into the next frame.
    // localToRoot(pointer) is the true finger position even when this slot jumps — unlike summing
    // drag deltas, which pick up every layout shift as a fake finger move and break auto-scroll.
    var rowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .onGloballyPositioned { rowCoords = it }
            // Gesture outside graphicsLayer so scale/translation never warp deltas or hit-testing.
            // Once claimed, the pointer keeps delivering events past the original bounds.
            .then(
                if (enableReorder) {
                    Modifier.pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                // One tick the moment the card is picked up, so reordering is
                                // confirmed by feel rather than by watching the card move.
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                pinned = pinnable?.pin()
                                val rootY = rowCoords
                                    ?.takeIf { it.isAttached }
                                    ?.localToRoot(offset)
                                    ?.y
                                    ?: offset.y
                                dragStartNow(rootY)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val coords = rowCoords?.takeIf { it.isAttached }
                                // Absolute root Y only. dragAmount is still passed for callers that
                                // want a delta, but list reorder must not accumulate it: when the
                                // LazyColumn scrolls/swaps this node, dragAmount includes the
                                // layout jump as if the finger moved.
                                if (coords != null) {
                                    dragNow(dragAmount.y, coords.localToRoot(change.position).y)
                                }
                            },
                            onDragEnd = { unpin(); dragEndNow() },
                            onDragCancel = { unpin(); dragCancelNow() }
                        )
                    }
                } else Modifier
            )
            .zIndex(if (isDragging) 2f else 0f)
            .graphicsLayer {
                this.translationY = translationY
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                shadowElevation = elev
            }
            .clip(shape)
            .background(containerColor)
            // Always draw border; alpha/color animates so selection "moves" smoothly.
            .border(1.dp, borderColor, shape)
            .then(
                if (enableReorder) {
                    // Open hand while hovering a reorderable card; closed/move while dragging.
                    Modifier.pointerHoverIcon(
                        if (isDragging) PointerIconGrabbing else PointerIconGrab,
                        overrideDescendants = isDragging
                    )
                } else Modifier
            )
            // The card is a selectable surface, not a button — arrow cursor unless reorder is on.
            // Delete and ⋮ still use handClickable and override the cursor themselves.
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
                        color = SmuglyAccent.copy(alpha = markerRing),
                        shape = CircleShape
                    )
            )
        }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            // Flag emojis in profile titles (🇪🇸 Испания) become letter-pairs on Windows;
            // ProfileNameText draws a real flag image on desktop and keeps emoji elsewhere.
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileNameText(
                    name = name,
                    color = SmuglyTextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (latency != null) {
                    Spacer(Modifier.width(8.dp))
                    LatencyBadge(latency)
                }
            }
            // Xray and empty subtitles: no second line (avoids "{..." junk).
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = SmuglyTextSecondary,
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
                    tint = SmuglyTextSecondary,
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
                tint = SmuglyTextSecondary,
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
    // Taller than a plain 56dp strip, matching the original Android View UI — the status and
    // traffic lines were cramped against the edges at that height.
    val barHeight = 72.dp
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
                .background(SmuglyCard)
                .padding(start = 16.dp, end = 100.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    status,
                    color = SmuglyAccent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )
                Text(
                    traffic,
                    color = SmuglyTextSecondary,
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
                .background(SmuglyAccent)
                .handClickable(enabled = !loading, onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = SmuglyButtonTextPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    text = if (running) "■" else "▶",
                    color = SmuglyButtonTextPrimary,
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
            .background(SmuglyCard)
            .padding(top = 20.dp, bottom = 16.dp, start = 12.dp, end = 12.dp)
    ) {
        items.forEach { item ->
            val isSelected = item == selected
            val interaction = remember(item) { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            val bg = when {
                isSelected -> SmuglyAccent.copy(alpha = 0.22f)
                hovered -> SmuglyCardSoft
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
                    color = if (isSelected) SmuglyAccent else SmuglyTextPrimary,
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
                "Smugly",
                color = SmuglyTextPrimary,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp
            )
            Text(
                versionLabel,
                color = SmuglyTextMuted,
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
        HOME -> app.smugly.t(app.smugly.S.HOME)
        DIAGNOSTICS -> app.smugly.t(app.smugly.S.DIAGNOSTICS)
        SETTINGS -> app.smugly.t(app.smugly.S.SETTINGS)
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
                        // Same skin as MenuLayer: app background, square corners, no outline,
                        // only a drop shadow lifting it off the content. A rounded, lighter card
                        // read as a different design language from the rest of the app.
                        .shadow(12.dp, RectangleShape)
                        .background(SmuglyBg)
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
 * Blocking spinner, centred over a dimmed screen.
 *
 * For work that has nothing on screen to attach a spinner to: importing a subscription is a network
 * round trip that happens *before* its folder tab exists, so there is no card to spin an icon on and
 * the app would otherwise look frozen until the toast lands. Deliberately not dismissible — the
 * request is already in flight and there is nothing to cancel.
 */
@Composable
fun LoadingOverlay(visible: Boolean, message: String) {
    AnimatedModalCard(
        visible = visible,
        onDismissRequest = {},
        modifier = Modifier.widthIn(max = 260.dp)
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(34.dp),
                color = SmuglyAccent,
                strokeWidth = 3.dp
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = message,
                color = SmuglyTextPrimary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
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
        Text(title, color = SmuglyTextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Normal)
        Text(
            message,
            color = SmuglyTextSecondary,
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
                color = SmuglyTextSecondary,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .handClickable(onClick = onDismiss)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                confirmLabel,
                color = SmuglyAccent,
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
        Text(title, color = SmuglyTextPrimary, fontSize = 17.sp)
        Box(
            Modifier
                .fillMaxWidth()
                .height(280.dp)
                .padding(top = 12.dp, bottom = 14.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SmuglyInput)
                .padding(10.dp)
        ) {
            val scroll = rememberScrollState()
            Text(
                text = body,
                color = SmuglyTextSecondary,
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
                color = SmuglyTextSecondary,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .handClickable(onClick = onDismiss)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                copyLabel,
                color = SmuglyAccent,
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
                color = SmuglyButtonTextPrimary,
                fontSize = 13.sp,
                modifier = Modifier
                    .padding(bottom = 88.dp, start = 16.dp, end = 16.dp)
                    .shadow(8.dp, RoundedCornerShape(10.dp))
                    .clip(RoundedCornerShape(10.dp))
                    .background(SmuglyCard.copy(alpha = 0.96f))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }
}
