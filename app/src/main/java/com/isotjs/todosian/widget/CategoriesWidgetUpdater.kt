package com.isotjs.todosian.widget

import android.content.Context
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the checklist widget in sync with vault changes (in-app writes and external editors).
 *
 * SAF ContentObservers are unreliable for many providers, so we also poll the selected
 * category file's last-modified timestamp while the process is alive, and schedule a
 * periodic WorkManager refresh for when it is not.
 *
 * Glance runs each widget composition as a unique [androidx.work] SessionWorker. Enqueueing
 * a second update for the same widget cancels the first mid-flight. Widget ActionCallbacks
 * that also write files must [suppressExternalUpdates]: [com.isotjs.todosian.data.FileRepository]
 * calls [requestUpdate] after every write, which would otherwise cancel the action's own
 * [scheduleUpdateAfterAction] SessionWorker.
 */
object CategoriesWidgetUpdater {
    private const val POLL_INTERVAL_MS = 15_000L
    private const val DEFAULT_SUPPRESS_MS = 2_000L
    private const val POST_ACTION_UPDATE_DELAY_MS = 120L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var updateJob: Job? = null
    private var postActionUpdateJob: Job? = null
    private var observeJob: Job? = null
    private var pollJob: Job? = null
    private var appContextRef: Context? = null

    @Volatile
    private var suppressUntilElapsedRealtime = 0L

    /**
     * Ignores [requestUpdate] for [durationMs] and cancels any pending one.
     *
     * Needed when an ActionCallback both writes via FileRepository (which always
     * [requestUpdate]s) and schedules its own Glance update — without this, the
     * write-triggered refresh cancels the action's SessionWorker mid-flight.
     */
    fun suppressExternalUpdates(durationMs: Long = DEFAULT_SUPPRESS_MS) {
        updateJob?.cancel()
        updateJob = null
        val until = SystemClock.elapsedRealtime() + durationMs
        if (until > suppressUntilElapsedRealtime) {
            suppressUntilElapsedRealtime = until
        }
    }

    /**
     * Runs [CategoriesWidget.update] shortly after an ActionCallback returns.
     * Calling update() inside the action races the live Glance session.
     */
    fun scheduleUpdateAfterAction(context: Context, glanceId: androidx.glance.GlanceId) {
        val appContext = context.applicationContext
        postActionUpdateJob?.cancel()
        postActionUpdateJob = scope.launch {
            delay(POST_ACTION_UPDATE_DELAY_MS)
            runCatching {
                CategoriesWidget().update(appContext, glanceId)
            }
        }
    }

    fun requestUpdate(context: Context) {
        if (SystemClock.elapsedRealtime() < suppressUntilElapsedRealtime) {
            return
        }

        val appContext = context.applicationContext
        updateJob?.cancel()
        updateJob = scope.launch {
            // Brief delay so SAF providers finish flushing before we re-read.
            delay(300)
            if (SystemClock.elapsedRealtime() < suppressUntilElapsedRealtime) {
                return@launch
            }
            publishAndUpdateAll(appContext)
        }
    }

    /**
     * Reload list content into each widget's Glance state, then update once per instance.
     * Safe to call outside ActionCallbacks (in-app edits, WorkManager).
     */
    suspend fun publishAndUpdateAll(context: Context) {
        val appContext = context.applicationContext
        val state = CategoriesWidgetContent.load(appContext)
        WidgetContentRepository.publish(state)
        val glanceIds = runCatching {
            GlanceAppWidgetManager(appContext).getGlanceIds(CategoriesWidget::class.java)
        }.getOrDefault(emptyList())

        glanceIds.forEach { glanceId ->
            runCatching {
                updateAppWidgetState(appContext, glanceId) { prefs ->
                    CategoriesWidgetContent.write(prefs, state)
                }
                CategoriesWidget().update(appContext, glanceId)
            }
        }
    }

    fun startObserving(
        context: Context,
        fileRepository: FileRepository,
        preferencesManager: PreferencesManager,
    ) {
        val appContext = context.applicationContext
        appContextRef = appContext
        WidgetRefreshScheduler.enqueue(appContext)

        if (observeJob?.isActive != true) {
            observeJob = scope.launch {
                while (isActive) {
                    val folderUri = fileRepository.getFolderUri()
                    if (folderUri == null) {
                        delay(5_000)
                        continue
                    }
                    try {
                        @OptIn(FlowPreview::class)
                        fileRepository.observeMarkdownFilesChanges(folderUri)
                            .debounce(400)
                            .collect {
                                requestUpdate(appContext)
                            }
                    } catch (_: Exception) {
                        delay(5_000)
                    }
                }
            }
        }

        if (pollJob?.isActive != true) {
            pollJob = scope.launch {
                var lastUri: String? = null
                var lastModified: Long? = null
                while (isActive) {
                    val selectedUri = preferencesManager.getWidgetCategoryUri()
                    if (selectedUri.isNullOrBlank()) {
                        lastUri = null
                        lastModified = null
                        delay(POLL_INTERVAL_MS)
                        continue
                    }

                    val modified = runCatching {
                        fileRepository.getLastModified(selectedUri.toUri()).getOrNull()
                    }.getOrNull()

                    if (
                        modified != null &&
                        selectedUri == lastUri &&
                        lastModified != null &&
                        modified != lastModified
                    ) {
                        requestUpdate(appContext)
                    }

                    lastUri = selectedUri
                    lastModified = modified
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }

    fun stopObserving() {
        observeJob?.cancel()
        pollJob?.cancel()
        observeJob = null
        pollJob = null
        appContextRef?.let { WidgetRefreshScheduler.cancel(it) }
        appContextRef = null
    }

    fun startObservingIfWidgetsExist(
        context: Context,
        fileRepository: FileRepository,
        preferencesManager: PreferencesManager,
    ) {
        val appContext = context.applicationContext
        scope.launch {
            val hasWidgets = runCatching {
                GlanceAppWidgetManager(appContext)
                    .getGlanceIds(CategoriesWidget::class.java)
                    .isNotEmpty()
            }.getOrDefault(false)
            if (hasWidgets) {
                startObserving(appContext, fileRepository, preferencesManager)
            }
        }
    }
}
