package com.isotjs.todosian

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.isotjs.todosian.data.settings.ThemeMode
import com.isotjs.todosian.ui.theme.TodosianTheme
import com.isotjs.todosian.widget.TodoWidgetConstants
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val pendingDeepLink = MutableStateFlow<WidgetDeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeWidgetIntent(intent)

        val fileRepository = (application as TodosianApplication).fileRepository
        val appSettingsRepository = (application as TodosianApplication).appSettingsRepository
        setContent {
            val settings = appSettingsRepository.settings.collectAsStateWithLifecycle(
                initialValue = com.isotjs.todosian.data.settings.AppSettings(),
            ).value

            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            val deepLink by pendingDeepLink.collectAsStateWithLifecycle()

            TodosianTheme(
                darkTheme = darkTheme,
                dynamicColor = settings.dynamicColorEnabled,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    TodosianApp(
                        fileRepository = fileRepository,
                        appSettingsRepository = appSettingsRepository,
                        pendingDeepLink = deepLink,
                        onDeepLinkHandled = {
                            pendingDeepLink.value = null
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeWidgetIntent(intent)
    }

    private fun consumeWidgetIntent(intent: Intent?) {
        if (intent == null) return
        val uriString = intent.getStringExtra(TodoWidgetConstants.EXTRA_OPEN_CATEGORY_URI)
        if (uriString.isNullOrBlank()) return

        val openAddTodo = intent.getBooleanExtra(TodoWidgetConstants.EXTRA_OPEN_ADD_TODO, false)
        val addRequestId = intent.getLongExtra(TodoWidgetConstants.EXTRA_ADD_REQUEST_ID, 0L)
        val editLineIndex = intent.getIntExtra(TodoWidgetConstants.EXTRA_OPEN_EDIT_LINE_INDEX, -1)
        val editRequestId = intent.getLongExtra(TodoWidgetConstants.EXTRA_EDIT_REQUEST_ID, 0L)

        // Clear extras so rotation / recreate does not re-trigger the sheet.
        intent.removeExtra(TodoWidgetConstants.EXTRA_OPEN_CATEGORY_URI)
        intent.removeExtra(TodoWidgetConstants.EXTRA_OPEN_ADD_TODO)
        intent.removeExtra(TodoWidgetConstants.EXTRA_ADD_REQUEST_ID)
        intent.removeExtra(TodoWidgetConstants.EXTRA_OPEN_EDIT_LINE_INDEX)
        intent.removeExtra(TodoWidgetConstants.EXTRA_EDIT_REQUEST_ID)

        val uri = runCatching { uriString.toUri() }.getOrNull() ?: return
        pendingDeepLink.value = WidgetDeepLink(
            categoryUri = uri,
            openAddTodo = openAddTodo,
            addRequestId = addRequestId,
            openEditLineIndex = editLineIndex.takeIf { it >= 0 },
            editRequestId = editRequestId,
        )
    }
}

data class WidgetDeepLink(
    val categoryUri: Uri,
    val openAddTodo: Boolean = false,
    val addRequestId: Long = 0L,
    val openEditLineIndex: Int? = null,
    val editRequestId: Long = 0L,
)
