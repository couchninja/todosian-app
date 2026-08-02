package com.isotjs.todosian.utils

import com.isotjs.todosian.data.model.TasksPriority
import com.isotjs.todosian.data.model.Todo
import com.isotjs.todosian.data.settings.TodoSort
import org.junit.Assert.assertEquals
import org.junit.Test

class TodoSorterTest {

    @Test
    fun fileOrder_sortsByLineIndex() {
        val todos = listOf(
            todo(lineIndex = 3, text = "C"),
            todo(lineIndex = 1, text = "A"),
            todo(lineIndex = 2, text = "B"),
        )

        val sorted = TodoSorter.sort(todos, TodoSort.FILE_ORDER)

        assertEquals(listOf("A", "B", "C"), sorted.map { it.text })
    }

    @Test
    fun priority_highToLow_keepsSubtasksWithParent() {
        val todos = listOf(
            todo(lineIndex = 0, text = "Low", priority = TasksPriority.LOW),
            todo(lineIndex = 1, text = "Low child", indentLevel = 1),
            todo(lineIndex = 2, text = "High", priority = TasksPriority.HIGH),
            todo(lineIndex = 3, text = "High child", indentLevel = 1),
        )

        val sorted = TodoSorter.sort(todos, TodoSort.PRIORITY_HIGH_TO_LOW)

        assertEquals(
            listOf("High", "High child", "Low", "Low child"),
            sorted.map { it.text },
        )
    }

    @Test
    fun createdDate_newestFirst_nullsLast() {
        val todos = listOf(
            todo(lineIndex = 0, text = "Old", createdDate = "2024-01-01"),
            todo(lineIndex = 1, text = "No date"),
            todo(lineIndex = 2, text = "New", createdDate = "2025-06-01"),
        )

        val sorted = TodoSorter.sort(todos, TodoSort.CREATED_DATE_NEWEST_FIRST)

        assertEquals(listOf("New", "Old", "No date"), sorted.map { it.text })
    }

    @Test
    fun dueDate_earliestFirst_nullsLast() {
        val todos = listOf(
            todo(lineIndex = 0, text = "Later", dueDate = "2025-06-01"),
            todo(lineIndex = 1, text = "No date"),
            todo(lineIndex = 2, text = "Soon", dueDate = "2025-01-01"),
        )

        val sorted = TodoSorter.sort(todos, TodoSort.DUE_DATE_EARLIEST_FIRST)

        assertEquals(listOf("Soon", "Later", "No date"), sorted.map { it.text })
    }

    private fun todo(
        lineIndex: Int,
        text: String,
        indentLevel: Int = 0,
        priority: TasksPriority? = null,
        createdDate: String? = null,
        dueDate: String? = null,
    ): Todo = Todo(
        id = "line-$lineIndex",
        text = text,
        isDone = false,
        lineIndex = lineIndex,
        indentLevel = indentLevel,
        priority = priority,
        createdDate = createdDate,
        dueDate = dueDate,
    )
}
