package dev.mixcloudtv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.mixcloudtv.AppContainer
import dev.mixcloudtv.data.Cloudcast
import dev.mixcloudtv.data.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---------- Show ----------

data class ShowState(
    val loading: Boolean = true,
    val cast: Cloudcast? = null,
    val similar: List<Cloudcast> = emptyList(),
    val error: String? = null,
    val loggedIn: Boolean = false,
    /** Null until known (or when logged out). */
    val following: Boolean? = null,
    val message: String? = null,
)

class ShowViewModel(private val c: AppContainer, private val key: String) : ViewModel() {
    private val _state = MutableStateFlow(ShowState())
    val state = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val loggedIn = c.auth.token.first() != null
            runCatching { c.client.cloudcast(key) }
                .onSuccess { cast -> _state.update { it.copy(loading = false, cast = cast, loggedIn = loggedIn) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load show") } }
            runCatching { c.client.cloudcasts(key + "similar/", 20) }
                .onSuccess { sim -> _state.update { it.copy(similar = sim) } }
        }
    }

    fun act(label: String, action: suspend () -> Unit) {
        viewModelScope.launch {
            val msg = runCatching { action() }.fold({ "$label ✓" }, { "$label failed: ${it.message}" })
            _state.update { it.copy(message = msg) }
        }
    }
}

