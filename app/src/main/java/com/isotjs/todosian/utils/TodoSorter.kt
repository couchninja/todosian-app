package com.isotjs.todosian.utils

import com.isotjs.todosian.data.model.Todo
import com.isotjs.todosian.data.model.priorityRank
import com.isotjs.todosian.data.settings.TodoSort

/**
 * Shared todo ordering used by the category screen and home-screen widget.
 */
object TodoSorter {
    fun sort(todos: List<Todo>, sort: TodoSort): List<Todo> {
        return when (sort) {
            TodoSort.FILE_ORDER -> todos.sortedBy { it.lineIndex }
            TodoSort.PRIORITY_HIGH_TO_LOW -> sortByPriorityKeepingSubtasks(todos)
            TodoSort.CREATED_DATE_NEWEST_FIRST ->
                todos.sortedWith(
                    compareByDescending<Todo> { it.createdDate != null }
                        .thenByDescending { it.createdDate ?: "" }
                        .thenBy { it.lineIndex },
                )
            TodoSort.DUE_DATE_EARLIEST_FIRST ->
                todos.sortedWith(
                    compareBy<Todo> { it.dueDate == null }
                        .thenBy { it.dueDate ?: "" }
                        .thenBy { it.lineIndex },
                )
        }
    }

    private data class TodoGroup(
        val parent: Todo,
        val children: List<Todo>,
    )

    private fun sortByPriorityKeepingSubtasks(todos: List<Todo>): List<Todo> {
        if (todos.isEmpty()) return todos

        val ordered = todos.sortedBy { it.lineIndex }
        val groups = ArrayList<TodoGroup>()

        var currentParent: Todo? = null
        var currentChildren = ArrayList<Todo>()

        fun flushGroup() {
            val parent = currentParent
            if (parent != null) {
                groups.add(TodoGroup(parent = parent, children = currentChildren.toList()))
            }
        }

        for (todo in ordered) {
            if (todo.indentLevel == 0 || currentParent == null) {
                flushGroup()
                currentParent = todo
                currentChildren = ArrayList()
            } else {
                currentChildren.add(todo)
            }
        }

        flushGroup()

        val sortedGroups = groups.sortedWith(
            compareByDescending<TodoGroup> { it.parent.priority.priorityRank() }
                .thenBy { it.parent.lineIndex },
        )

        val result = ArrayList<Todo>(ordered.size)
        for (group in sortedGroups) {
            result.add(group.parent)
            result.addAll(group.children)
        }

        return result
    }
}
