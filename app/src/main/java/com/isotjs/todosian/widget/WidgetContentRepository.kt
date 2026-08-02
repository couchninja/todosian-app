package com.isotjs.todosian.widget

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process widget UI snapshot for the active Glance session.
 * ActionCallbacks [publish] here so the live composition can update without
 * calling [androidx.glance.appwidget.GlanceAppWidget.update] mid-callback.
 */
internal object WidgetContentRepository {
    private val _content = MutableStateFlow<WidgetState?>(null)
    val content: StateFlow<WidgetState?> = _content.asStateFlow()

    fun publish(state: WidgetState) {
        _content.value = state
    }
}
