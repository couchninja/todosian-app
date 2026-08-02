package com.isotjs.todosian.utils

import com.isotjs.todosian.data.model.Todo
import com.isotjs.todosian.data.settings.TodoSort

/**
 * Builds the Completed section list, inserting incomplete ancestors as "ghost" rows so
 * completed subtasks keep their tree context when the parent remains active.
 */
object CompletedSectionBuilder {
    data class Item(
        val todo: Todo,
        /** Incomplete ancestor shown only for structure; the real task stays in Active. */
        val isGhost: Boolean,
        /** For ghosts: true when every nested todo under this ancestor is done. */
        val allDescendantsDone: Boolean = false,
    )

    fun build(allTodos: List<Todo>, sort: TodoSort = TodoSort.FILE_ORDER): List<Item> {
        if (allTodos.isEmpty()) return emptyList()

        val ordered = allTodos.sortedBy { it.lineIndex }
        val include = markCompletedWithAncestors(ordered)
        if (include.none { it }) return emptyList()

        val included = ordered.mapIndexedNotNull { index, todo ->
            if (!include[index]) return@mapIndexedNotNull null
            val isGhost = !todo.isDone
            Item(
                todo = todo,
                isGhost = isGhost,
                allDescendantsDone = isGhost && areAllDescendantsDone(ordered, index),
            )
        }

        val flagsByLine = included.associateBy { it.todo.lineIndex }
        return TodoSorter.sort(included.map { it.todo }, sort).map { todo ->
            flagsByLine.getValue(todo.lineIndex)
        }
    }

    /** Marks each completed todo and every incomplete ancestor that leads to one. */
    private fun markCompletedWithAncestors(ordered: List<Todo>): BooleanArray {
        val parentOf = IntArray(ordered.size) { -1 }
        val stack = ArrayDeque<Int>()
        for (i in ordered.indices) {
            val level = ordered[i].indentLevel
            while (stack.isNotEmpty() && ordered[stack.last()].indentLevel >= level) {
                stack.removeLast()
            }
            parentOf[i] = stack.lastOrNull() ?: -1
            stack.addLast(i)
        }

        val include = BooleanArray(ordered.size)
        for (i in ordered.indices) {
            if (!ordered[i].isDone) continue
            include[i] = true
            var ancestor = parentOf[i]
            while (ancestor >= 0) {
                include[ancestor] = true
                ancestor = parentOf[ancestor]
            }
        }
        return include
    }

    /** True when [rootIndex] has nested todos and every one of them is done. */
    private fun areAllDescendantsDone(ordered: List<Todo>, rootIndex: Int): Boolean {
        val rootLevel = ordered[rootIndex].indentLevel
        var sawDescendant = false
        for (i in (rootIndex + 1) until ordered.size) {
            val level = ordered[i].indentLevel
            if (level <= rootLevel) break
            sawDescendant = true
            if (!ordered[i].isDone) return false
        }
        return sawDescendant
    }
}
