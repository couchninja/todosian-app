package com.isotjs.todosian.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.isotjs.todosian.TodosianApplication
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Advances the widget to the next Markdown list when the title is tapped. */
class CycleCategoryAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        mutex.withLock {
            val appContext = context.applicationContext
            val app = appContext as? TodosianApplication ?: return
            val categories = app.fileRepository.getCategories().getOrNull().orEmpty()
            if (categories.size <= 1) return

            val next = TodoWidgetData.nextAfter(
                items = categories,
                selectedUri = app.preferencesManager.getWidgetCategoryUri(),
                uriOf = { it.uri.toString() },
            ) ?: return

            // No file write here; still suppress in case a poll/observer refresh races us.
            CategoriesWidgetUpdater.suppressExternalUpdates()
            app.preferencesManager.setWidgetCategoryUri(next.uri.toString())

            CategoriesWidgetContent.publishToWidget(
                context = appContext,
                glanceId = glanceId,
                selectedUriOverride = next.uri.toString(),
            )
            // Update after this callback returns to avoid canceling the live SessionWorker.
            CategoriesWidgetUpdater.scheduleUpdateAfterAction(appContext, glanceId)
        }
    }

    companion object {
        private val mutex = Mutex()
    }
}
