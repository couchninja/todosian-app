package com.isotjs.todosian.widget

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.ToggleableStateKey
import com.isotjs.todosian.TodosianApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

class ToggleTodoAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val appContext = context.applicationContext
        val lineIndex = parameters[LineIndexKey]
        val targetChecked = parameters[ToggleableStateKey]
        if (lineIndex == null || targetChecked == null) {
            Log.w(TAG, "Missing action params lineIndex=$lineIndex targetChecked=$targetChecked")
            return
        }

        val app = appContext as? TodosianApplication ?: return
        val categoryUri = resolveCategoryUri(parameters) ?: return

        // writeLines → requestUpdate; suppress so that refresh cannot cancel our update.
        CategoriesWidgetUpdater.suppressExternalUpdates()

        val lines = app.fileRepository.readLines(categoryUri).getOrElse {
            Log.w(TAG, "Failed to read category file", it)
            CategoriesWidgetContent.publishToWidget(appContext, glanceId, categoryUri.toString())
            CategoriesWidgetUpdater.scheduleUpdateAfterAction(appContext, glanceId)
            return
        }

        val enableTasksPlugin = app.appSettingsRepository.settings.first().enableTasksPluginSupport
        val updated = TodoWidgetData.applyToggle(
            lines = lines,
            lineIndex = lineIndex,
            targetChecked = targetChecked,
            enableTasksPlugin = enableTasksPlugin,
        )
        if (updated == null) {
            Log.w(TAG, "applyToggle returned null for lineIndex=$lineIndex")
            CategoriesWidgetContent.publishToWidget(appContext, glanceId, categoryUri.toString())
            CategoriesWidgetUpdater.scheduleUpdateAfterAction(appContext, glanceId)
            return
        }
        if (updated !== lines) {
            val writeResult = app.fileRepository.writeLines(categoryUri, updated)
            if (writeResult.isFailure) {
                Log.w(TAG, "Failed to write category file", writeResult.exceptionOrNull())
            }
            delay(SAF_SETTLE_MS)
        }

        CategoriesWidgetContent.publishToWidget(appContext, glanceId, categoryUri.toString())
        CategoriesWidgetUpdater.scheduleUpdateAfterAction(appContext, glanceId)
    }

    private fun resolveCategoryUri(
        parameters: ActionParameters,
    ): Uri? {
        val raw = parameters[CategoryUriKey] ?: return null
        return runCatching { raw.toUri() }.getOrNull()
    }

    companion object {
        private const val TAG = "ToggleTodoAction"
        private const val SAF_SETTLE_MS = 100L
        val LineIndexKey = ActionParameters.Key<Int>("line_index")
        val CategoryUriKey = ActionParameters.Key<String>("category_uri")
    }
}
