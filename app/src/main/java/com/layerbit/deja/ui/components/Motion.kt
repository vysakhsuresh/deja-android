package com.layerbit.deja.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.layerbit.deja.ui.theme.DejaColors

/**
 * The small amount of motion the app allows itself.
 *
 * Every animation here is tied to something that actually changed - a number going up as a scan
 * runs, a thumbnail arriving, a finger going down on a tile. None of it is decorative, because
 * decorative motion on a screen you open fifteen times a day stops being delightful by the third
 * day and starts being latency.
 */

/** Short enough to feel like a response rather than an animation. */
private const val PRESS_MS = 110

/**
 * A number that counts to its new value instead of snapping.
 *
 * Worth it specifically during a scan: the count climbing is the clearest signal that Deja is
 * working, and a figure that jumps from 412 to 530 when the screen recomposes reads as a glitch.
 */
@Composable
fun AnimatedCount(
    value: Int,
    color: Color = DejaColors.Text,
    fontSize: TextUnit = 14.sp,
    fontWeight: FontWeight = FontWeight.Normal,
    format: (Int) -> String = { it.toString() }
) {
    val animated by animateIntAsState(
        targetValue = value,
        animationSpec = tween(durationMillis = 520),
        label = "count"
    )
    Text(text = format(animated), color = color, fontSize = fontSize, fontWeight = fontWeight)
}

/**
 * Long-press, haptics and an optional press zoom in one modifier, so every tappable surface in
 * the app behaves the same way without each call site remembering an interaction source.
 *
 * The zoom is off by default, and that is a correctness point rather than a taste one. A
 * `graphicsLayer` in the middle of a modifier chain only transforms what comes *after* it, so on
 * a chain like `clip().background().tappable()` the label would shrink while the card behind it
 * stayed put - which reads as a rendering bug, not as feedback. Where the whole visible surface
 * sits inside the tappable node - a thumbnail filling a clipped tile - the transform covers
 * everything, and there a value just above 1 presses the image *into* its frame rather than
 * pulling it away from the edges. Everywhere else the ripple is the feedback.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.tappable(
    enabled: Boolean = true,
    pressScale: Float = 1f,
    hapticOnLongPress: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current

    val clickable = this.combinedClickable(
        interactionSource = interaction,
        indication = LocalIndication.current,
        enabled = enabled,
        onLongClick = onLongClick?.let {
            {
                if (hapticOnLongPress) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                it()
            }
        },
        onClick = onClick
    )
    // Both read unconditionally: a composable whose call count changes with an argument is a
    // trap, even where the argument happens to be constant at every call site today.
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressScale else 1f,
        animationSpec = tween(durationMillis = PRESS_MS),
        label = "press"
    )

    return if (pressScale == 1f) {
        clickable
    } else {
        clickable.graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    }
}

/**
 * The placeholder a thumbnail sits in until its bitmap is decoded.
 *
 * A gentle pulse rather than the usual diagonal sweep: a grid of nine tiles all sweeping at once
 * is visual noise, and the only job here is to say "something is coming" without competing with
 * the images that then arrive.
 */
@Composable
fun ShimmerBox(modifier: Modifier = Modifier, shape: RoundedCornerShape = RoundedCornerShape(0.dp)) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(DejaColors.Surface)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .alpha(alpha)
                .background(DejaColors.Border)
        )
    }
}
