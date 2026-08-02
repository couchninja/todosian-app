package com.isotjs.todosian.utils

import com.isotjs.todosian.data.model.TasksPriority
import com.isotjs.todosian.data.model.Todo
import com.isotjs.todosian.data.settings.TodoSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletedSectionBuilderTest {

    @Test
    fun empty_when_no_completed_todos() {
        val todos = listOf(
            todo(0, "Parent", isDone = false),
            todo(1, "Child", isDone = false, indentLevel = 1),
        )

        assertEquals(emptyList<CompletedSectionBuilder.Item>(), CompletedSectionBuilder.build(todos))
    }

    @Test
    fun completed_top_level_has_no_ghost() {
        val todos = listOf(
            todo(0, "Done", isDone = true),
            todo(1, "Active", isDone = false),
        )

        val items = CompletedSectionBuilder.build(todos)

        assertEquals(listOf("Done"), items.map { it.todo.text })
        assertFalse(items.single().isGhost)
    }

    @Test
    fun completed_subtask_inserts_ghost_parent() {
        val todos = listOf(
            todo(0, "Parent", isDone = false),
            todo(1, "Done child", isDone = true, indentLevel = 1),
            todo(2, "Active child", isDone = false, indentLevel = 1),
        )

        val items = CompletedSectionBuilder.build(todos)

        assertEquals(listOf("Parent", "Done child"), items.map { it.todo.text })
        assertTrue(items[0].isGhost)
        assertFalse(items[0].allDescendantsDone)
        assertFalse(items[1].isGhost)
    }

    @Test
    fun ghost_all_descendants_done_when_every_nested_todo_is_complete() {
        val todos = listOf(
            todo(0, "Parent", isDone = false),
            todo(1, "Done child", isDone = true, indentLevel = 1),
            todo(2, "Also done", isDone = true, indentLevel = 1),
        )

        val items = CompletedSectionBuilder.build(todos)

        assertEquals(listOf("Parent", "Done child", "Also done"), items.map { it.todo.text })
        assertTrue(items[0].isGhost)
        assertTrue(items[0].allDescendantsDone)
    }

    @Test
    fun completed_sub_subtask_inserts_ghost_chain() {
        val todos = listOf(
            todo(0, "Parent", isDone = false),
            todo(1, "Subtask", isDone = false, indentLevel = 1),
            todo(2, "Nested done", isDone = true, indentLevel = 2),
            todo(3, "Sibling", isDone = false, indentLevel = 1),
        )

        val items = CompletedSectionBuilder.build(todos)

        assertEquals(listOf("Parent", "Subtask", "Nested done"), items.map { it.todo.text })
        assertTrue(items[0].isGhost)
        assertFalse(items[0].allDescendantsDone)
        assertTrue(items[1].isGhost)
        assertTrue(items[1].allDescendantsDone)
        assertFalse(items[2].isGhost)
    }

    @Test
    fun skips_incomplete_siblings_that_are_not_ancestors() {
        val todos = listOf(
            todo(0, "Parent", isDone = false),
            todo(1, "Skip me", isDone = false, indentLevel = 1),
            todo(2, "Done", isDone = true, indentLevel = 1),
        )

        val items = CompletedSectionBuilder.build(todos)

        assertEquals(listOf("Parent", "Done"), items.map { it.todo.text })
    }

    @Test
    fun completed_parent_keeps_completed_children_without_ghosts() {
        val todos = listOf(
            todo(0, "Parent", isDone = true),
            todo(1, "Child", isDone = true, indentLevel = 1),
        )

        val items = CompletedSectionBuilder.build(todos)

        assertEquals(listOf("Parent", "Child"), items.map { it.todo.text })
        assertTrue(items.none { it.isGhost })
    }

    @Test
    fun priority_sort_keeps_ghost_with_completed_children() {
        val todos = listOf(
            todo(0, "Low parent", isDone = false, priority = TasksPriority.LOW),
            todo(1, "Low done", isDone = true, indentLevel = 1),
            todo(2, "High done", isDone = true, priority = TasksPriority.HIGH),
        )

        val items = CompletedSectionBuilder.build(todos, TodoSort.PRIORITY_HIGH_TO_LOW)

        assertEquals(
            listOf("High done", "Low parent", "Low done"),
            items.map { it.todo.text },
        )
        assertTrue(items[1].isGhost)
        assertFalse(items[2].isGhost)
    }

    private fun todo(
        lineIndex: Int,
        text: String,
        isDone: Boolean,
        indentLevel: Int = 0,
        priority: TasksPriority? = null,
    ): Todo = Todo(
        id = "line-$lineIndex",
        text = text,
        isDone = isDone,
        lineIndex = lineIndex,
        indentLevel = indentLevel,
        priority = priority,
    )
}
