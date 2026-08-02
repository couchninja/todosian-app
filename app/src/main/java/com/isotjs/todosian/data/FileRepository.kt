package com.isotjs.todosian.data

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.isotjs.todosian.data.model.Category
import com.isotjs.todosian.utils.MarkdownParser
import com.isotjs.todosian.widget.CategoriesWidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Max in-memory undo snapshots kept across the app. */
const val MAX_UNDO_BACKUPS = 3

/** Poll interval for detecting external markdown edits while a screen is open. */
internal const val EXTERNAL_FILE_POLL_MS = 2_000L

/** Undo snapshot of a markdown file's full contents. */
data class FileUndoBackup(
    val uri: Uri,
    val lines: List<String>,
)

interface FileRepository {
    /** Undo stack (oldest → newest). At most [MAX_UNDO_BACKUPS] entries. */
    val undoStack: StateFlow<List<FileUndoBackup>>

    fun getFolderUri(): Uri?

    fun clearFolderUri()

    suspend fun persistFolderUri(uri: Uri): Result<Unit>

    suspend fun getCategories(): Result<List<Category>>

    suspend fun readLines(uri: Uri): Result<List<String>>

    /** Document last-modified time in epoch millis, or 0 if the provider does not report one. */
    suspend fun getLastModified(uri: Uri): Result<Long>

    suspend fun getDisplayName(uri: Uri): Result<String>

    suspend fun getFolderDisplayName(folderUri: Uri): Result<String>

    /**
     * Writes [lines] to [uri].
     *
     * When [backupForUndo] is non-null and the write succeeds, it is pushed onto the undo stack
     * (capped at [MAX_UNDO_BACKUPS]). When null, the undo stack is cleared.
     */
    suspend fun writeLines(
        uri: Uri,
        lines: List<String>,
        backupForUndo: List<String>? = null,
    ): Result<Unit>

    /** Pops and restores the newest undo snapshot. */
    suspend fun restoreUndoBackup(): Result<FileUndoBackup>

    /** Drops all undo snapshots (e.g. after an external file edit). */
    fun clearUndoBackups()

    suspend fun createCategory(folderUri: Uri, name: String): Result<Uri>

    suspend fun renameCategory(categoryUri: Uri, newName: String): Result<Unit>

    suspend fun deleteCategory(categoryUri: Uri): Result<Unit>

    suspend fun moveTodoLine(sourceUri: Uri, targetUri: Uri, lineIndex: Int): Result<Unit>

    suspend fun copyTodoLine(sourceUri: Uri, targetUri: Uri, lineIndex: Int): Result<Unit>

    fun hasPersistedReadWritePermission(uri: Uri): Boolean

    suspend fun countMarkdownFiles(folderUri: Uri): Result<Int>

    fun observeMarkdownFilesChanges(folderUri: Uri): Flow<Unit>
}

