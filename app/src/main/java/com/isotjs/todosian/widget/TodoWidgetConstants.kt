package com.isotjs.todosian.widget

object TodoWidgetConstants {
    const val EXTRA_OPEN_CATEGORY_URI = "com.isotjs.todosian.extra.OPEN_CATEGORY_URI"
    const val EXTRA_OPEN_ADD_TODO = "com.isotjs.todosian.extra.OPEN_ADD_TODO"
    const val EXTRA_ADD_REQUEST_ID = "com.isotjs.todosian.extra.ADD_REQUEST_ID"
    const val EXTRA_OPEN_EDIT_LINE_INDEX = "com.isotjs.todosian.extra.OPEN_EDIT_LINE_INDEX"
    const val EXTRA_EDIT_REQUEST_ID = "com.isotjs.todosian.extra.EDIT_REQUEST_ID"

    /**
     * Soft cap on todos stored/rendered in the widget list.
     * Glance maps LazyColumn to a RemoteViews ListView; uncapped interactive
     * rows inflate binder transactions (~700KB+) and launchers drop updates.
     */
    const val MAX_SCROLLABLE_TODOS = 18
}
