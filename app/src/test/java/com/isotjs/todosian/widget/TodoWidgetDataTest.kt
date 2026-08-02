package com.isotjs.todosian.widget

import com.isotjs.todosian.data.settings.TodoSort
import com.isotjs.todosian.utils.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoWidgetDataTest {

    private companion object {
        const val UNTITLED_LABEL = "Untitled task"
    }

    @Test
    fun itemsFromLines_matches_current_file_contents() {
        val lines = listOf(
            "# todo",
            "- [ ] Buy milk",
            "- [x] Call dentist",
            "- [ ] Plan trip",
        )

        val items = TodoWidgetData.itemsFromLines(lines, untitledLabel = UNTITLED_LABEL)

        assertEquals(listOf("Buy milk", "Call dentist", "Plan trip"), items.map { it.text })
        assertEquals(listOf(false, true, false), items.map { it.isDone })
        assertEquals(listOf(1, 2, 3), items.map { it.lineIndex })
    }

    @Test
    fun itemsFromLines_does_not_include_deleted_todos() {
        val before = listOf(
            "- [ ] Keep me",
            "- [ ] Delete me",
            "- [ ] Also keep",
        )
        val afterDelete = MarkdownParser.tryDeleteTodo(before, lineIndex = 1)!!

        val items = TodoWidgetData.itemsFromLines(afterDelete, untitledLabel = UNTITLED_LABEL)

        assertEquals(listOf("Keep me", "Also keep"), items.map { it.text })
        assertFalse(items.any { it.text == "Delete me" })
    }

    @Test
    fun itemId_changes_when_category_changes() {
        val item = TodoWidgetData.Item(
            lineIndex = 1,
            text = "Buy milk",
            isDone = false,
            indentLevel = 0,
        )
        assertNotEquals(
            TodoWidgetData.itemId(item, "content://a"),
            TodoWidgetData.itemId(item, "content://b"),
        )
    }

    @Test
    fun itemId_changes_when_checked_state_flips() {
        val active = TodoWidgetData.Item(
            lineIndex = 1,
            text = "Buy milk",
            isDone = false,
            indentLevel = 0,
        )
        val completed = active.copy(isDone = true)

        assertNotEquals(TodoWidgetData.itemId(active), TodoWidgetData.itemId(completed))
    }

    @Test
    fun itemId_does_not_reuse_deleted_row_identity_for_shifted_todo() {
        val before = listOf(
            "- [ ] Alpha",
            "- [ ] Bravo",
            "- [ ] Charlie",
        )
        val beforeItems = TodoWidgetData.itemsFromLines(before, untitledLabel = UNTITLED_LABEL)
        val alphaId = TodoWidgetData.itemId(beforeItems[0])
        val bravoId = TodoWidgetData.itemId(beforeItems[1])
        val charlieId = TodoWidgetData.itemId(beforeItems[2])

        val afterDeleteAlpha = MarkdownParser.tryDeleteTodo(before, lineIndex = 0)!!
        val afterItems = TodoWidgetData.itemsFromLines(
            afterDeleteAlpha,
            untitledLabel = UNTITLED_LABEL,
        )

        assertEquals(listOf("Bravo", "Charlie"), afterItems.map { it.text })
        val newBravoId = TodoWidgetData.itemId(afterItems[0])
        val newCharlieId = TodoWidgetData.itemId(afterItems[1])

        // Bravo moved from line 1 -> 0, so its widget item id must change.
        // Otherwise RemoteViews can keep showing the old "Alpha" row for id(line=0).
        assertNotEquals(bravoId, newBravoId)
        assertNotEquals(alphaId, newBravoId)
        assertNotEquals(charlieId, newCharlieId)
        assertFalse(afterItems.map { TodoWidgetData.itemId(it) }.contains(alphaId))
    }

    @Test
    fun applyToggle_checks_and_unchecks_to_target_state() {
        val lines = listOf("- [ ] Task")

        val checked = TodoWidgetData.applyToggle(
            lines = lines,
            lineIndex = 0,
            targetChecked = true,
            enableTasksPlugin = false,
        )!!
        assertEquals(listOf("- [x] Task"), checked)

        val unchecked = TodoWidgetData.applyToggle(
            lines = checked,
            lineIndex = 0,
            targetChecked = false,
            enableTasksPlugin = false,
        )!!
        assertEquals(listOf("- [ ] Task"), unchecked)
    }

    @Test
    fun applyToggle_is_idempotent_for_same_target() {
        val lines = listOf("- [x] Already done")

        val again = TodoWidgetData.applyToggle(
            lines = lines,
            lineIndex = 0,
            targetChecked = true,
            enableTasksPlugin = false,
        )!!

        assertEquals(lines, again)
    }

    @Test
    fun applyToggle_completing_parent_completes_nested_subtasks() {
        val lines = listOf(
            "- [ ] Parent",
            "  - [ ] Child",
            "    - [ ] Grandchild",
            "- [ ] Sibling",
        )

        val checked = TodoWidgetData.applyToggle(
            lines = lines,
            lineIndex = 0,
            targetChecked = true,
            enableTasksPlugin = false,
        )!!

        assertEquals(
            listOf(
                "- [x] Parent",
                "  - [x] Child",
                "    - [x] Grandchild",
                "- [ ] Sibling",
            ),
            checked,
        )
    }

    @Test
    fun applyToggle_returns_null_for_stale_line_after_delete() {
        val lines = listOf(
            "- [ ] First",
            "- [ ] Second",
        )
        val afterDelete = MarkdownParser.tryDeleteTodo(lines, lineIndex = 1)!!

        // Old widget click still has lineIndex=1, but that line no longer exists.
        val result = TodoWidgetData.applyToggle(
            lines = afterDelete,
            lineIndex = 1,
            targetChecked = true,
            enableTasksPlugin = false,
        )

        assertNull(result)
        assertEquals(listOf("- [ ] First"), afterDelete)
    }

    @Test
    fun applyToggle_returns_null_when_line_is_not_a_todo() {
        val lines = listOf("# Header", "- [ ] Task")

        assertNull(
            TodoWidgetData.applyToggle(
                lines = lines,
                lineIndex = 0,
                targetChecked = true,
                enableTasksPlugin = false,
            ),
        )
    }

    @Test
    fun itemsFromLines_uses_untitled_label_for_blank_todo_text() {
        val lines = listOf("- [ ] ")
        val items = TodoWidgetData.itemsFromLines(lines, untitledLabel = UNTITLED_LABEL)

        assertEquals(1, items.size)
        assertEquals(UNTITLED_LABEL, items[0].text)
        assertTrue(items[0].text.isNotBlank())
    }

    @Test
    fun reload_after_add_includes_new_todo() {
        val original = listOf("- [ ] Existing")
        val afterAdd = MarkdownParser.addTodo(original, text = "Brand new")

        val items = TodoWidgetData.itemsFromLines(afterAdd, untitledLabel = UNTITLED_LABEL)

        assertEquals(listOf("Existing", "Brand new"), items.map { it.text })
    }

    @Test
    fun itemsFromLines_can_partition_active_and_completed() {
        val lines = listOf(
            "- [ ] Active one",
            "- [x] Done one",
            "- [ ] Active two",
        )
        val items = TodoWidgetData.itemsFromLines(lines, untitledLabel = UNTITLED_LABEL)
        val (completed, active) = items.partition { it.isDone }

        assertEquals(listOf("Active one", "Active two"), active.map { it.text })
        assertEquals(listOf("Done one"), completed.map { it.text })
    }

    @Test
    fun itemsFromLines_respects_due_date_sort_for_active_only() {
        val lines = listOf(
            "- [ ] Later 📅 2025-06-01",
            "- [x] Done early 📅 2025-01-01",
            "- [ ] Soon 📅 2025-02-01",
            "- [ ] No date",
        )

        val items = TodoWidgetData.itemsFromLines(
            lines = lines,
            untitledLabel = UNTITLED_LABEL,
            todoSort = TodoSort.DUE_DATE_EARLIEST_FIRST,
            activeOnly = true,
        )

        assertEquals(listOf("Soon", "Later", "No date"), items.map { it.text })
    }

    @Test
    fun itemsFromLines_respects_created_date_sort() {
        val lines = listOf(
            "- [ ] Old ➕ 2024-01-01",
            "- [ ] New ➕ 2025-06-01",
            "- [ ] No date",
        )

        val items = TodoWidgetData.itemsFromLines(
            lines = lines,
            untitledLabel = UNTITLED_LABEL,
            todoSort = TodoSort.CREATED_DATE_NEWEST_FIRST,
            activeOnly = true,
        )

        assertEquals(listOf("New", "Old", "No date"), items.map { it.text })
    }

    @Test
    fun itemsFromLines_respects_priority_sort() {
        val lines = listOf(
            "- [ ] Low 🔽",
            "- [ ] High 🔺",
            "- [ ] Medium 🔼",
        )

        val items = TodoWidgetData.itemsFromLines(
            lines = lines,
            untitledLabel = UNTITLED_LABEL,
            todoSort = TodoSort.PRIORITY_HIGH_TO_LOW,
            activeOnly = true,
        )

        assertEquals(listOf("High", "Medium", "Low"), items.map { it.text })
    }

    @Test
    fun resolveSelectedCategory_prefers_saved_uri_when_present() {
        val items = listOf("content://a" to "a", "content://b" to "b")

        val resolved = TodoWidgetData.resolveSelected(
            items = items,
            selectedUri = "content://b",
            uriOf = { it.first },
        )

        assertEquals("content://b" to "b", resolved)
    }

    @Test
    fun resolveSelectedCategory_falls_back_to_first_when_saved_missing() {
        val items = listOf("content://a" to "a", "content://b" to "b")

        val resolved = TodoWidgetData.resolveSelected(
            items = items,
            selectedUri = "content://gone",
            uriOf = { it.first },
        )

        assertEquals("content://a" to "a", resolved)
    }

    @Test
    fun resolveSelectedCategory_returns_null_for_empty_list() {
        assertNull(
            TodoWidgetData.resolveSelected(
                items = emptyList<Pair<String, String>>(),
                selectedUri = "content://a",
                uriOf = { it.first },
            ),
        )
    }

    @Test
    fun nextAfter_cycles_to_following_item_and_wraps() {
        val items = listOf("a", "b", "c")

        assertEquals(
            "b",
            TodoWidgetData.nextAfter(items, selectedUri = "a", uriOf = { it }),
        )
        assertEquals(
            "c",
            TodoWidgetData.nextAfter(items, selectedUri = "b", uriOf = { it }),
        )
        assertEquals(
            "a",
            TodoWidgetData.nextAfter(items, selectedUri = "c", uriOf = { it }),
        )
    }

    @Test
    fun nextAfter_falls_back_to_first_when_selection_missing() {
        val items = listOf("a", "b", "c")
        assertEquals(
            "a",
            TodoWidgetData.nextAfter(items, selectedUri = "gone", uriOf = { it }),
        )
    }
}
