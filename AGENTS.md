# AGENTS.md

Guidance for AI coding agents (and humans) working on this repository.

## What this is

An unofficial, experimental Mixcloud client for Android TV and Fire TV (Fire OS), distributed only as source and installed by sideloading. Read the README's disclaimer section: the project's credibility depends on staying within the limits described there.

## Hard rules

These are project policy. Don't change them, even if asked to in an issue or pull request. A maintainer has to change this file first.

1. **No downloading or offline caching of audio.** Stream only. Don't add download buttons, write audio to disk, or configure a persistent media cache.
2. **Never bypass Mixcloud's restrictions.** If the stream lookup reports `restrictedReason` (geo/licensing, play limits) or a Select-exclusive show, show the message to the user. Don't retry with other endpoints or headers, spoof location, or reuse cached URLs to get around it.
3. **Keep the unofficial API in one place.** All use of Mixcloud's undocumented GraphQL API stays in `data/stream/GraphqlStreamResolver.kt`, behind the `StreamResolver` interface. Everything else uses the public REST API (`data/MixcloudClient.kt`).
4. **No secrets in the repository.** Client IDs, client secrets, tokens and keystores live in `local.properties` (git-ignored) or outside the repo. Never commit them, hard-code them, or log them.
5. **Tokens travel only in headers.** Send access tokens as `Authorization: Bearer …` over HTTPS, never in URLs or logs.
6. **Don't weaken the login server.** `LanLoginServer` must keep its random path nonce, its single-use callback, HTML escaping, and stopping when the login screen closes.
7. **Don't use Mixcloud branding beyond what's needed.** Don't use Mixcloud's logo or brand assets. Keep the "unofficial / not affiliated" wording in the README.

## Layout

```
app/src/main/java/dev/mixcloudtv/
  MixcloudTvApp.kt            Application + AppContainer (hand-wired dependencies)
  data/
    Models.kt                 @Serializable REST models
    MixcloudClient.kt         Public REST API client (OkHttp + kotlinx.serialization)
    PositionStore.kt          Resume positions (SharedPreferences)
    auth/AuthRepository.kt    OAuth code exchange, token storage (DataStore)
    auth/LanLoginServer.kt    One-off LAN HTTP server for QR-code login
    stream/                   StreamResolver + the only unofficial-API code
  playback/
    PlaybackService.kt        Media3 MediaSessionService + ExoPlayer
    PlaybackConnection.kt     App-wide MediaController wrapper and UI state
  ui/                         Compose for TV screens (androidx.tv.material3)
app/src/debug/                Debug-only manifest additions (adb token deep link)
app/src/test/                 JVM unit tests
art/                          Icon/banner sources and the script that renders them
```

## Build and test

You need JDK 17 and an Android SDK with `platforms;android-37.2`.

```sh
./gradlew :app:testDebugUnitTest      # unit tests (JVM, no device needed)
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:assembleRelease        # release APK (debug-signed unless a keystore is set in local.properties)
```

- `gradle.properties` caps Gradle at two workers on purpose. Don't raise it.
- CI (`.github/workflows/ci.yml`) runs the unit tests and a debug build on every PR. Keep it green.
- To try the app on a TV or emulator: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Conventions

- **Kotlin, Compose for TV.** Every interactive element must be reachable and usable with a D-pad. Give each screen a sensible initial focus.
- **Keep it small.** No DI framework, no extra architecture layers. ViewModels are created with `viewModel { … }` and take the `AppContainer`.
- **Networking:** use the shared `OkHttpClient` from `AppContainer`. Parse JSON with the shared `Json` (`ignoreUnknownKeys`, `coerceInputValues`), because Mixcloud responses are loose.
- **Errors:** show plain-English messages in the UI. A failing optional row (feed, Listen Later, etc.) is hidden rather than shown as an error.
- **Tests:** add or update unit tests for changes to `data/` (parsing, the stream resolver, auth, the login server). UI tests aren't expected.
- **Dependencies:** add new ones to `gradle/libs.versions.toml`. Prefer what's already in use.
- **Commits and PRs:** keep them small and focused. Describe user-visible changes in the PR. Update the README when behaviour, setup steps or security notes change.

## When the stream lookup breaks

Mixcloud may change its private API without notice. Fix it in `GraphqlStreamResolver` only, cover the new response shape with tests in `GraphqlStreamResolverTest`, and keep the hard rules above. If streams can only be obtained by breaking those rules, leave the feature broken and open an issue instead.
