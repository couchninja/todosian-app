package com.isotjs.todosian.ui.category

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.isotjs.todosian.R
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.data.model.Todo
import com.isotjs.todosian.utils.MarkdownParser
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class CategoryUiState(
    val isLoading: Boolean = false,
    val title: String = "",
    val activeTodos: List<Todo> = emptyList(),
    val completedTodos: List<Todo> = emptyList(),
    val lines: List<String> = emptyList(),
    val moveTargets: List<MoveTarget> = emptyList(),
)

data class MoveTarget(
    val title: String,
    val uri: Uri,
)

class CategoryViewModel(
    private val fileRepository: FileRepository,
    private val categoryUri: Uri,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CategoryUiState(isLoading = true))
    val uiState: StateFlow<CategoryUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<Event>()
    val events = _events.asSharedFlow()

    private val inFlightWrites = AtomicInteger(0)
    private val pendingRefreshFromObserver = AtomicBoolean(false)

    init {
        observeExternalChanges()
        refreshFromDisk(showLoading = true)
    }

    private fun observeExternalChanges() {
        val folderUri = fileRepository.getFolderUri() ?: return
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            // Watch the whole vault, not only the document URI. Many SAF providers do not
            // notify ContentObserver on document content changes from external editors.
            fileRepository.observeMarkdownFilesChanges(folderUri)
                .debounce(250)
                .catch {
                    // Best-effort; CategoryScreen also refreshes on STARTED.
                }
                .collectLatest {
                    if (inFlightWrites.get() > 0) {
                        pendingRefreshFromObserver.set(true)
                        return@collectLatest
                    }
                    refreshFromDisk(showLoading = false)
                }
        }
    }

    fun load() {
        refreshFromDisk(showLoading = true)
    }

    fun refreshFromDisk(showLoading: Boolean = false) {
        viewModelScope.launch {
            if (showLoading) {
                _uiState.value = _uiState.value.copy(isLoading = true)
            }

            val nameResult = fileRepository.getDisplayName(categoryUri)
            val title = nameResult.getOrNull()?.removeSuffix(".md").orEmpty()

            val linesResult = fileRepository.readLines(categoryUri)
            if (linesResult.isFailure) {
                _events.emit(Event.ShowMessage(R.string.error_read_failed))
                _uiState.value = _uiState.value.copy(isLoading = false, title = title)
                return@launch
            }

            val lines = linesResult.getOrThrow()
            if (!showLoading && lines == _uiState.value.lines) return@launch

            val todos = MarkdownParser.parse(lines)
            val (completed, active) = todos.partition { it.isDone }

            val targets = fileRepository.getCategories()
                .getOrElse { emptyList() }
                .filterNot { it.uri == categoryUri }
                .map { MoveTarget(title = it.displayName, uri = it.uri) }
                .sortedBy { it.title.lowercase() }

            _uiState.value = CategoryUiState(
                isLoading = false,
                title = title,
                activeTodos = active.sortedBy { it.lineIndex },
                completedTodos = completed.sortedBy { it.lineIndex },
                lines = lines,
                moveTargets = targets,
            )
        }
    }

    fun toggleTodo(todo: Todo, enableTasksPluginSupport: Boolean) {
        applyLineMutation(
            mutate = { lines ->
                MarkdownParser.tryToggleLine(
                    lines = lines,
                    lineIndex = todo.lineIndex,
                    enableTasksPlugin = enableTasksPluginSupport,
                )
            },
        )
    }

    /** Clears done state for [todo] and every nested subtask (ghost partial-check action). */
    fun uncompleteTodoTree(todo: Todo, enableTasksPluginSupport: Boolean) {
        applyLineMutation(
            mutate = { lines ->
                MarkdownParser.tryUncompleteTodoTree(
                    lines = lines,
                    lineIndex = todo.lineIndex,
                    enableTasksPlugin = enableTasksPluginSupport,
                )
            },
        )
    }

    /**
     * Applies a line mutation that fails closed with a read-error toast + refresh.
     * No-ops when [mutate] returns the same list instance/content as before.
     */
    private fun applyLineMutation(mutate: (List<String>) -> List<String>?) {
        viewModelScope.launch {
            val previousLines = _uiState.value.lines
            val newLines = mutate(previousLines)
            if (newLines == null) {
                _events.emit(Event.ShowMessage(R.string.error_read_failed))
                refreshFromDisk(showLoading = false)
                return@launch
            }
            if (newLines == previousLines) return@launch

            applyLines(newLines)

            inFlightWrites.incrementAndGet()
            try {
                val write = fileRepository.writeLines(categoryUri, newLines)
                if (write.isFailure) {
                    if (_uiState.value.lines == newLines) {
                        applyLines(previousLines)
                    }
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    fun addTodo(
        text: String,
        meta: MarkdownParser.TasksMeta?,
        enableTasksPluginSupport: Boolean,
        addAtStart: Boolean = false,
    ) {
        viewModelScope.launch {
            val previousLines = _uiState.value.lines
            val newLines = MarkdownParser.addTodo(
                lines = previousLines,
                text = text,
                meta = meta,
                enableTasksPlugin = enableTasksPluginSupport,
                addAtStart = addAtStart,
            )
            if (newLines == previousLines) return@launch

            applyLines(newLines)

            inFlightWrites.incrementAndGet()
            try {
                val write = fileRepository.writeLines(categoryUri, newLines)
                if (write.isFailure) {
                    if (_uiState.value.lines == newLines) {
                        applyLines(previousLines)
                    }
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    fun addSubTodo(
        parent: Todo,
        text: String,
        meta: MarkdownParser.TasksMeta?,
        enableTasksPluginSupport: Boolean,
    ) {
        viewModelScope.launch {
            val previousLines = _uiState.value.lines
            val newLines = MarkdownParser.addSubTodo(
                lines = previousLines,
                parentLineIndex = parent.lineIndex,
                text = text,
                meta = meta,
                enableTasksPlugin = enableTasksPluginSupport,
            )
            if (newLines == null) {
                _events.emit(Event.ShowMessage(R.string.error_read_failed))
                refreshFromDisk(showLoading = false)
                return@launch
            }

            applyLines(newLines)

            inFlightWrites.incrementAndGet()
            try {
                val write = fileRepository.writeLines(categoryUri, newLines)
                if (write.isFailure) {
                    if (_uiState.value.lines == newLines) {
                        applyLines(previousLines)
                    }
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    fun editTodo(
        todo: Todo,
        newText: String,
        meta: MarkdownParser.TasksMeta?,
        enableTasksPluginSupport: Boolean,
    ) {
        viewModelScope.launch {
            val previousLines = _uiState.value.lines
            val newLines = if (enableTasksPluginSupport) {
                MarkdownParser.editTodo(
                    lines = previousLines,
                    lineIndex = todo.lineIndex,
                    newText = newText,
                    meta = meta,
                    enableTasksPlugin = true,
                )
            } else {
                val updated = MarkdownParser.tryEditTodoText(previousLines, todo.lineIndex, newText)
                if (updated == null) {
                    _events.emit(Event.ShowMessage(R.string.error_read_failed))
                    refreshFromDisk(showLoading = false)
                    return@launch
                }
                updated
            }

            applyLines(newLines)

            inFlightWrites.incrementAndGet()
            try {
                val write = fileRepository.writeLines(categoryUri, newLines)
                if (write.isFailure) {
                    if (_uiState.value.lines == newLines) {
                        applyLines(previousLines)
                    }
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    fun deleteTodo(todo: Todo) {
        viewModelScope.launch {
            val previousLines = _uiState.value.lines
            val newLines = MarkdownParser.tryDeleteTodoWithSubtasks(previousLines, todo)
            if (newLines == null) {
                _events.emit(Event.ShowMessage(R.string.error_read_failed))
                refreshFromDisk(showLoading = false)
                return@launch
            }

            applyLines(newLines)

            inFlightWrites.incrementAndGet()
            try {
                val write = fileRepository.writeLines(categoryUri, newLines)
                if (write.isFailure) {
                    if (_uiState.value.lines == newLines) {
                        applyLines(previousLines)
                    }
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    /** Reorders todo blocks; notes and nested subtasks move with each item. */
    fun applyTodoOrder(orderedLineIndices: List<Int>, newOrderedLineIndices: List<Int>) {
        if (orderedLineIndices == newOrderedLineIndices) return
        applySilentLineMutation { lines ->
            MarkdownParser.tryApplyTodoBlockOrder(
                lines = lines,
                orderedLineIndices = orderedLineIndices,
                newOrderedLineIndices = newOrderedLineIndices,
            )
        }
    }

    /**
     * Moves the todo block at [todoLineIndex] under [newParentLineIndex] as a direct child.
     * When [beforeSiblingLineIndex] is null, appends after the parent's nested content.
     */
    fun moveTodoUnderParent(
        todoLineIndex: Int,
        newParentLineIndex: Int,
        beforeSiblingLineIndex: Int? = null,
    ) {
        applySilentLineMutation { lines ->
            MarkdownParser.tryMoveTodoUnderParent(
                lines = lines,
                todoLineIndex = todoLineIndex,
                newParentLineIndex = newParentLineIndex,
                beforeSiblingLineIndex = beforeSiblingLineIndex,
            )
        }
    }

    /** Indents [todo] one level under its previous sibling. Invokes [onUpdated] with the new todo. */
    fun indentTodo(todo: Todo, onUpdated: (Todo) -> Unit = {}) {
        changeTodoIndent(todo, onUpdated, MarkdownParser::tryIndentTodo)
    }

    /** Outdents [todo] one level. Invokes [onUpdated] with the new todo. */
    fun outdentTodo(todo: Todo, onUpdated: (Todo) -> Unit = {}) {
        changeTodoIndent(todo, onUpdated, MarkdownParser::tryOutdentTodo)
    }

    private fun changeTodoIndent(
        todo: Todo,
        onUpdated: (Todo) -> Unit,
        transform: (List<String>, Int) -> List<String>?,
    ) {
        var mutatedLineIndex: Int? = null
        applySilentLineMutation(
            onApplied = { newLines ->
                mutatedLineIndex?.let { lineIndex ->
                    MarkdownParser.parse(newLines)
                        .firstOrNull { it.lineIndex == lineIndex }
                        ?.let(onUpdated)
                }
            },
        ) { lines ->
            val lineIndex = MarkdownParser.resolveTodoLineIndex(lines, todo)
                ?: return@applySilentLineMutation null
            mutatedLineIndex = lineIndex
            // Not applicable → same list (silent no-op). Missing todo → null (refresh).
            transform(lines, lineIndex) ?: lines
        }
    }

    /**
     * Applies a line mutation that fails closed with a silent refresh (not a read-error toast).
     * Used for drag-reorder where null means stale indices / hierarchy mismatch.
     */
    private fun applySilentLineMutation(
        onApplied: ((List<String>) -> Unit)? = null,
        mutate: (List<String>) -> List<String>?,
    ) {
        viewModelScope.launch {
            val previousLines = _uiState.value.lines
            val newLines = mutate(previousLines) ?: run {
                refreshFromDisk(showLoading = false)
                return@launch
            }
            if (newLines == previousLines) return@launch

            applyLines(newLines)
            onApplied?.invoke(newLines)

            inFlightWrites.incrementAndGet()
            try {
                val write = fileRepository.writeLines(categoryUri, newLines)
                if (write.isFailure) {
                    if (_uiState.value.lines == newLines) {
                        applyLines(previousLines)
                    }
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    fun moveTodo(todo: Todo, targetUri: Uri) {
        viewModelScope.launch {
            if (todo.indentLevel > 0) {
                _events.emit(Event.ShowMessage(R.string.category_move_subtask_disabled))
                return@launch
            }
            val previousLines = _uiState.value.lines
            val resolvedIndex = MarkdownParser.resolveTodoLineIndex(previousLines, todo)
            if (resolvedIndex == null) {
                _events.emit(Event.ShowMessage(R.string.error_read_failed))
                refreshFromDisk(showLoading = false)
                return@launch
            }
            val newLines = MarkdownParser.tryDeleteTodoWithSubtasks(previousLines, resolvedIndex)
            if (newLines == null) {
                _events.emit(Event.ShowMessage(R.string.error_read_failed))
                refreshFromDisk(showLoading = false)
                return@launch
            }

            applyLines(newLines)

            inFlightWrites.incrementAndGet()
            try {
                val result = fileRepository.moveTodoLine(categoryUri, targetUri, resolvedIndex)
                if (result.isFailure) {
                    if (_uiState.value.lines == newLines) {
                        applyLines(previousLines)
                    }
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                } else {
                    _events.emit(Event.ShowMessage(R.string.category_move_success))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    fun copyTodo(todo: Todo, targetUri: Uri) {
        viewModelScope.launch {
            inFlightWrites.incrementAndGet()
            try {
                val result = fileRepository.copyTodoLine(categoryUri, targetUri, todo.lineIndex)
                if (result.isFailure) {
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                } else {
                    _events.emit(Event.ShowMessage(R.string.category_copy_success))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    private fun onWriteFinishedMaybeRefresh() {
        val remaining = inFlightWrites.decrementAndGet().coerceAtLeast(0)
        if (remaining == 0 && pendingRefreshFromObserver.getAndSet(false)) {
            refreshFromDisk(showLoading = false)
        }
    }

    private fun applyLines(lines: List<String>) {
        val todos = MarkdownParser.parse(lines)
        val (completed, active) = todos.partition { it.isDone }
        _uiState.value = _uiState.value.copy(
            activeTodos = active.sortedBy { it.lineIndex },
            completedTodos = completed.sortedBy { it.lineIndex },
            lines = lines,
            moveTargets = _uiState.value.moveTargets,
        )
    }

    sealed interface Event {
        data class ShowMessage(@param:StringRes val messageResId: Int) : Event
    }
}
