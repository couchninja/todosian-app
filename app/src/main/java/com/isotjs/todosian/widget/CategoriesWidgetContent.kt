package com.isotjs.todosian.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.state.updateAppWidgetState
import com.isotjs.todosian.MainActivity
import com.isotjs.todosian.R
import com.isotjs.todosian.TodosianApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Snapshot of widget UI stored in Glance [PreferencesGlanceStateDefinition] state.
 * Glance only reliably recomposes ActionCallback / update() from [currentState], not from
 * process-local StateFlows or SharedPreferences side effects.
 */
internal object CategoriesWidgetContent {
    val KindKey = stringPreferencesKey("kind")
    val TitleKey = stringPreferencesKey("title")
    val MessageKey = stringPreferencesKey("message")
    val CategoryUriKey = stringPreferencesKey("category_uri")
    val AddContentDescriptionKey = stringPreferencesKey("add_cd")
    val CanCycleKey = booleanPreferencesKey("can_cycle")
    val EmptyMessageKey = stringPreferencesKey("empty_message")
    val MoreMessageKey = stringPreferencesKey("more_message")
    val ActiveTodosKey = stringPreferencesKey("active_todos_json")

    private const val KIND_READY = "ready"
    private const val KIND_MESSAGE = "message"

    suspend fun load(
        context: Context,
        selectedUriOverride: String? = null,
    ): WidgetState = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val addContentDescription = appContext.getString(R.string.cd_add_todo)
        val fallbackTitle = appContext.getString(R.string.widget_categories_name)
        val app = appContext as? TodosianApplication
            ?: return@withContext WidgetState.Message(
                title = fallbackTitle,
                message = appContext.getString(R.string.widget_todo_error),
                addContentDescription = addContentDescription,
                categoryUri = null,
                canCycleLists = false,
            )

        val prefs = app.preferencesManager

        if (app.fileRepository.getFolderUri() == null) {
            return@withContext WidgetState.Message(
                title = fallbackTitle,
                message = appContext.getString(R.string.widget_todo_no_folder),
                addContentDescription = addContentDescription,
                categoryUri = null,
                canCycleLists = false,
            )
        }

        val categories = app.fileRepository.getCategories().getOrElse {
            return@withContext WidgetState.Message(
                title = fallbackTitle,
                message = appContext.getString(R.string.widget_todo_error),
                addContentDescription = addContentDescription,
                categoryUri = null,
                canCycleLists = false,
            )
        }

        val selectedUri = selectedUriOverride ?: prefs.getWidgetCategoryUri()
        val category = TodoWidgetData.resolveSelectedCategory(
            categories = categories,
            selectedUri = selectedUri,
        )
        if (category == null) {
            return@withContext WidgetState.Message(
                title = fallbackTitle,
                message = appContext.getString(R.string.widget_todo_missing),
                addContentDescription = addContentDescription,
                categoryUri = null,
                canCycleLists = false,
            )
        }

        if (prefs.getWidgetCategoryUri() != category.uri.toString()) {
            prefs.setWidgetCategoryUri(category.uri.toString())
        }

        val canCycleLists = categories.size > 1
        val lines = app.fileRepository.readLines(category.uri).getOrElse {
            return@withContext WidgetState.Message(
                title = category.displayName,
                message = appContext.getString(R.string.widget_todo_error),
                addContentDescription = addContentDescription,
                categoryUri = category.uri.toString(),
                canCycleLists = canCycleLists,
            )
        }

        val active = TodoWidgetData.itemsFromLines(
            lines = lines,
            untitledLabel = appContext.getString(R.string.widget_todo_untitled),
            todoSort = app.appSettingsRepository.settings.first().todoSort,
            activeOnly = true,
        )
        val visible = active.take(TodoWidgetConstants.MAX_SCROLLABLE_TODOS)
        val hiddenCount = active.size - visible.size

