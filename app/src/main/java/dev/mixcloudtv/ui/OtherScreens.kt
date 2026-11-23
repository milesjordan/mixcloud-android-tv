package dev.mixcloudtv.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.mixcloudtv.AppContainer
import dev.mixcloudtv.data.Cloudcast
import dev.mixcloudtv.data.User
import dev.mixcloudtv.data.auth.AuthRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---------- Search ----------

data class SearchState(
    val query: String = "",
    val searching: Boolean = false,
    val shows: List<Cloudcast> = emptyList(),
    val users: List<User> = emptyList(),
    val error: String? = null,
)

class SearchViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(SearchState())
    val state = _state.asStateFlow()
    private var job: Job? = null

    fun onQuery(q: String) {
        _state.update { it.copy(query = q) }
        job?.cancel()
        job = viewModelScope.launch {
            delay(700)
            search(q)
        }
    }

    fun searchNow() {
        job?.cancel()
        job = viewModelScope.launch { search(_state.value.query) }
    }

    private suspend fun search(q: String) {
        if (q.isBlank()) {
            _state.update { it.copy(searching = false, shows = emptyList(), users = emptyList(), error = null) }
            return
        }
        _state.update { it.copy(searching = true, error = null) }
        val shows = runCatching { c.client.searchCloudcasts(q) }
        val users = runCatching { c.client.searchUsers(q) }
        _state.update {
            it.copy(
                searching = false,
                shows = shows.getOrDefault(emptyList()),
                users = users.getOrDefault(emptyList()),
                error = shows.exceptionOrNull()?.message,
            )
        }
    }
}

@Composable
fun SearchScreen(onOpenShow: (Cloudcast) -> Unit, onOpenUser: (String) -> Unit) {
    val container = appContainer()
    val vm: SearchViewModel = viewModel { SearchViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val fieldRequester = remember { FocusRequester() }
    var fieldFocused by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 32.dp)) {
        item {
            Column(Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp)) {
                Text("Search Mixcloud", style = MaterialTheme.typography.headlineMedium)
                BasicTextField(
                    value = state.query,
                    onValueChange = vm::onQuery,
                    singleLine = true,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.searchNow() }),
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .fillMaxWidth(0.6f)
                        .focusRequester(fieldRequester)
                        .onFocusChanged { fieldFocused = it.isFocused }
                        .border(
                            2.dp,
                            if (fieldFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(16.dp),
                    decorationBox = { inner ->
                        Box {
                            if (state.query.isEmpty()) {
                                Text("Press OK to type a show, DJ or genre", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            inner()
                        }
                    },
                )
                when {
                    state.searching -> Text("Searching…", Modifier.padding(top = 12.dp))
                    state.error != null -> Text("Search failed: ${state.error}", Modifier.padding(top = 12.dp))
                }
            }
            LaunchedEffect(Unit) { runCatching { fieldRequester.requestFocus() } }
        }
        if (state.shows.isNotEmpty()) item { CloudcastRow("s-shows", "Shows", state.shows, onOpenShow) }
        if (state.users.isNotEmpty()) item { UserRow("People", state.users) { onOpenUser(it.key) } }
    }
}

// ---------- Now playing ----------

@Composable
fun PlayerScreen(onOpenShow: (String) -> Unit) {
    val playback = appContainer().playback
    val state by playback.state.collectAsStateWithLifecycle()
    val cast = state.cloudcast
    if (cast == null) {
        CenteredMessage("Nothing playing")
        return
    }
    val playRequester = remember { FocusRequester() }
    val durationMs = state.durationMs.takeIf { it > 0 } ?: (cast.audioLength * 1000L)
    val current = cast.sectionAt(state.positionMs / 1000)

    Row(
        Modifier.fillMaxSize().padding(ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = cast.pictures.big(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(360.dp).clip(RoundedCornerShape(16.dp)),
        )
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(cast.name, style = MaterialTheme.typography.headlineMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(cast.user.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            current?.let {
                Text("Now: ${it.label()}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when {
                state.error != null -> Text(state.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                state.resolving -> Text("Getting stream…")
                state.buffering -> Text("Buffering…")
            }
            SeekBar(
                positionMs = state.positionMs,
                durationMs = durationMs,
                onSeek = playback::seekTo,
                labelFor = { ms -> cast.sectionAt(ms / 1000)?.label() },
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { playback.seekBy(-30_000) }) { Text("« 30s") }
                Button(onClick = playback::togglePlay, modifier = Modifier.focusRequester(playRequester)) {
                    Text(if (state.isPlaying) "Pause" else "Play")
                }
                OutlinedButton(onClick = { playback.seekBy(30_000) }) { Text("30s »") }
                OutlinedButton(onClick = { playback.seekBy(600_000) }) { Text("10m »") }
                OutlinedButton(onClick = { onOpenShow(cast.key) }) { Text("Show & tracklist") }
            }
            if (state.error != null) {
                OutlinedButton(onClick = { playback.play(cast) }) { Text("Retry") }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { playRequester.requestFocus() } }
}

