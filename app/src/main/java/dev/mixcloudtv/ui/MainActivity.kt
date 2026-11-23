package dev.mixcloudtv.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.mixcloudtv.BuildConfig
import dev.mixcloudtv.MixcloudTvApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container get() = (application as MixcloudTvApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            MixcloudTvTheme { AppNav() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Dev shortcut: adb shell am start -d "mixcloudtv://token?access_token=..." */
    private fun handleIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val data = intent?.data ?: return
        if (data.scheme != "mixcloudtv" || data.host != "token") return
        val token = data.getQueryParameter("access_token") ?: return
        lifecycleScope.launch {
            val msg = runCatching { container.completeLogin(token) }.fold({ "Logged in" }, { "Token rejected: ${it.message}" })
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    // Media keys on the remote while the app is in front.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val playback = container.playback
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> playback.togglePlay()
            KeyEvent.KEYCODE_MEDIA_PLAY -> playback.resume()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> playback.pause()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> playback.seekBy(30_000)
            KeyEvent.KEYCODE_MEDIA_REWIND -> playback.seekBy(-30_000)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}

private fun NavHostController.openShow(key: String) = navigate("show/${Uri.encode(key)}")
private fun NavHostController.openUser(key: String) = navigate("user/${Uri.encode(key)}")

@androidx.compose.runtime.Composable
private fun AppNav() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onOpenShow = { nav.openShow(it.key) },
                onSearch = { nav.navigate("search") },
                onNowPlaying = { nav.navigate("player") },
                onAccount = { nav.navigate("login") },
            )
        }
        composable("search") {
            SearchScreen(onOpenShow = { nav.openShow(it.key) }, onOpenUser = { nav.openUser(it) })
        }
        composable("show/{key}") { entry ->
            val key = entry.arguments?.getString("key").orEmpty()
            ShowScreen(
                key = key,
                onPlay = { nav.navigate("player") { launchSingleTop = true } },
                onOpenUser = { nav.openUser(it) },
                onOpenShow = { nav.openShow(it.key) },
            )
        }
        composable("user/{key}") { entry ->
            UserScreen(key = entry.arguments?.getString("key").orEmpty(), onOpenShow = { nav.openShow(it.key) })
        }
        composable("player") {
            PlayerScreen(onOpenShow = { nav.openShow(it) })
        }
        composable("login") {
            LoginScreen(onDone = { nav.popBackStack() })
        }
    }
}
