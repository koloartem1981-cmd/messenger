package com.devin.messenger.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.devin.messenger.data.Api
import com.devin.messenger.data.RealtimeBus
import com.devin.messenger.data.SessionStore
import com.devin.messenger.data.UserPublic
import com.devin.messenger.ui.auth.AuthScreen
import com.devin.messenger.ui.chat.ChatScreen
import com.devin.messenger.ui.home.HomeScreen
import com.devin.messenger.ui.profile.ProfileScreen
import com.devin.messenger.ui.search.SearchScreen

object Routes {
    const val AUTH = "auth"
    const val HOME = "home"
    const val SEARCH = "search"
    const val PROFILE = "profile"
    const val CHAT = "chat/{peerId}/{peerName}/{peerUsername}/{peerAvatar}"

    fun chat(peerId: Long, peerName: String, peerUsername: String, peerAvatar: String?): String {
        val avatar = peerAvatar ?: ""
        return "chat/$peerId/${encode(peerName)}/${encode(peerUsername)}/${encode(avatar)}"
    }

    private fun encode(value: String): String =
        android.net.Uri.encode(value.ifEmpty { "_" })
}

@androidx.camera.core.ExperimentalGetImage
@Composable
fun AppNav(
    sessionStore: SessionStore,
    token: String?,
    currentUser: UserPublic?,
) {
    val context = LocalContext.current
    val api = remember { Api(context.applicationContext) }
    val navController = rememberNavController()

    LaunchedEffect(token) {
        if (!token.isNullOrBlank()) {
            RealtimeBus.connect(api, token)
        } else {
            RealtimeBus.disconnect()
        }
    }

    val start = if (token.isNullOrBlank()) Routes.AUTH else Routes.HOME

    NavHost(
        navController = navController,
        startDestination = start,
        enterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(280)) +
                fadeIn(tween(280))
        },
        exitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(280)) +
                fadeOut(tween(280))
        },
        popEnterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(280)) +
                fadeIn(tween(280))
        },
        popExitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(280)) +
                fadeOut(tween(280))
        },
    ) {
        composable(Routes.AUTH) {
            AuthScreen(
                api = api,
                onAuthenticated = { result ->
                    sessionStore.save(result)
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.AUTH) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                api = api,
                token = token,
                currentUser = currentUser,
                onOpenChat = { peer ->
                    navController.navigate(
                        Routes.chat(
                            peerId = peer.id,
                            peerName = peer.displayName,
                            peerUsername = peer.username,
                            peerAvatar = api.avatarUrlFor(peer),
                        )
                    )
                },
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenProfile = { navController.navigate(Routes.PROFILE) },
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(
                api = api,
                token = token,
                onOpenChat = { peer ->
                    navController.navigate(
                        Routes.chat(
                            peerId = peer.id,
                            peerName = peer.displayName,
                            peerUsername = peer.username,
                            peerAvatar = api.avatarUrlFor(peer),
                        )
                    ) {
                        popUpTo(Routes.HOME)
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.PROFILE) {
            ProfileScreen(
                api = api,
                token = token,
                currentUser = currentUser,
                onSaved = { user -> sessionStore.saveUser(user) },
                onLogout = {
                    sessionStore.clear()
                    navController.navigate(Routes.AUTH) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument("peerId") { type = NavType.LongType },
                navArgument("peerName") { type = NavType.StringType },
                navArgument("peerUsername") { type = NavType.StringType },
                navArgument("peerAvatar") { type = NavType.StringType },
            ),
        ) { backStack ->
            val peerId = backStack.arguments?.getLong("peerId") ?: 0L
            val peerName = backStack.arguments?.getString("peerName").orEmpty()
            val peerUsername = backStack.arguments?.getString("peerUsername").orEmpty()
            val peerAvatar = backStack.arguments?.getString("peerAvatar").orEmpty()
            ChatScreen(
                api = api,
                token = token,
                currentUserId = currentUser?.id ?: 0L,
                peerId = peerId,
                peerDisplayName = peerName,
                peerUsername = peerUsername,
                peerAvatarUrl = peerAvatar.takeIf { it.isNotBlank() },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
