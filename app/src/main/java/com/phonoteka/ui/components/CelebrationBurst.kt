package com.phonoteka.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlin.math.cos
import kotlin.math.sin

/** Non-interactive, short firework shown only after a completed operation. */
@Composable
fun CelebrationBurst(trigger: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val animatorScale = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
            .getOrDefault(1f).coerceAtLeast(0f)
    }
    val progress = remember { Animatable(1f) }
    var lastTrigger by rememberSaveable { mutableIntStateOf(trigger) }
    LaunchedEffect(trigger, animatorScale) {
        if (trigger > lastTrigger && animatorScale > 0f) {
            lastTrigger = trigger
            progress.snapTo(0f)
            progress.animateTo(1f, tween((1250 * animatorScale).toInt().coerceAtLeast(1)))
        } else lastTrigger = trigger
    }
    if (progress.value >= 1f) return
    Canvas(modifier.fillMaxSize()) {
        val palette = listOf(Color(0xFFFFC857), Color(0xFFFF6B8A), Color(0xFF75D6FF), Color(0xFFBFA4FF))
        val centers = listOf(Offset(size.width * .25f, size.height * .4f),
            Offset(size.width * .73f, size.height * .29f), Offset(size.width * .51f, size.height * .64f))
        centers.forEachIndexed { burst, center ->
            val t = ((progress.value - burst * .17f) / .65f).coerceIn(0f, 1f)
            if (t > 0f && t < 1f) {
                val fade = (1f - t) * (1f - t)
                repeat(24) { ray ->
                    val angle = ray * (Math.PI * 2 / 24) + burst * .31
                    val distance = size.minDimension * (.07f + .34f * t) * (if (ray % 3 == 0) 1f else .8f)
                    val point = Offset(center.x + cos(angle).toFloat() * distance,
                        center.y + sin(angle).toFloat() * distance)
                    val color = palette[(ray + burst) % palette.size].copy(alpha = fade)
                    drawCircle(color, radius = size.minDimension * .007f * (1f - t * .55f), center = point)
                }
            }
        }
    }
}
