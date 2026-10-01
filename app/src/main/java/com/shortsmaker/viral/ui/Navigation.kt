package com.shortsmaker.viral.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.shortsmaker.viral.ui.editor.EditorScreen
import com.shortsmaker.viral.ui.export.ExportScreen
import com.shortsmaker.viral.ui.home.HomeScreen
import com.shortsmaker.viral.ui.importer.ImportScreen
import com.shortsmaker.viral.ui.processing.ProcessingScreen
import com.shortsmaker.viral.ui.settings.SettingsScreen
import com.shortsmaker.viral.ui.suggestions.SuggestionsScreen

private object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val IMPORT = "import"
    const val PROCESSING = "processing/{id}"
    const val SUGGESTIONS = "suggestions/{id}"
    const val EDITOR = "editor/{id}/{clip}"
    const val EXPORT = "export/{id}/{clip}"

    fun processing(id: String) = "processing/$id"
    fun suggestions(id: String) = "suggestions/$id"
    fun editor(id: String, clip: String) = "editor/$id/$clip"
    fun export(id: String, clip: String) = "export/$id/$clip"
}

private val idArg = navArgument("id") { type = NavType.StringType }
private val clipArg = navArgument("clip") { type = NavType.StringType }

@Composable
fun AppNavigation() {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onCreate = { nav.navigate(Routes.IMPORT) },
                onOpenProject = { nav.navigate(Routes.suggestions(it)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.IMPORT) {
            ImportScreen(onBack = { nav.popBackStack() }, onStart = { id -> nav.navigate(Routes.processing(id)) })
        }
        composable(Routes.PROCESSING, arguments = listOf(idArg)) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            ProcessingScreen(
                projectId = id,
                onDone = {
                    nav.navigate(Routes.suggestions(id)) { popUpTo(Routes.HOME) }
                },
                onExit = { nav.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
        composable(Routes.SUGGESTIONS, arguments = listOf(idArg)) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            SuggestionsScreen(
                projectId = id,
                onBack = { nav.popBackStack() },
                onEdit = { clip -> nav.navigate(Routes.editor(id, clip)) },
            )
        }
        composable(Routes.EDITOR, arguments = listOf(idArg, clipArg)) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val clip = entry.arguments?.getString("clip").orEmpty()
            EditorScreen(
                projectId = id,
                clipId = clip,
                onBack = { nav.popBackStack() },
                onExport = { nav.navigate(Routes.export(id, clip)) },
            )
        }
        composable(Routes.EXPORT, arguments = listOf(idArg, clipArg)) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val clip = entry.arguments?.getString("clip").orEmpty()
            ExportScreen(
                projectId = id,
                clipId = clip,
                onBack = { nav.popBackStack() },
                onHome = { nav.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
    }
}
