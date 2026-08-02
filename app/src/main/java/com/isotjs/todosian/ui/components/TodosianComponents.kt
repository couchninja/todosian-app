package com.isotjs.todosian.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object TodosianDimens {
    val ScreenHorizontalPadding: Dp = 16.dp
    val CardPadding: Dp = 16.dp
    val ProgressHeight: Dp = 6.dp

    /** Per-level nest indent in todo lists (non-compact). */
    val TodoIndentStep: Dp = 20.dp
    val TodoIndentStepCompact: Dp = 12.dp
    val TodoIndentMax: Dp = 80.dp
    val TodoIndentMaxCompact: Dp = 48.dp

    fun todoIndentPadding(indentLevel: Int, compact: Boolean = false): Dp {
        val step = if (compact) TodoIndentStepCompact else TodoIndentStep
        val max = if (compact) TodoIndentMaxCompact else TodoIndentMax
        return (step * indentLevel).coerceAtMost(max)
    }
}

@Composable
fun TodosianSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
fun TodosianLinearProgress(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(TodosianDimens.ProgressHeight / 2)
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier
            .height(TodosianDimens.ProgressHeight)
            .clip(shape),
    )
}
