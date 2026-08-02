package com.isotjs.todosian.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.CheckboxDefaults
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.isotjs.todosian.R
import com.isotjs.todosian.TodosianApplication

private val WidgetTitleFontSize = 17.sp
private val WidgetSectionFontSize = 14.sp
private val WidgetBodyFontSize = 16.sp
private val WidgetCheckboxSize = 28.dp

/** Bottom inset so the last row clears the floating add button. */
private val WidgetListFabClearance = 64.dp

class CategoriesWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        (context.applicationContext as? TodosianApplication)?.let { app ->
            CategoriesWidgetUpdater.startObserving(
                context,
                app.fileRepository,
                app.preferencesManager,
            )
        }

        // Prefer ActionCallback / publishAndUpdateAll snapshot; only load on cold start.
        val seeded = WidgetContentRepository.content.value
        val initialState = if (seeded != null) {
            seeded
        } else {
            CategoriesWidgetContent.load(context).also { WidgetContentRepository.publish(it) }
        }
        updateAppWidgetState(context, id) { prefs ->
            CategoriesWidgetContent.write(prefs, initialState)
        }

        provideContent {
            val published by WidgetContentRepository.content.collectAsState()
            val glancePrefs = currentState<Preferences>()
            val glanceState = CategoriesWidgetContent.read(glancePrefs)
            val state = published ?: glanceState ?: initialState

            GlanceTheme {
                TodoListWidgetContent(state = state)
            }
        }
    }
}

internal sealed interface WidgetState {
    val title: String
    val addContentDescription: String
    val canCycleLists: Boolean

    data class Message(
        override val title: String,
        val message: String,
        override val addContentDescription: String,
        val categoryUri: String?,
        override val canCycleLists: Boolean,
    ) : WidgetState

    data class Ready(
        override val title: String,
        val categoryUri: String,
        val activeTodos: List<TodoWidgetData.Item>,
        val moreMessage: String? = null,
        val emptyMessage: String,
        override val addContentDescription: String,
        override val canCycleLists: Boolean,
    ) : WidgetState
}

@Composable
private fun TodoListWidgetContent(state: WidgetState) {
    val context = LocalContext.current
    val openAdd = when (state) {
        is WidgetState.Ready -> actionRunCallback<OpenAddTodoAction>(
            actionParametersOf(
                OpenAddTodoAction.CategoryUriKey to state.categoryUri,
            ),
        )
        is WidgetState.Message -> actionStartActivity(
            CategoriesWidgetContent.openCategoryIntent(context, state.categoryUri),
        )
    }
    val categoryUri = when (state) {
        is WidgetState.Ready -> state.categoryUri
        is WidgetState.Message -> state.categoryUri
    }
    val openCategory = actionStartActivity(
        CategoriesWidgetContent.openCategoryIntent(context, categoryUri),
    )
    val cycleList = actionRunCallback<CycleCategoryAction>()

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            CategoryTitleBar(
                title = state.title,
                canCycle = state.canCycleLists,
                onTitleClick = cycleList,
                onOpenCategory = openCategory,
            )

            when (state) {
                is WidgetState.Message -> {
                    Text(
                        text = state.message,
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = WidgetBodyFontSize,
                        ),
                        modifier = GlanceModifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .clickable(openCategory),
                    )
                }

                is WidgetState.Ready -> {
                    if (state.activeTodos.isEmpty()) {
                        Text(
                            text = state.emptyMessage,
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurfaceVariant,
                                fontSize = WidgetBodyFontSize,
                            ),
                            modifier = GlanceModifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .clickable(openCategory),
                        )
                    } else {
                        ScrollableTodoList(
                            state = state,
                            openCategory = openCategory,
                        )
                    }
                }
            }
        }

        Box(modifier = GlanceModifier.padding(end = 10.dp, bottom = 10.dp)) {
            CircleIconButton(
                imageProvider = ImageProvider(R.drawable.ic_widget_add),
                contentDescription = state.addContentDescription,
                onClick = openAdd,
            )
        }
    }
}

@Composable
private fun CategoryTitleBar(
    title: String,
    canCycle: Boolean,
    onTitleClick: Action,
    onOpenCategory: Action,
) {
    val titleAction = if (canCycle) onTitleClick else onOpenCategory
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .clickable(onOpenCategory)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (canCycle) "$title ▸" else title,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontWeight = FontWeight.Medium,
                fontSize = WidgetTitleFontSize,
            ),
            maxLines = 1,
            modifier = GlanceModifier
                .padding(end = 20.dp)
                .clickable(titleAction),
        )
        Spacer(modifier = GlanceModifier.defaultWeight())
        Spacer(modifier = GlanceModifier.width(8.dp))
        Image(
            provider = ImageProvider(R.drawable.ic_widget_app),
            contentDescription = null,
            modifier = GlanceModifier
                .size(40.dp)
                .clickable(onOpenCategory),
        )
    }
}

@Composable
private fun ScrollableTodoList(
    state: WidgetState.Ready,
    openCategory: Action,
) {
    val categoryKey = state.categoryUri

    // LazyColumn sizes to its items; remaining space is a normal clickable
    // region (ListView does not forward taps in empty gaps below rows).
    Column(modifier = GlanceModifier.fillMaxSize()) {
        LazyColumn(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp),
        ) {
            items(
                items = state.activeTodos,
                itemId = { TodoWidgetData.itemId(it, categoryKey) },
            ) { todo ->
                TodoRow(
                    todo = todo,
                    categoryUri = state.categoryUri,
                    openCategory = openCategory,
                )
            }
            state.moreMessage?.let { moreMessage ->
                item {
                    Text(
                        text = moreMessage,
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = WidgetSectionFontSize,
                        ),
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 8.dp)
                            .clickable(openCategory),
                    )
                }
            }
            item {
                Box(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .height(WidgetListFabClearance)
                        .clickable(openCategory),
                ) {}
            }
        }
        Box(
            modifier = GlanceModifier
                .defaultWeight()
                .fillMaxWidth()
                .clickable(openCategory),
        ) {}
    }
}

@Composable
private fun TodoRow(
    todo: TodoWidgetData.Item,
    categoryUri: String,
    openCategory: Action,
) {
    val context = LocalContext.current
    val indent = (todo.indentLevel * 12).dp
    val openEdit = actionStartActivity(
        CategoriesWidgetContent.openEditTodoIntent(
            context = context,
            categoryUri = categoryUri,
            lineIndex = todo.lineIndex,
        ),
    )

    // Sibling click targets only — nested Row+Text clickables stay dead in ListView
    // until the collection rebinds.
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(start = indent, top = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckBox(
            checked = todo.isDone,
            onCheckedChange = actionRunCallback<ToggleTodoAction>(
                actionParametersOf(
                    ToggleTodoAction.LineIndexKey to todo.lineIndex,
                    ToggleTodoAction.CategoryUriKey to categoryUri,
                ),
            ),
            colors = CheckboxDefaults.colors(),
            modifier = GlanceModifier.size(WidgetCheckboxSize),
        )
        Spacer(modifier = GlanceModifier.width(8.dp))
        Text(
            text = todo.text,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = WidgetBodyFontSize,
            ),
            maxLines = 2,
            modifier = GlanceModifier
                .padding(end = 20.dp)
                .clickable(openEdit),
        )
        // Remaining row space; ListView items do not fall through to the
        // widget background, so this still needs its own open-list action.
        Box(
            modifier = GlanceModifier
                .defaultWeight()
                .height(WidgetCheckboxSize)
                .clickable(openCategory),
        ) {}
    }
}
