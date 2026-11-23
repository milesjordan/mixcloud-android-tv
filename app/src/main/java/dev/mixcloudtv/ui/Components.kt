package dev.mixcloudtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import coil3.compose.AsyncImage
import dev.mixcloudtv.AppContainer
import dev.mixcloudtv.MixcloudTvApp
import dev.mixcloudtv.data.Cloudcast
import dev.mixcloudtv.data.User

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as MixcloudTvApp).container

@Composable
fun MixcloudTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF9D8CFF),
            onPrimary = Color(0xFF14102B),
            background = Color(0xFF101014),
            surface = Color(0xFF1B1B22),
            onBackground = Color(0xFFECECF1),
            onSurface = Color(0xFFECECF1),
        ),
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                content()
            }
        }
    }
}

val ScreenPadding = 48.dp

@Composable
fun CloudcastCard(cast: Cloudcast, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier.width(200.dp)) {
        AsyncImage(
            model = cast.pictures.card(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        Column(Modifier.padding(10.dp)) {
            Text(cast.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                cast.user.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun UserCard(user: User, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier.width(160.dp)) {
        AsyncImage(
            model = user.pictures.card(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(12.dp).clip(CircleShape),
        )
        Text(
            user.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp).padding(bottom = 10.dp),
        )
    }
}

/**
 * A titled horizontal row of shows. [restoreId] names the card ("rowId|key") that should
 * take focus when the screen is shown again, so Back returns you to where you were.
 */
@Composable
fun CloudcastRow(
    rowId: String,
    title: String,
    items: List<Cloudcast>,
    onClick: (Cloudcast) -> Unit,
    restoreId: String? = null,
    onFocused: (String) -> Unit = {},
) {
    Column(Modifier.padding(vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = ScreenPadding, bottom = 12.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(items, key = { i, c -> "$i:${c.key}" }) { _, cast ->
                val id = "$rowId|${cast.key}"
                val requester = remember { FocusRequester() }
                if (id == restoreId) {
                    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
                }
                CloudcastCard(
                    cast,
                    onClick = { onClick(cast) },
                    modifier = Modifier.focusRequester(requester).onFocusChanged { if (it.isFocused) onFocused(id) },
                )
            }
        }
    }
}

@Composable
fun UserRow(title: String, users: List<User>, onClick: (User) -> Unit) {
    Column(Modifier.padding(vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = ScreenPadding, bottom = 12.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(users, key = { i, u -> "$i:${u.key}" }) { _, user ->
                UserCard(user, onClick = { onClick(user) })
            }
        }
    }
}

@Composable
fun CenteredMessage(text: String, onRetry: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(ScreenPadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        if (onRetry != null) {
            val requester = remember { FocusRequester() }
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp).focusRequester(requester)) { Text("Try again") }
            LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
        }
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/**
 * Focusable scrubber for the remote. Left/Right move a marker (faster the longer the key is held),
 * OK seeks straight away, and it also seeks by itself shortly after you stop pressing.
 */
@Composable
fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    labelFor: (Long) -> String?,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Long?>(null) }

    // Commit automatically once the user pauses on a spot.
    LaunchedEffect(scrub) {
        val target = scrub ?: return@LaunchedEffect
        delay(1500)
        onSeek(target)
        scrub = null
    }

    val shown = scrub ?: positionMs
    val fraction = if (durationMs > 0) shown.toFloat() / durationMs else 0f

    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .onFocusChanged {
                    focused = it.isFocused
                    if (!it.isFocused) scrub?.let { target -> onSeek(target); scrub = null }
                }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || durationMs <= 0) return@onKeyEvent false
                    val repeat = event.nativeKeyEvent.repeatCount
                    val step = when {
                        repeat < 4 -> 10_000L
                        repeat < 12 -> 30_000L
                        else -> maxOf(60_000L, durationMs / 40)
                    }
                    when (event.key) {
                        Key.DirectionRight -> { scrub = ((scrub ?: positionMs) + step).coerceAtMost(durationMs - 1_000); true }
                        Key.DirectionLeft -> { scrub = ((scrub ?: positionMs) - step).coerceAtLeast(0); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            scrub?.let { onSeek(it); scrub = null }
                            true
                        }
                        else -> false
                    }
                }
                .focusable(),
            contentAlignment = Alignment.CenterStart,
        ) {
            val barHeight = if (focused) 12.dp else 6.dp
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(if (scrub != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary),
                )
            }
            if (focused) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val x = (maxWidth - 24.dp) * fraction.coerceIn(0f, 1f)
                    Box(
                        Modifier
                            .offset(x = x)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color.White),
                    )
                }
            }
        }
        val label = scrub?.let { labelFor(it) }
        Text(
            buildString {
                if (scrub != null) append("Jump to ")
                append("${formatTime(shown / 1000)} / ${formatTime(durationMs / 1000)}")
                if (label != null) append("  •  $label")
                if (focused && scrub == null) append("    ◀ ▶ to scrub")
            },
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

fun formatTime(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
