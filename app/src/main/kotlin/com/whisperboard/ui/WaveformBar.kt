package com.whisperboard.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min

private const val BAR_COUNT = 24

@Composable
fun WaveformBar(
    waveformData: FloatArray,
    isRecording: Boolean,
    modifier: Modifier = Modifier,
) {
    val barColor = MaterialTheme.colorScheme.primary

    // Downsample waveform data to BAR_COUNT amplitude values
    val amplitudes = remember(waveformData) {
        if (waveformData.isEmpty()) {
            FloatArray(BAR_COUNT) { 0f }
        } else {
            FloatArray(BAR_COUNT) { i ->
                val start = i * waveformData.size / BAR_COUNT
                val end = min((i + 1) * waveformData.size / BAR_COUNT, waveformData.size)
                if (start < end) {
                    var sum = 0f
                    for (j in start until end) {
                        sum += abs(waveformData[j])
                    }
                    (sum / (end - start)).coerceIn(0f, 1f)
                } else {
                    0f
                }
            }
        }
    }

    WaveformCanvas(
        amplitudes = amplitudes,
        isRecording = isRecording,
        barColor = barColor,
        modifier = modifier
            .fillMaxWidth()
            .height(if (isRecording) 40.dp else 0.dp),
    )
}

@Composable
private fun WaveformCanvas(
    amplitudes: FloatArray,
    isRecording: Boolean,
    barColor: Color,
    modifier: Modifier = Modifier,
) {
    // Animate each bar independently for smooth transitions
    val animatedAmplitudes = amplitudes.map { target ->
        animateFloatAsState(
            targetValue = if (isRecording) target else 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
            label = "bar-amplitude",
        ).value
    }

    Canvas(modifier = modifier) {
        if (size.height <= 0f || size.width <= 0f) return@Canvas

        val barWidth = size.width / (BAR_COUNT * 1.5f)
        val gap = barWidth * 0.5f
        val totalBarWidth = barWidth + gap
        val startX = (size.width - totalBarWidth * BAR_COUNT + gap) / 2f
        val minBarHeight = 4f
        val maxBarHeight = size.height * 0.9f
        val cornerRadius = barWidth / 2f

        animatedAmplitudes.forEachIndexed { i, amplitude ->
            val barHeight = minBarHeight + amplitude * (maxBarHeight - minBarHeight)
            val x = startX + i * totalBarWidth
            val y = (size.height - barHeight) / 2f

            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(cornerRadius, cornerRadius),
            )
        }
    }
}