@Composable
fun ShowScreen(key: String, onPlay: () -> Unit, onOpenUser: (String) -> Unit, onOpenShow: (Cloudcast) -> Unit) {
    val container = appContainer()
    val vm: ShowViewModel = viewModel(key = key) { ShowViewModel(container, key) }
    val state by vm.state.collectAsStateWithLifecycle()
    val now by container.playback.state.collectAsStateWithLifecycle()
    val cast = state.cast

    when {
        state.loading && cast == null -> CenteredMessage("Loading…")
        cast == null -> CenteredMessage(state.error ?: "Couldn't load show", vm::load)
        else -> {
            val playRequester = remember { FocusRequester() }
            val resumeAt = container.positions.resumePoint(cast.key, cast.audioLength)
            val isCurrent = now.cloudcast?.key == cast.key
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 32.dp)) {
                item {
                    Row(Modifier.padding(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        AsyncImage(
                            model = cast.pictures.big(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(300.dp).clip(RoundedCornerShape(12.dp)),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(cast.name, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(cast.user.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            Text(
                                listOfNotNull(
                                    formatTime(cast.audioLength.toLong()),
                                    "${cast.playCount} plays",
                                    cast.createdTime?.take(10),
                                    cast.tags.joinToString(", ") { it.name }.ifBlank { null },
                                ).joinToString("  •  "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                                Button(
                                    onClick = {
                                        if (!isCurrent) container.playback.play(cast)
                                        onPlay()
                                    },
                                    modifier = Modifier.focusRequester(playRequester),
                                ) {
                                    Text(
                                        when {
                                            isCurrent -> "Now playing"
                                            resumeAt > 0 -> "Resume from ${formatTime(resumeAt / 1000)}"
                                            else -> "Play"
                                        },
                                    )
                                }
                                if (resumeAt > 0 && !isCurrent) {
                                    OutlinedButton(onClick = {
                                        container.positions.clear(cast.key)
                                        container.playback.play(cast)
                                        onPlay()
                                    }) { Text("Play from start") }
                                }
                                OutlinedButton(onClick = { onOpenUser(cast.user.key) }) { Text("More from ${cast.user.name}") }
                                if (state.loggedIn) {
                                    OutlinedButton(onClick = { vm.act("Favourite") { container.client.favorite(cast.key) } }) { Text("Favourite") }
                                    OutlinedButton(onClick = { vm.act("Listen later") { container.client.listenLater(cast.key) } }) { Text("Listen later") }
                                }
                            }
                            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            cast.description?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 5, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    LaunchedEffect(Unit) { runCatching { playRequester.requestFocus() } }
                }
                if (state.similar.isNotEmpty()) {
                    item { CloudcastRow("similar", "Similar shows", state.similar, onOpenShow) }
                }
                if (cast.sections.isNotEmpty()) {
                    item {
                        Text("Tracklist", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = ScreenPadding, top = 16.dp))
                    }
                    itemsIndexed(cast.sections) { i, section ->
                        ListItem(
                            selected = false,
                            onClick = {
                                // Jump to the track if this show is the one playing.
                                val start = section.startTime ?: return@ListItem
                                if (isCurrent) container.playback.seekTo(start * 1000L)
                            },
                            headlineContent = { Text("${i + 1}. ${section.label()}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingContent = { section.startTime?.let { Text(formatTime(it.toLong())) } },
                            modifier = Modifier.padding(horizontal = ScreenPadding),
                        )
                    }
                }
            }
        }
    }
}

// ---------- User ----------

data class UserState(
    val loading: Boolean = true,
    val user: User? = null,
    val uploads: List<Cloudcast> = emptyList(),
    val favourites: List<Cloudcast> = emptyList(),
    val error: String? = null,
    val loggedIn: Boolean = false,
    /** Null until known (or when logged out). */
    val following: Boolean? = null,
    val message: String? = null,
)

class UserViewModel(private val c: AppContainer, private val key: String) : ViewModel() {
    private val _state = MutableStateFlow(UserState())
    val state = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val loggedIn = c.auth.token.first() != null
            runCatching { c.client.user(key) }
                .onSuccess { u -> _state.update { it.copy(loading = false, user = u, loggedIn = loggedIn) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load") } }
            runCatching { c.client.cloudcasts(key + "cloudcasts/") }.onSuccess { l -> _state.update { it.copy(uploads = l) } }
            runCatching { c.client.cloudcasts(key + "favorites/") }.onSuccess { l -> _state.update { it.copy(favourites = l) } }
            c.auth.username.first()?.let { me ->
                runCatching { c.client.followingKeys(me) }
                    .onSuccess { keys -> _state.update { it.copy(following = key.lowercase() in keys) } }
            }
        }
    }

    fun toggleFollow() {
        viewModelScope.launch {
            val follow = _state.value.following != true
            runCatching { if (follow) c.client.follow(key) else c.client.unfollow(key) }
                .onSuccess { _state.update { it.copy(following = follow, message = null) } }
                .onFailure { e -> _state.update { it.copy(message = "Couldn't ${if (follow) "follow" else "unfollow"}: ${e.message}") } }
        }
    }
}

@Composable
fun UserScreen(key: String, onOpenShow: (Cloudcast) -> Unit) {
    val container = appContainer()
    val vm: UserViewModel = viewModel(key = key) { UserViewModel(container, key) }
    val state by vm.state.collectAsStateWithLifecycle()
    val user = state.user
    when {
        state.loading && user == null -> CenteredMessage("Loading…")
        user == null -> CenteredMessage(state.error ?: "Couldn't load", vm::load)
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 32.dp)) {
            item {
                Row(Modifier.padding(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    AsyncImage(
                        model = user.pictures.big(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(140.dp).clip(CircleShape),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(user.name, style = MaterialTheme.typography.headlineMedium)
                        Text(
                            listOfNotNull("${user.followerCount} followers", "${user.cloudcastCount} shows", user.city?.ifBlank { null })
                                .joinToString("  •  "),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        user.biog?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                        if (state.loggedIn) {
                            if (state.following == true) {
                                OutlinedButton(onClick = vm::toggleFollow) { Text("Following ✓  (Unfollow)") }
                            } else {
                                Button(onClick = vm::toggleFollow) { Text("Follow") }
                            }
                        }
                        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            if (state.uploads.isNotEmpty()) item { CloudcastRow("uploads", "Shows", state.uploads, onOpenShow, restoreId = "uploads|${state.uploads.first().key}") }
            if (state.favourites.isNotEmpty()) item { CloudcastRow("favs", "Their favourites", state.favourites, onOpenShow) }
        }
    }
}
