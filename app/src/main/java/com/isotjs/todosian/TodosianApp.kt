package com.isotjs.todosian

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.data.settings.AppSettingsRepository
import com.isotjs.todosian.ui.category.CategoryScreen
import com.isotjs.todosian.ui.dailyfocus.DailyFocusScreen
import com.isotjs.todosian.ui.home.HomeScreen
import com.isotjs.todosian.ui.onboarding.OnboardingScreen
import com.isotjs.todosian.ui.settings.SettingsScreen

@Composable
fun TodosianApp(
    fileRepository: FileRepository,
    appSettingsRepository: AppSettingsRepository,
    pendingDeepLink: WidgetDeepLink? = null,
    onDeepLinkHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    val startDestination = remember(fileRepository) {
        if (fileRepository.getFolderUri() == null) Routes.Onboarding else Routes.Home
    }

    LaunchedEffect(pendingDeepLink) {
        val link = pendingDeepLink ?: return@LaunchedEffect
        if (fileRepository.getFolderUri() == null) {
            onDeepLinkHandled()
            return@LaunchedEffect
        }

        navController.navigate(
            Routes.category(
                uri = link.categoryUri,
                openAddTodo = link.openAddTodo,
                addRequestId = link.addRequestId,
                openEditLineIndex = link.openEditLineIndex ?: -1,
                editRequestId = link.editRequestId,
            ),
        ) {
            // Ensure we leave any existing category entry so sheet deep links apply fresh.
            popUpTo(Routes.Home) { inclusive = false }
            launchSingleTop = true
        }
        onDeepLinkHandled()
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier,
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(300),
            ) + fadeIn(animationSpec = tween(150))
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { -it / 4 },
                animationSpec = tween(300),
            ) + fadeOut(animationSpec = tween(150))
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { -it / 4 },
                animationSpec = tween(300),
            ) + fadeIn(animationSpec = tween(150))
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(300),
            ) + fadeOut(animationSpec = tween(150))
        },
    ) {
        composable(Routes.Onboarding) {
            OnboardingScreen(
                fileRepository = fileRepository,
                onFinished = {
                    navController.navigate(Routes.Home) {
                        popUpTo(Routes.Onboarding) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.Home) { backStackEntry ->
            val refreshSignal = backStackEntry.savedStateHandle
                .getStateFlow(KEY_REFRESH_HOME, 0L)
                .collectAsStateWithLifecycle()
                .value

            HomeScreen(
                fileRepository = fileRepository,
                appSettingsRepository = appSettingsRepository,
                onOpenCategory = { uri ->
                    navController.navigate(Routes.category(uri))
                },
                onOpenSettings = {
                    navController.navigate(Routes.Settings)
                },
                onOpenDailyFocus = {
                    navController.navigate(Routes.DailyFocus)
                },
                refreshSignal = refreshSignal,
                onRequireOnboarding = {
                    navController.navigate(Routes.Onboarding) {
                        popUpTo(Routes.Home) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.Settings) {
            SettingsScreen(
                fileRepository = fileRepository,
                appSettingsRepository = appSettingsRepository,
                onBack = {
                    navController.previousBackStackEntry?.savedStateHandle?.set(
                        KEY_REFRESH_HOME,
                        System.currentTimeMillis(),
                    )
                    navController.popBackStack()
                },
                onRequireOnboarding = {
                    navController.navigate(Routes.Onboarding) {
                        popUpTo(Routes.Home) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Routes.Category,
            arguments = listOf(
                navArgument(Routes.ARG_CATEGORY_URI) {
                    type = NavType.StringType
                },
                navArgument(Routes.ARG_OPEN_ADD_TODO) {
                    type = NavType.BoolType
                    defaultValue = false
                },
                navArgument(Routes.ARG_ADD_REQUEST_ID) {
                    type = NavType.LongType
                    defaultValue = 0L
                },
                navArgument(Routes.ARG_OPEN_EDIT_LINE_INDEX) {
                    type = NavType.IntType
                    defaultValue = -1
                },
                navArgument(Routes.ARG_EDIT_REQUEST_ID) {
                    type = NavType.LongType
                    defaultValue = 0L
                },
            ),
        ) { backStackEntry ->
            val encoded = backStackEntry.arguments?.getString(Routes.ARG_CATEGORY_URI).orEmpty()
            val openAddTodo = backStackEntry.arguments?.getBoolean(Routes.ARG_OPEN_ADD_TODO) == true
            val addRequestId = backStackEntry.arguments?.getLong(Routes.ARG_ADD_REQUEST_ID) ?: 0L
            val openEditLineIndex =
                backStackEntry.arguments?.getInt(Routes.ARG_OPEN_EDIT_LINE_INDEX) ?: -1
            val editRequestId = backStackEntry.arguments?.getLong(Routes.ARG_EDIT_REQUEST_ID) ?: 0L
            val uri = encoded.toUri()
            CategoryScreen(
                fileRepository = fileRepository,
                appSettingsRepository = appSettingsRepository,
                categoryUri = uri,
                openAddTodo = openAddTodo,
                addRequestId = addRequestId,
                openEditLineIndex = openEditLineIndex,
                editRequestId = editRequestId,
                onBack = {
                    navController.previousBackStackEntry?.savedStateHandle?.set(
                        KEY_REFRESH_HOME,
                        System.currentTimeMillis(),
                    )
                    navController.popBackStack()
                },
            )
        }

        composable(Routes.DailyFocus) {
            DailyFocusScreen(
                fileRepository = fileRepository,
                appSettingsRepository = appSettingsRepository,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

private const val KEY_REFRESH_HOME = "refresh_home"

object Routes {
    const val Onboarding = "onboarding"
    const val Home = "home"
    const val Settings = "settings"
    const val DailyFocus = "daily_focus"

    const val ARG_CATEGORY_URI = "categoryUri"
    const val ARG_OPEN_ADD_TODO = "openAddTodo"
    const val ARG_ADD_REQUEST_ID = "addRequestId"
    const val ARG_OPEN_EDIT_LINE_INDEX = "openEditLineIndex"
    const val ARG_EDIT_REQUEST_ID = "editRequestId"
    const val Category =
        "category/{$ARG_CATEGORY_URI}" +
            "?$ARG_OPEN_ADD_TODO={$ARG_OPEN_ADD_TODO}" +
            "&$ARG_ADD_REQUEST_ID={$ARG_ADD_REQUEST_ID}" +
            "&$ARG_OPEN_EDIT_LINE_INDEX={$ARG_OPEN_EDIT_LINE_INDEX}" +
            "&$ARG_EDIT_REQUEST_ID={$ARG_EDIT_REQUEST_ID}"

    fun category(
        uri: Uri,
        openAddTodo: Boolean = false,
        addRequestId: Long = 0L,
        openEditLineIndex: Int = -1,
        editRequestId: Long = 0L,
    ): String {
        return "category/${Uri.encode(uri.toString())}" +
            "?$ARG_OPEN_ADD_TODO=$openAddTodo" +
            "&$ARG_ADD_REQUEST_ID=$addRequestId" +
            "&$ARG_OPEN_EDIT_LINE_INDEX=$openEditLineIndex" +
            "&$ARG_EDIT_REQUEST_ID=$editRequestId"
    }
}
