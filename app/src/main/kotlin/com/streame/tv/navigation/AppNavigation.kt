package com.streame.tv.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.streame.tv.data.model.Category
import com.streame.tv.data.model.MediaItem
import com.streame.tv.data.model.MediaType
import com.streame.tv.data.model.Profile
import com.streame.tv.data.repository.AuthState
import com.streame.tv.ui.screens.details.DetailsScreen
import com.streame.tv.ui.screens.home.HomeScreen
import com.streame.tv.ui.screens.login.LoginScreen
import com.streame.tv.ui.screens.account.AuthQrSignInScreen
import com.streame.tv.ui.screens.account.AuthEmailSignInScreen
import com.streame.tv.ui.screens.player.PlayerScreen
import com.streame.tv.ui.screens.collections.CollectionDetailsScreen
import com.streame.tv.ui.screens.search.SearchScreen
import com.streame.tv.ui.screens.settings.SettingsScreen
import com.streame.tv.ui.screens.watchlist.WatchlistScreen
import com.streame.tv.ui.screens.profile.ProfileSelectionScreen
import com.streame.tv.util.LocalDeviceType

/**
 * Navigation destinations
 */
sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Home : Screen("home")
    object Search : Screen("search")
    object Watchlist : Screen("watchlist")
    object CollectionDetails : Screen("collections/{catalogId}") {
        fun createRoute(catalogId: String): String {
            return "collections/${java.net.URLEncoder.encode(catalogId, "UTF-8")}" 
        }
    }
    object Settings : Screen("settings")
    object ProfileSelection : Screen("profile_selection")
    object SupabaseQrLogin : Screen("supabase_qr_login")
    object SupabaseEmailLogin : Screen("supabase_email_login")
    
    object Details : Screen("details/{mediaType}/{mediaId}?initialSeason={initialSeason}&initialEpisode={initialEpisode}") {
        fun createRoute(
            mediaType: MediaType,
            mediaId: Int,
            initialSeason: Int? = null,
            initialEpisode: Int? = null
        ): String {
            val base = "details/${mediaType.name.lowercase()}/$mediaId"
            val params = mutableListOf<String>()
            initialSeason?.let { params.add("initialSeason=$it") }
            initialEpisode?.let { params.add("initialEpisode=$it") }
            return if (params.isNotEmpty()) "$base?${params.joinToString("&")}" else base
        }
    }
    
    object Player : Screen("player/{playbackId}") {
        /**
         * Create a Player route by storing all playback parameters
         * in [PlaybackParamsStore] and returning a route with just the playbackId.
         * This avoids 10+ URL-encoded parameters in the navigation route.
         */
        fun createRoute(
            mediaType: MediaType,
            mediaId: Int,
            seasonNumber: Int? = null,
            episodeNumber: Int? = null,
            imdbId: String? = null,
            streamUrl: String? = null,
            preferredAddonId: String? = null,
            preferredSourceName: String? = null,
            preferredBingeGroup: String? = null,
            startPositionMs: Long? = null
        ): String {
            val params = PlaybackParams(
                mediaType = mediaType,
                mediaId = mediaId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                imdbId = imdbId,
                streamUrl = streamUrl,
                preferredAddonId = preferredAddonId,
                preferredSourceName = preferredSourceName,
                preferredBingeGroup = preferredBingeGroup,
                startPositionMs = startPositionMs
            )
            val playbackId = PlaybackParamsStore.put(params)
            return "player/$playbackId"
        }
    }
}

/**
 * Main navigation graph
 */