class SafFileRepository(
    private val appContext: Context,
    private val preferencesManager: PreferencesManager,
) : FileRepository {

    private val _undoStack = MutableStateFlow<List<FileUndoBackup>>(emptyList())
    override val undoStack: StateFlow<List<FileUndoBackup>> = _undoStack.asStateFlow()

    override fun getFolderUri(): Uri? = preferencesManager.getFolderUri()

    override fun clearFolderUri() {
        preferencesManager.clearFolderUri()
        CategoriesWidgetUpdater.requestUpdate(appContext)
    }

    override suspend fun persistFolderUri(uri: Uri): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                appContext.contentResolver.takePersistableUriPermission(uri, flags)
                preferencesManager.saveFolderUri(uri)
            }
        }.onSuccess {
            CategoriesWidgetUpdater.requestUpdate(appContext)
        }
    }

    override suspend fun getCategories(): Result<List<Category>> {
        val folderUri = getFolderUri() ?: return Result.success(emptyList())
        return withContext(Dispatchers.IO) {
            runCatching {
                val folder = DocumentFile.fromTreeUri(appContext, folderUri)
                    ?: throw IllegalStateException("Invalid folder URI")

                folder.listFiles()
                    .asSequence()
                    .filter { it.isFile }
                    .filter { file ->
                        val name = file.name.orEmpty()
                        name.endsWith(".md", ignoreCase = true) && !name.contains("sync-conflict", ignoreCase = true)
                    }
                    .map { file ->
                        val name = file.name ?: ""
                        val displayName = name.removeSuffix(".md")
                        val lines = readLinesInternal(file.uri)
                        val todos = MarkdownParser.parse(lines)
                        val doneCount = todos.count { it.isDone }
                        val today = LocalDate.now().toString()
                        val activeTodos = todos.filter { !it.isDone }
                        val dueTodayCount = activeTodos.count { it.dueDate == today }
                        val overdueCount = activeTodos.count { todo ->
                            todo.dueDate?.let { it < today } == true
                        }
                        Category(
                            fileName = name,
                            displayName = displayName,
                            uri = file.uri,
                            todoCount = todos.size,
                            doneCount = doneCount,
                            dueTodayCount = dueTodayCount,
                            overdueCount = overdueCount,
                        )
                    }
                    .sortedBy { it.displayName.lowercase() }
                    .toList()
            }
        }
    }

    override suspend fun readLines(uri: Uri): Result<List<String>> {
        return withContext(Dispatchers.IO) {
            runCatching { readLinesInternal(uri) }
        }
    }

    override suspend fun getLastModified(uri: Uri): Result<Long> {
        return withContext(Dispatchers.IO) {
            runCatching { lastModifiedInternal(uri) }
        }
    }

    override suspend fun getDisplayName(uri: Uri): Result<String> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = DocumentFile.fromSingleUri(appContext, uri)
                    ?: throw IllegalStateException("Invalid file URI")
                file.name ?: ""
            }
        }
    }

    override suspend fun getFolderDisplayName(folderUri: Uri): Result<String> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val folder = DocumentFile.fromTreeUri(appContext, folderUri)
                    ?: throw IllegalStateException("Invalid folder URI")
                folder.name
                    ?: folderUri.lastPathSegment
                    ?: folderUri.toString()
            }
        }
    }

    override suspend fun writeLines(
        uri: Uri,
        lines: List<String>,
        backupForUndo: List<String>?,
    ): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                writeLinesInternal(uri, lines)
            }
        }.onSuccess {
            if (backupForUndo != null) {
                pushUndoBackup(FileUndoBackup(uri = uri, lines = backupForUndo.toList()))
            } else {
                clearUndoBackups()
            }
            CategoriesWidgetUpdater.requestUpdate(appContext)
        }
    }

    override suspend fun restoreUndoBackup(): Result<FileUndoBackup> {
        val stack = _undoStack.value
        if (stack.isEmpty()) {
            return Result.failure(IllegalStateException("No undo backup"))
        }
        val backup = stack.last()
        val remaining = stack.dropLast(1)
        return withContext(Dispatchers.IO) {
            runCatching {
                writeLinesInternal(backup.uri, backup.lines)
            }
        }.onSuccess {
            _undoStack.value = remaining
            CategoriesWidgetUpdater.requestUpdate(appContext)
        }.map { backup }
    }

    override fun clearUndoBackups() {
        _undoStack.value = emptyList()
    }

    private fun pushUndoBackup(backup: FileUndoBackup) {
        _undoStack.value = (_undoStack.value + backup).takeLast(MAX_UNDO_BACKUPS)
    }

    override suspend fun createCategory(folderUri: Uri, name: String): Result<Uri> {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) return Result.failure(IllegalArgumentException("Empty name"))

        return withContext(Dispatchers.IO) {
            runCatching {
                val folder = DocumentFile.fromTreeUri(appContext, folderUri)
                    ?: throw IllegalStateException("Invalid folder URI")

                val fileName = if (cleaned.endsWith(".md", ignoreCase = true)) cleaned else "$cleaned.md"
                val created = folder.createFile("text/markdown", fileName)
                    ?: throw IllegalStateException("Unable to create file")

                created.uri
            }
        }.onSuccess {
            CategoriesWidgetUpdater.requestUpdate(appContext)
        }
    }

    override suspend fun renameCategory(categoryUri: Uri, newName: String): Result<Unit> {
        val cleaned = newName.trim()
        if (cleaned.isEmpty()) return Result.failure(IllegalArgumentException("Empty name"))

        return withContext(Dispatchers.IO) {
            runCatching {
                val fileName = cleaned
                    .removeSuffix(".md")
                    .removeSuffix(".MD")
                    .removeSuffix(".Md")
                    .removeSuffix(".mD")
                    .trim()
                    .let { base -> if (base.endsWith(".md", ignoreCase = true)) base else "$base.md" }

                val renamed = DocumentsContract.renameDocument(appContext.contentResolver, categoryUri, fileName)
                if (renamed == null) throw IllegalStateException("Unable to rename document")
            }
        }.onSuccess {
            clearUndoBackups()
            CategoriesWidgetUpdater.requestUpdate(appContext)
        }
    }

    override suspend fun deleteCategory(categoryUri: Uri): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = DocumentFile.fromSingleUri(appContext, categoryUri)
                    ?: throw IllegalStateException("Invalid file URI")
                val ok = file.delete()
                if (!ok) throw IllegalStateException("Unable to delete file")
            }
        }.onSuccess {
            clearUndoBackups()
            CategoriesWidgetUpdater.requestUpdate(appContext)
        }
    }

    override suspend fun moveTodoLine(sourceUri: Uri, targetUri: Uri, lineIndex: Int): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val sourceLines = readLinesInternal(sourceUri)
                val targetLines = readLinesInternal(targetUri)
                val updated = MarkdownParser.tryMoveTodoLine(
                    sourceLines = sourceLines,
                    lineIndex = lineIndex,
                    targetLines = targetLines,
                ) ?: throw IllegalStateException("Invalid todo line")

                writeLinesInternal(sourceUri, updated.first)
                writeLinesInternal(targetUri, updated.second)
            }
        }.onSuccess {
            clearUndoBackups()
            CategoriesWidgetUpdater.requestUpdate(appContext)
        }
    }

    override suspend fun copyTodoLine(sourceUri: Uri, targetUri: Uri, lineIndex: Int): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val sourceLines = readLinesInternal(sourceUri)
                val targetLines = readLinesInternal(targetUri)
                val updated = MarkdownParser.tryCopyTodoLine(
                    sourceLines = sourceLines,
                    lineIndex = lineIndex,
                    targetLines = targetLines,
                ) ?: throw IllegalStateException("Invalid todo line")

                writeLinesInternal(targetUri, updated.second)
            }
        }.onSuccess {
            clearUndoBackups()
            CategoriesWidgetUpdater.requestUpdate(appContext)
        }
    }

    override fun hasPersistedReadWritePermission(uri: Uri): Boolean {
        val perms = appContext.contentResolver.persistedUriPermissions
        val perm = perms.firstOrNull { it.uri == uri } ?: return false
        return perm.isReadPermission && perm.isWritePermission
    }

    override suspend fun countMarkdownFiles(folderUri: Uri): Result<Int> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val folder = DocumentFile.fromTreeUri(appContext, folderUri)
                    ?: throw IllegalStateException("Invalid folder URI")

                folder.listFiles().count { file ->
                    if (!file.isFile) return@count false
                    val name = file.name.orEmpty()
                    name.endsWith(".md", ignoreCase = true) && !name.contains("sync-conflict", ignoreCase = true)
                }
            }
        }
    }

    override fun observeMarkdownFilesChanges(folderUri: Uri): Flow<Unit> {
        val treeDocId = DocumentsContract.getTreeDocumentId(folderUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, treeDocId)

        return callbackFlow {
            val resolver = appContext.contentResolver

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var updateJob = scope.launch { }
            updateJob.cancel()

            var observedUris: List<Pair<Uri, Boolean>> = emptyList()

            lateinit var observer: ContentObserver

            fun currentMarkdownUris(): List<Pair<Uri, Boolean>> {
                val folder = DocumentFile.fromTreeUri(appContext, folderUri) ?: return emptyList()
                val fileUris = folder.listFiles()
                    .asSequence()
                    .filter { it.isFile }
                    .filter {
                        val name = it.name.orEmpty()
                        name.endsWith(".md", ignoreCase = true) && !name.contains("sync-conflict", ignoreCase = true)
                    }
                    .map { it.uri }
                    .toList()

                val uris = mutableListOf<Pair<Uri, Boolean>>()
                // structural changes
                uris.add(folderUri to true)
                uris.add(childrenUri to true)
                // content changes
                fileUris.forEach { uris.add(it to false) }
                return uris
            }

            fun scheduleUpdate() {
                updateJob.cancel()
                updateJob = scope.launch {
                    delay(200)
                    val next = runCatching { currentMarkdownUris() }.getOrElse { emptyList() }
                    if (next == observedUris) return@launch

                    runCatching { resolver.unregisterContentObserver(observer) }
                    observedUris = next
                    runCatching {
                        observedUris.forEach { (uri, notifyDescendants) ->
                            resolver.registerContentObserver(uri, notifyDescendants, observer)
                        }
                    }.onFailure { t ->
                        close(t)
                    }
                }
            }

            observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, changedUri: Uri?) {
                    trySend(Unit)
                    val changed = changedUri?.toString().orEmpty()
                    val shouldUpdate = changedUri == null ||
                        changedUri == folderUri ||
                        changedUri == childrenUri ||
                        (changed.isNotEmpty() && changed.startsWith(childrenUri.toString()))
                    if (shouldUpdate) scheduleUpdate()
                }
            }

            // initial register
            observedUris = listOf(
                folderUri to true,
                childrenUri to true,
            )
            runCatching {
                observedUris.forEach { (uri, notifyDescendants) ->
                    resolver.registerContentObserver(uri, notifyDescendants, observer)
                }
            }.onFailure { t ->
                close(t)
                return@callbackFlow
            }

            scheduleUpdate()

            awaitClose {
                updateJob.cancel()
                scope.cancel()
                runCatching { resolver.unregisterContentObserver(observer) }
            }
        }.conflate()
    }

    private fun readLinesInternal(uri: Uri): List<String> {
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            return input.bufferedReader().readLines()
        }
        throw IllegalStateException("Unable to open input stream")
    }

    private fun lastModifiedInternal(uri: Uri): Long {
        val fromQuery = appContext.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
        if (fromQuery != null) return fromQuery

        val file = DocumentFile.fromSingleUri(appContext, uri)
            ?: throw IllegalStateException("Invalid file URI")
        return file.lastModified()
    }

    private fun writeLinesInternal(uri: Uri, lines: List<String>) {
        appContext.contentResolver.openOutputStream(uri, "rwt")?.use { out ->
            out.bufferedWriter().use { writer ->
                lines.forEachIndexed { index, line ->
                    if (index > 0) writer.newLine()
                    writer.write(line)
                }
            }
        } ?: throw IllegalStateException("Unable to open output stream")
    }
}
