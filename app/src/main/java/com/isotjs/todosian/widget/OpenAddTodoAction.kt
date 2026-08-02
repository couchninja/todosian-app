package com.isotjs.todosian.widget

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback

/**
 * Starts [com.isotjs.todosian.MainActivity] with a fresh add-todo deep link on every tap.
 * Intent extras must not be baked into the widget layout (stale request ids
 * would prevent the sheet from opening again).
 */
class OpenAddTodoAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val categoryUri = parameters[CategoryUriKey]
        if (categoryUri.isNullOrBlank()) {
            Log.w(TAG, "Missing category URI for add-todo action")
            return
        }

        val intent = CategoriesWidgetContent.openCategoryIntent(context, categoryUri).apply {
            putExtra(TodoWidgetConstants.EXTRA_OPEN_ADD_TODO, true)
            putExtra(TodoWidgetConstants.EXTRA_ADD_REQUEST_ID, System.currentTimeMillis())
        }
        context.startActivity(intent)
    }

    companion object {
        private const val TAG = "OpenAddTodoAction"
        val CategoryUriKey = ActionParameters.Key<String>("category_uri")
    }
}