        WidgetState.Ready(
            title = category.displayName,
            categoryUri = category.uri.toString(),
            activeTodos = visible,
            moreMessage = if (hiddenCount > 0) {
                appContext.getString(R.string.widget_todo_more, hiddenCount)
            } else {
                null
            },
            emptyMessage = appContext.getString(R.string.widget_todo_empty),
            addContentDescription = addContentDescription,
            canCycleLists = canCycleLists,
        )
    }

    /** Load, publish to the in-process snapshot, and write Glance prefs for [glanceId]. */
    suspend fun publishToWidget(
        context: Context,
        glanceId: GlanceId,
        selectedUriOverride: String? = null,
    ) {
        val appContext = context.applicationContext
        val state = load(appContext, selectedUriOverride)
        WidgetContentRepository.publish(state)
        updateAppWidgetState(appContext, glanceId) { prefs ->
            write(prefs, state)
        }
    }

    fun write(prefs: MutablePreferences, state: WidgetState) {
        prefs[TitleKey] = state.title
        prefs[AddContentDescriptionKey] = state.addContentDescription
        prefs[CanCycleKey] = state.canCycleLists
        when (state) {
            is WidgetState.Message -> {
                prefs[KindKey] = KIND_MESSAGE
                prefs[MessageKey] = state.message
                if (state.categoryUri.isNullOrBlank()) {
                    prefs.remove(CategoryUriKey)
                } else {
                    prefs[CategoryUriKey] = state.categoryUri
                }
                prefs.remove(ActiveTodosKey)
                prefs.remove(EmptyMessageKey)
                prefs.remove(MoreMessageKey)
            }
            is WidgetState.Ready -> {
                prefs[KindKey] = KIND_READY
                prefs[CategoryUriKey] = state.categoryUri
                prefs[EmptyMessageKey] = state.emptyMessage
                prefs[ActiveTodosKey] = encodeTodos(state.activeTodos)
                if (state.moreMessage.isNullOrBlank()) {
                    prefs.remove(MoreMessageKey)
                } else {
                    prefs[MoreMessageKey] = state.moreMessage
                }
                prefs.remove(MessageKey)
            }
        }
    }

    fun read(prefs: Preferences): WidgetState? {
        val kind = prefs[KindKey] ?: return null
        val title = prefs[TitleKey] ?: return null
        val addCd = prefs[AddContentDescriptionKey] ?: return null
        val canCycle = prefs[CanCycleKey] ?: false
        return when (kind) {
            KIND_MESSAGE -> WidgetState.Message(
                title = title,
                message = prefs[MessageKey].orEmpty(),
                addContentDescription = addCd,
                categoryUri = prefs[CategoryUriKey],
                canCycleLists = canCycle,
            )
            KIND_READY -> {
                val categoryUri = prefs[CategoryUriKey] ?: return null
                WidgetState.Ready(
                    title = title,
                    categoryUri = categoryUri,
                    activeTodos = decodeTodos(prefs[ActiveTodosKey]),
                    moreMessage = prefs[MoreMessageKey],
                    emptyMessage = prefs[EmptyMessageKey].orEmpty(),
                    addContentDescription = addCd,
                    canCycleLists = canCycle,
                )
            }
            else -> null
        }
    }

    fun openAppIntent(context: Context): Intent {
        return Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    }

    fun openCategoryIntent(context: Context, categoryUri: Uri): Intent {
        return openAppIntent(context).apply {
            putExtra(TodoWidgetConstants.EXTRA_OPEN_CATEGORY_URI, categoryUri.toString())
        }
    }

    fun openCategoryIntent(context: Context, categoryUri: String?): Intent {
        if (categoryUri.isNullOrBlank()) return openAppIntent(context)
        return openCategoryIntent(context, categoryUri.toUri())
    }

    /**
     * Edit deep link for a widget todo. [Intent.setData] keeps each row's
     * PendingIntent distinct inside the LazyColumn ListView.
     */
    fun openEditTodoIntent(
        context: Context,
        categoryUri: String,
        lineIndex: Int,
    ): Intent {
        return openAppIntent(context).apply {
            data = "todosian://widget/edit".toUri()
                .buildUpon()
                .appendQueryParameter("uri", categoryUri)
                .appendQueryParameter("line", lineIndex.toString())
                .build()
            putExtra(TodoWidgetConstants.EXTRA_OPEN_CATEGORY_URI, categoryUri)
            putExtra(TodoWidgetConstants.EXTRA_OPEN_EDIT_LINE_INDEX, lineIndex)
            putExtra(
                TodoWidgetConstants.EXTRA_EDIT_REQUEST_ID,
                System.currentTimeMillis() * 1_000L + lineIndex,
            )
        }
    }

    private fun encodeTodos(items: List<TodoWidgetData.Item>): String {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("lineIndex", item.lineIndex)
                    .put("text", item.text)
                    .put("isDone", item.isDone)
                    .put("indentLevel", item.indentLevel),
            )
        }
        return array.toString()
    }

    private fun decodeTodos(raw: String?): List<TodoWidgetData.Item> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        TodoWidgetData.Item(
                            lineIndex = obj.getInt("lineIndex"),
                            text = obj.getString("text"),
                            isDone = obj.getBoolean("isDone"),
                            indentLevel = obj.optInt("indentLevel", 0),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
