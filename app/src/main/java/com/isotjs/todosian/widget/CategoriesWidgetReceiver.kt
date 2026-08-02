package com.isotjs.todosian.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.isotjs.todosian.TodosianApplication

class CategoriesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CategoriesWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        startWatching(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        startWatching(context)
    }

    override fun onDisabled(context: Context) {
        CategoriesWidgetUpdater.stopObserving()
        super.onDisabled(context)
    }

    private fun startWatching(context: Context) {
        val app = context.applicationContext as? TodosianApplication ?: return
        CategoriesWidgetUpdater.startObserving(
            context,
            app.fileRepository,
            app.preferencesManager,
        )
    }
}
