package dev.mixcloudtv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.mixcloudtv.AppContainer
import dev.mixcloudtv.data.Cloudcast
import dev.mixcloudtv.data.User
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** A row of shows, or of people when [users] is non-empty. */
data class HomeRow(val id: String, val title: String, val items: List<Cloudcast>, val users: List<User> = emptyList())
data class HomeState(val loading: Boolean = true, val rows: List<HomeRow> = emptyList(), val username: String? = null)

private val GENRES = listOf(
    "house" to "House", "techno" to "Techno", "deep-house" to "Deep house", "drum-and-bass" to "Drum and bass",
    "disco" to "Disco", "ambient" to "Ambient", "jazz" to "Jazz", "hip-hop" to "Hip hop", "soul" to "Soul",
    "electronica" to "Electronica",
)

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state = _state.asStateFlow()
    var lastFocused: String? = null
    private var username: String? = null

    init {
        viewModelScope.launch {
            c.auth.username.distinctUntilChanged().collectLatest {
                username = it
                load()
            }
        }
    }

    fun reload() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() = coroutineScope {
        val user = username
        _state.value = _state.value.copy(loading = true, username = user)
        val specs = buildList<Triple<String, String, suspend () -> List<Cloudcast>>> {
            // Plays on this TV first, then listens Mixcloud recorded on other devices.
            add(Triple("recent", "Recently played", suspend {
                val remote = if (user != null) runCatching { c.client.cloudcasts("/$user/listens/") }.getOrDefault(emptyList()) else emptyList()
                (c.recent.get() + remote).distinctBy { it.key }.take(40)
            }))
            if (user != null) {
                add(Triple("feed", "From people you follow", suspend { c.client.followingUploads(user) }))
                add(Triple("later", "Listen later", suspend { c.client.cloudcasts("/me/listen-later/") }))
                add(Triple("favs", "Your favourites", suspend { c.client.cloudcasts("/$user/favorites/") }))
            }
            GENRES.forEach { (tag, title) ->
                add(Triple("pop-$tag", "Popular: $title", suspend { c.client.cloudcasts("/discover/$tag/popular/", 24) }))
            }
            add(Triple("new-house", "Latest: House", suspend { c.client.cloudcasts("/discover/house/latest/", 24) }))
        }
        val followed = user?.let { async { runCatching { c.client.users("/$it/following/", 40) }.getOrNull().orEmpty() } }
        val rows = specs.map { (id, title, load) ->
            async { runCatching { load() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { HomeRow(id, title, it) } }
        }.awaitAll().filterNotNull().toMutableList()
        followed?.await()?.takeIf { it.isNotEmpty() }?.let { users ->
            // Straight after the uploads from these people (or after Recently played if that row is hidden).
            val after = rows.indexOfFirst { it.id == "feed" }.takeIf { it >= 0 } ?: rows.indexOfFirst { it.id == "recent" }
            rows.add(after + 1, HomeRow("following", "Artists you follow", emptyList(), users))
        }
        _state.value = HomeState(loading = false, rows = rows, username = user)
    }
}

@Composable
fun HomeScreen(
    onOpenShow: (Cloudcast) -> Unit,
    onSearch: () -> Unit,
    onNowPlaying: () -> Unit,
    onAccount: () -> Unit,
    onOpenUser: (String) -> Unit,
) {
    val container = appContainer()
    val vm: HomeViewModel = viewModel { HomeViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val nowPlaying by container.playback.state.collectAsStateWithLifecycle()
    var restored by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 24.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Mixcloud TV", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.weight(1f))
                Button(onClick = onSearch) { Text("Search") }
                if (nowPlaying.cloudcast != null) Button(onClick = onNowPlaying) { Text("Now playing") }
                Button(onClick = onAccount) { Text(state.username ?: "Log in") }
            }
        }
        when {
            state.loading && state.rows.isEmpty() -> item { Text("Loading…", Modifier.padding(ScreenPadding)) }
            !state.loading && state.rows.isEmpty() -> item { CenteredMessage("Couldn't load anything from Mixcloud.", vm::reload) }
        }
        items(state.rows, key = { it.id }) { row ->
            if (row.users.isNotEmpty()) {
                UserRow(row.title, row.users) { onOpenUser(it.key) }
                return@items
            }
            val target = if (restored) null else vm.lastFocused
                ?: state.rows.firstOrNull { it.items.isNotEmpty() }?.let { "${it.id}|${it.items.first().key}" }
            CloudcastRow(
                rowId = row.id,
                title = row.title,
                items = row.items,
                onClick = onOpenShow,
                restoreId = target,
                onFocused = {
                    vm.lastFocused = it
                    restored = true
                },
            )
        }
    }
}