@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Login.route,
    preloadedCategories: List<Category> = emptyList(),
    preloadedHeroItem: MediaItem? = null,
    preloadedHeroLogoUrl: String? = null,
    preloadedLogoCache: Map<String, String> = emptyMap(),
    currentProfile: Profile? = null,
    onSwitchProfile: () -> Unit = {},
    onExitApp: () -> Unit = {}
) {
    val navigateTopLevel: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(Screen.Home.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val navigateHome: () -> Unit = {
        // Navigate to Home clearing the entire back stack above it.
        // Uses navigate() instead of popBackStack() because popBackStack can
        // silently fail if Home is not found, and restoreState on other
        // navigateTopLevel calls can bring back stale Details pages.
        navController.navigate(Screen.Home.route) {
            popUpTo(Screen.Home.route) { inclusive = true; saveState = false }
            launchSingleTop = true
            restoreState = false
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        // Premium screen transitions — subtle fade + slight depth push.
        // Netflix TV uses ~250ms fade; this is tuned for Android TV's 60fps.
        // Pure crossfade — no horizontal slides (those feel mobile, not TV).
        // Netflix TV uses ~250ms crossfade for all screen transitions.
        enterTransition = { fadeIn(androidx.compose.animation.core.tween(250)) },
        exitTransition = { fadeOut(androidx.compose.animation.core.tween(200)) },
        popEnterTransition = { fadeIn(androidx.compose.animation.core.tween(250)) },
        popExitTransition = { fadeOut(androidx.compose.animation.core.tween(200)) }
    ) {
        // Login screen (Trakt)
        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }

        // Supabase QR sign-in
        composable(Screen.SupabaseQrLogin.route) {
            AuthQrSignInScreen(
                onBackPress = { navController.popBackStack() },
                onContinue = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.SupabaseQrLogin.route) { inclusive = true }
                    }
                },
                onEmailSignIn = {
                    navController.navigate(Screen.SupabaseEmailLogin.route)
                }
            )
        }

        // Supabase email/password sign-in
        composable(Screen.SupabaseEmailLogin.route) {
            AuthEmailSignInScreen(
                onBackPress = { navController.popBackStack() },
                onSignInSuccess = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.SupabaseEmailLogin.route) { inclusive = true }
                    }
                }
            )
        }
        
        // Home screen
        composable(Screen.Home.route) {
            HomeScreen(
                preloadedCategories = preloadedCategories,
                preloadedHeroItem = preloadedHeroItem,
                preloadedHeroLogoUrl = preloadedHeroLogoUrl,
                preloadedLogoCache = preloadedLogoCache,
                currentProfile = currentProfile,
                onNavigateToDetails = { mediaType, mediaId, initialSeason, initialEpisode ->
                    navController.navigate(Screen.Details.createRoute(mediaType, mediaId, initialSeason, initialEpisode))
                },
                onNavigateToCollection = { catalogId ->
                    navController.navigate(Screen.CollectionDetails.createRoute(catalogId))
                },
                onNavigateToPlayer = { type, id, season, episode, imdbId, url, preferredAddonId, preferredSourceName, preferredBingeGroup, startPositionMs ->
                    navController.navigate(
                        Screen.Player.createRoute(
                            mediaType = type,
                            mediaId = id,
                            seasonNumber = season,
                            episodeNumber = episode,
                            imdbId = imdbId,
                            streamUrl = url,
                            preferredAddonId = preferredAddonId,
                            preferredSourceName = preferredSourceName,
                            preferredBingeGroup = preferredBingeGroup,
                            startPositionMs = startPositionMs
                        )
                    )
                },
                onNavigateToSearch = {
                    navigateTopLevel(Screen.Search.route)
                },
                onNavigateToWatchlist = {
                    navigateTopLevel(Screen.Watchlist.route)
                },
                onNavigateToSettings = {
                    navigateTopLevel(Screen.Settings.route)
                },
                onSwitchProfile = {
                    onSwitchProfile()
                    navController.navigate(Screen.ProfileSelection.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
                onExitApp = onExitApp
            )
        }
        
        // Search screen
        composable(Screen.Search.route) {
            SearchScreen(
                currentProfile = currentProfile,
                onNavigateToDetails = { mediaType, mediaId ->
                    navController.navigate(Screen.Details.createRoute(mediaType, mediaId))
                },
                onNavigateToHome = { navigateHome() },
                onNavigateToWatchlist = { navigateTopLevel(Screen.Watchlist.route) },
                onNavigateToSettings = { navigateTopLevel(Screen.Settings.route) },
                onSwitchProfile = {
                    onSwitchProfile()
                    navController.navigate(Screen.ProfileSelection.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        // Watchlist screen
        composable(Screen.Watchlist.route) {
            WatchlistScreen(
                currentProfile = currentProfile,
                onNavigateToDetails = { mediaType, mediaId ->
                    navController.navigate(Screen.Details.createRoute(mediaType, mediaId))
                },
                onNavigateToHome = { navigateHome() },
                onNavigateToSearch = { navigateTopLevel(Screen.Search.route) },
                onNavigateToSettings = { navigateTopLevel(Screen.Settings.route) },
                onSwitchProfile = {
                    onSwitchProfile()
                    navController.navigate(Screen.ProfileSelection.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        // Settings screen
        composable(
            route = "settings"
        ) {
            SettingsScreen(
                currentProfile = currentProfile,
                onNavigateToHome = { navigateHome() },
                onNavigateToSearch = { navigateTopLevel(Screen.Search.route) },
                onNavigateToWatchlist = { navigateTopLevel(Screen.Watchlist.route) },
                onSwitchProfile = {
                    onSwitchProfile()
                    navController.navigate(Screen.ProfileSelection.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
                navController = navController
            )
        }

        // Profile selection screen
        composable(Screen.ProfileSelection.route) {
            val isTouchDevice = com.streame.tv.util.LocalDeviceType.current.isTouchDevice()
            ProfileSelectionScreen(
                onProfileSelected = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.ProfileSelection.route) { inclusive = true }
                    }
                },
                onShowAddProfile = { /* Handled internally by ProfileSelectionScreen */ }
            )
        }

        // Details screen
        composable(
            route = Screen.CollectionDetails.route,
            arguments = listOf(navArgument("catalogId") { type = NavType.StringType })
        ) { backStackEntry ->
            val catalogId = backStackEntry.arguments?.getString("catalogId").orEmpty()
            if (catalogId.isBlank()) {
                navigateHome()
                return@composable
            }
            CollectionDetailsScreen(
                catalogId = catalogId,
                currentProfile = currentProfile,
                onNavigateToDetails = { mediaType, mediaId ->
                    navController.navigate(Screen.Details.createRoute(mediaType, mediaId))
                },
                onNavigateToHome = { navigateHome() },
                onNavigateToSearch = { navigateTopLevel(Screen.Search.route) },
                onNavigateToWatchlist = { navigateTopLevel(Screen.Watchlist.route) },
                onNavigateToSettings = { navigateTopLevel(Screen.Settings.route) },
                onBack = { navController.popBackStack() }
            )
        }

        // Details screen
        composable(
            route = Screen.Details.route,
            arguments = listOf(
                navArgument("mediaType") { type = NavType.StringType },
                navArgument("mediaId") { type = NavType.IntType },
                navArgument("initialSeason") {
                    type = NavType.IntType
                    defaultValue = -1
                },
                navArgument("initialEpisode") {
                    type = NavType.IntType
                    defaultValue = -1
                }
            )
        ) { backStackEntry ->
            val mediaTypeStr = backStackEntry.arguments?.getString("mediaType") ?: "movie"
            val mediaId = backStackEntry.arguments?.getInt("mediaId") ?: 0
            if (mediaId <= 0) {
                navigateHome()
                return@composable
            }
            val initialSeason = backStackEntry.arguments?.getInt("initialSeason")?.takeIf { it >= 0 }
            val initialEpisode = backStackEntry.arguments?.getInt("initialEpisode")?.takeIf { it >= 0 }
            val mediaType = if (mediaTypeStr == "tv") MediaType.TV else if (mediaTypeStr == "movie") MediaType.MOVIE else MediaType.MOVIE

            DetailsScreen(
                mediaType = mediaType,
                mediaId = mediaId,
                initialSeason = initialSeason,
                initialEpisode = initialEpisode,
                currentProfile = currentProfile,
                onNavigateToPlayer = { type, id, season, episode, imdbId, url, preferredAddonId, preferredSourceName, preferredBingeGroup, startPositionMs ->
                    navController.navigate(
                        Screen.Player.createRoute(
                            mediaType = type,
                            mediaId = id,
                            seasonNumber = season,
                            episodeNumber = episode,
                            imdbId = imdbId,
                            streamUrl = url,
                            preferredAddonId = preferredAddonId,
                            preferredSourceName = preferredSourceName,
                            preferredBingeGroup = preferredBingeGroup,
                            startPositionMs = startPositionMs
                        )
                    )
                },
                onNavigateToDetails = { type, id ->
                    navController.navigate(Screen.Details.createRoute(type, id))
                },
                onNavigateToHome = {
                    navigateHome()
                },
                onNavigateToSearch = {
                    navigateTopLevel(Screen.Search.route)
                },
                onNavigateToWatchlist = {
                    navigateTopLevel(Screen.Watchlist.route)
                },
                onNavigateToSettings = {
                    navigateTopLevel(Screen.Settings.route)
                },
                onSwitchProfile = {
                    onSwitchProfile()
                    navController.navigate(Screen.ProfileSelection.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
        
        // Player screen
        composable(
            route = Screen.Player.route,
            arguments = listOf(
                navArgument("playbackId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val playbackId = backStackEntry.arguments?.getString("playbackId") ?: ""
            val params = remember { PlaybackParamsStore.get(playbackId) }
            DisposableEffect(playbackId) {
                onDispose { PlaybackParamsStore.removeOnDispose(playbackId) }
            }

            if (params != null) {
                PlayerScreen(
                    mediaType = params.mediaType,
                    mediaId = params.mediaId,
                    seasonNumber = params.seasonNumber,
                    episodeNumber = params.episodeNumber,
                    imdbId = params.imdbId,
                    streamUrl = params.streamUrl,
                    preferredAddonId = params.preferredAddonId,
                    preferredSourceName = params.preferredSourceName,
                    preferredBingeGroup = params.preferredBingeGroup,
                    startPositionMs = params.startPositionMs,
                    onBack = { navController.popBackStack() },
                    onPlayNext = { nextSeason, nextEpisode, nextPreferredAddonId, nextPreferredSourceName, nextPreferredBingeGroup ->
                        // Navigate to next episode
                        navController.navigate(
                            Screen.Player.createRoute(
                                mediaType = params.mediaType,
                                mediaId = params.mediaId,
                                seasonNumber = nextSeason,
                                episodeNumber = nextEpisode,
                                preferredAddonId = nextPreferredAddonId,
                                preferredSourceName = nextPreferredSourceName,
                                preferredBingeGroup = nextPreferredBingeGroup
                            )
                        ) {
                            popUpTo(Screen.Player.route) { inclusive = true }
                        }
                    }
                )
            } else {
                // Params lost (process death) — go back to home
                navController.popBackStack()
            }
        }
    }
}
