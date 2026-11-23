package dev.mixcloudtv.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.mixcloudtv.AppContainer
import dev.mixcloudtv.data.auth.AuthRepository
import dev.mixcloudtv.data.auth.LanLoginServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class LoginViewModel(private val c: AppContainer) : ViewModel() {
    val status = MutableStateFlow<String?>(null)
    val done = MutableStateFlow(false)

    fun onCode(code: String, redirectUri: String) {
        viewModelScope.launch {
            status.value = "Logging in…"
            runCatching { c.completeLogin(c.auth.exchangeCode(code, redirectUri)) }
                .onSuccess { done.value = true }
                .onFailure { status.value = "Login failed: ${it.message}" }
        }
    }

    fun logout() {
        viewModelScope.launch {
            c.auth.logout()
            CookieManager.getInstance().removeAllCookies(null)
        }
    }
}

private fun qrBitmap(text: String, size: Int = 512): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

@Composable
fun LoginScreen(onDone: () -> Unit) {
    val container = appContainer()
    val vm: LoginViewModel = viewModel { LoginViewModel(container) }
    val username by container.auth.username.collectAsStateWithLifecycle(initialValue = null)
    val status by vm.status.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    var useTvBrowser by remember { mutableStateOf(false) }

    LaunchedEffect(done) { if (done) onDone() }

    when {
        username != null -> Column(Modifier.fillMaxSize().padding(ScreenPadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Logged in as $username", style = MaterialTheme.typography.headlineMedium)
            val r = remember { FocusRequester() }
            Button(onClick = { vm.logout() }, modifier = Modifier.focusRequester(r)) { Text("Log out") }
            LaunchedEffect(Unit) { runCatching { r.requestFocus() } }
        }
        !container.auth.isConfigured -> Column(Modifier.fillMaxSize().padding(ScreenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Login isn't set up in this build", style = MaterialTheme.typography.headlineMedium)
            Text("Create an app at mixcloud.com/developers, then put MIXCLOUD_CLIENT_ID and MIXCLOUD_CLIENT_SECRET in local.properties and rebuild.")
            val r = remember { FocusRequester() }
            Button(onClick = onDone, modifier = Modifier.focusRequester(r)) { Text("Back") }
            LaunchedEffect(Unit) { runCatching { r.requestFocus() } }
        }
        useTvBrowser -> TvBrowserLogin(container, vm, status)
        else -> QrLogin(container, vm, status, onUseTvBrowser = { useTvBrowser = true })
    }
}

@Composable
private fun QrLogin(container: AppContainer, vm: LoginViewModel, status: String?, onUseTvBrowser: () -> Unit) {
    var url by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        val server = LanLoginServer(
            authorizeUrl = { redirect -> container.auth.authorizeUrl(redirect) },
            onCode = { code, redirect -> vm.onCode(code, redirect) },
            onError = { vm.status.value = it },
        )
        url = server.start()
        if (url == null) vm.status.value = "The TV doesn't seem to be on a network."
        onDispose { server.stop() }
    }
    val qr = remember(url) { url?.let { qrBitmap(it) } }
    val r = remember { FocusRequester() }

    Row(
        Modifier.fillMaxSize().padding(ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(340.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(12.dp)) {
            qr?.let { Image(it, contentDescription = "Login QR code", modifier = Modifier.fillMaxSize()) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Log in with your phone", style = MaterialTheme.typography.headlineMedium)
            Text("1. Make sure your phone is on the same Wi-Fi as this TV.")
            Text("2. Scan the code with your phone's camera.")
            Text("3. Log in to Mixcloud and allow access.")
            url?.let { Text("Or open $it on any device on this network.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            status?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
            OutlinedButton(onClick = onUseTvBrowser, modifier = Modifier.focusRequester(r)) { Text("Log in on the TV instead") }
        }
    }
    LaunchedEffect(Unit) { runCatching { r.requestFocus() } }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TvBrowserLogin(container: AppContainer, vm: LoginViewModel, status: String?) {
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url
                            if (!url.toString().startsWith(AuthRepository.REDIRECT_URI)) return false
                            val code = url.getQueryParameter("code")
                            if (code != null) vm.onCode(code, AuthRepository.REDIRECT_URI)
                            else vm.status.value = "Login cancelled: ${url.getQueryParameter("error") ?: "no code"}"
                            return true
                        }
                    }
                    loadUrl(container.auth.authorizeUrl())
                    requestFocus()
                }
            },
        )
        status?.let {
            Text(it, modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp), style = MaterialTheme.typography.titleMedium)
        }
    }
}
