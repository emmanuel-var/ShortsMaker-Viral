package com.shortsmaker.viral.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import com.shortsmaker.viral.ads.BannerAd
import com.shortsmaker.viral.ui.common.container
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

/**
 * Raíz de la app. El banner (AdMob) se muestra SÓLO en pantallas de lectura/lista (inicio, ajustes y sugerencias),
 * abajo y separado del contenido. Nunca en el editor, la exportación, la importación ni el procesamiento,
 * donde hay botones y gestos cerca y podrían provocar toques accidentales (política de AdMob).
 */
@Composable
fun AppNavigation() {
    val nav = rememberNavController()
    val ads = LocalContext.current.container.ads
    val canShowAds by ads.canShowAds.collectAsStateWithLifecycle()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val routeAllowsBanner = route == Routes.HOME || route == Routes.SETTINGS || route == Routes.SUGGESTIONS
    var bannerLoaded by remember { mutableStateOf(false) }
    val showBanner = canShowAds && routeAllowsBanner

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Si el banner ocupa la parte inferior, él se encarga de la barra de navegación del sistema.
        val consume = if (showBanner && bannerLoaded) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier
        Box(Modifier.weight(1f).then(consume)) { AppNavHost(nav) }
        if (showBanner) {
            Box(
                Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
                    .then(if (bannerLoaded) Modifier.navigationBarsPadding() else Modifier),
            ) {
                BannerAd(onLoadedChange = { bannerLoaded = it })
            }
        }
    }
}

@Composable
private fun AppNavHost(nav: androidx.navigation.NavHostController) {
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
