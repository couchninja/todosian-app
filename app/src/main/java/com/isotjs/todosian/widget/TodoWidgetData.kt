package com.isotjs.todosian.widget

import com.isotjs.todosian.data.model.Category
import com.isotjs.todosian.data.settings.TodoSort
import com.isotjs.todosian.utils.MarkdownParser
import com.isotjs.todosian.utils.TodoSorter

/**
 * Pure widget list/toggle helpers (no Android UI). Safe to unit-test on the JVM.
 */
object TodoWidgetData {
    data class Item(
        val lineIndex: Int,
        val text: String,
        val isDone: Boolean,
        val indentLevel: Int,
    )

    /**
     * Stable-enough RemoteViews item id that changes when a todo moves to a new line
     * after inserts/deletes, or when its checked state flips (so LazyColumn does not
     * reuse a stale checkbox row when the item moves between active/completed).
     * Using only [Item.lineIndex] reuses ids for different todos and can leave "ghost" rows.
     */
    fun itemId(item: Item, categoryKey: String = ""): Long {
        val textHash = item.text.hashCode().toLong() and 0xffff_ffffL
        val doneBit = if (item.isDone) 1L shl 33 else 0L
        val categoryBit = categoryKey.hashCode().toLong() shl 1
        return (item.lineIndex.toLong() shl 34) xor textHash xor doneBit xor categoryBit
    }

    /**
     * Prefer the saved URI when it still exists; otherwise the first item.
     * [getCategories] is already sorted A–Z by display name.
     */
    fun <T> resolveSelected(
        items: List<T>,
        selectedUri: String?,
        uriOf: (T) -> String,
    ): T? {
        if (items.isEmpty()) return null
        if (!selectedUri.isNullOrBlank()) {
            items.firstOrNull { uriOf(it) == selectedUri }?.let { return it }
        }
        return items.first()
    }

    fun resolveSelectedCategory(
        categories: List<Category>,
        selectedUri: String?,
    ): Category? = resolveSelected(categories, selectedUri) { it.uri.toString() }

    /**
     * Next item after the currently selected one (wraps). Falls back to the first item
     * when the saved URI is missing, or the only item when there is just one.
     */
    fun <T> nextAfter(
        items: List<T>,
        selectedUri: String?,
        uriOf: (T) -> String,
    ): T? {
        if (items.isEmpty()) return null
        if (items.size == 1) return items.first()
        val currentIndex = items.indexOfFirst { uriOf(it) == selectedUri }
        val nextIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % items.size
        return items[nextIndex]
    }

    fun itemsFromLines(
        lines: List<String>,
        untitledLabel: String,
        todoSort: TodoSort = TodoSort.FILE_ORDER,
        activeOnly: Boolean = false,
    ): List<Item> {
        val parsed = MarkdownParser.parse(lines)
        val source = if (activeOnly) parsed.filterNot { it.isDone } else parsed
        return TodoSorter.sort(source, todoSort).map { todo ->
            Item(
                lineIndex = todo.lineIndex,
                text = todo.text.ifBlank { untitledLabel },
                isDone = todo.isDone,
                indentLevel = todo.indentLevel,
            )
        }
    }

    /**
     * Apply a checkbox target state to a todo line. No-ops when already matching.
     * Returns null when [lineIndex] is not a todo (e.g. stale index after a delete).
     */
    fun applyToggle(
        lines: List<String>,
        lineIndex: Int,
        targetChecked: Boolean,
        enableTasksPlugin: Boolean,
    ): List<String>? {
        if (lineIndex !in lines.indices || !MarkdownParser.isTodoLine(lines[lineIndex])) {
            return null
        }
        val isDoneNow = MarkdownParser.parse(listOf(lines[lineIndex])).firstOrNull()?.isDone == true
        if (isDoneNow == targetChecked) {
            return lines
        }
        return MarkdownParser.tryToggleLine(
            lines = lines,
            lineIndex = lineIndex,
            enableTasksPlugin = enableTasksPlugin,
        )
    }
}
