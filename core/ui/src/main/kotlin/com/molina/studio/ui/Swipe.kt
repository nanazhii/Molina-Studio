package com.molina.studio.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import kotlin.math.abs

/** Arah geser yang memicu [onHorizontalSwipe]. */
enum class SwipeDirection { Left, Right, Any }

/**
 * Pemicu geser horizontal sekali-tembak: setelah akumulasi geseran melewati [threshold]
 * pada [direction], [onTrigger] dipanggil dan akumulasi di-reset (gestur terus berlanjut).
 *
 * - Ambang dalam dp (konsisten di semua kepadatan layar, tidak lagi px mentah).
 * - [onTrigger] selalu versi terbaru (rememberUpdatedState), tanpa lambda basi.
 * - Satu-satunya tempat `pointerInput` + `detectHorizontalDragGestures` untuk gestur ini;
 *   Molina Player/Cinema cukup memakai modifier ini, bukan menulis ulang gestur.
 *   (Import yang benar: androidx.compose.ui.input.pointer.pointerInput)
 */
@Composable
fun Modifier.onHorizontalSwipe(
    threshold: Dp,
    direction: SwipeDirection = SwipeDirection.Any,
    onTrigger: () -> Unit
): Modifier {
    val latest by rememberUpdatedState(onTrigger)
    return pointerInput(threshold, direction) {
        val limit = threshold.toPx()
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onHorizontalDrag = { change, dx ->
                total += dx
                val hit = when (direction) {
                    SwipeDirection.Left -> total < -limit
                    SwipeDirection.Right -> total > limit
                    SwipeDirection.Any -> abs(total) > limit
                }
                if (hit) {
                    change.consume()
                    total = 0f
                    latest()
                }
            }
        )
    }
}
