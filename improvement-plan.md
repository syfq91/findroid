# Findroid fork — improvement plan

Scope: the six non-merge commits this fork adds on top of upstream
[`jarnedemeulemeester/findroid`](https://github.com/jarnedemeulemeester/findroid) `main`
— the libass/ExoPlayer subtitle work and the EPUB reader — plus the shared upstream
code those commits touch.

## Baseline

| Fact | Value |
|---|---|
| Upstream `main` | `f7d090f7` — identical to this fork's branch point |
| Fork delta | `git diff f7d090f7..HEAD` → 42 files, +3239 / −41 |
| Fork commits | `9dbd0227` libass, `2bb2dee3` EPUB, `325e5022` reader prefs, `0d7fb6e6` comics/manga, `90e45a78` offline books, `c3125096` build fixes |
| Upstream equivalents | none — upstream has no book/EPUB code and no libass in ExoPlayer |

The two headline features do not exist upstream, so there is no reference
implementation to align with. Upstream only matters for the shared code the fork
edits: `DownloaderImpl`, `JellyfinRepository{,Impl,OfflineImpl}`,
`ServerDatabase{,Dao}`, `Converters`, `FindroidItem`, `CollectionType`,
`PlayerViewModel`, `PlayerActivity`, `PlayerScreen`, `AppPreferences`.

Upstream's README names the gap the fork's libass work addresses: "SSA/ASS has
limited styling support", pointing at ExoPlayer#8435 (upstream `README.md:40`).

## Summary of findings

| Area | Upstream | Fork | Verdict |
|---|---|---|---|
| libass in ExoPlayer | stock renderers, no custom media source factory | `ass-media` 0.5.1, verified against the library's own recipe | correct, keep |
| Book model / storage | absent | `FindroidBook`, `books` table, Room 8→9 auto-migration | correct, keep |
| Book navigation | absent | phone only | gap on TV |
| Offline `getItem()` | returns `null` | nested try/catch over movie → episode → book | unintended behaviour change |
| Download URL | no such API | `?api_key=<token>` | works, leaks token in URL |
| `DownloaderImpl` | polymorphic `when (item)` | 7 extra `is FindroidBook` checks | diverges from upstream shape |
| Settings | — | `pref_player_exo_libass`, default `true` | visible to mpv users |

---

## P0 — Functional gaps

### P0-1 Books are inert rows on the TV app

**Problem.** `modes/film` is shared by both apps, and the fork made it emit books:
`LibraryViewModel.kt:46` maps `CollectionType.Books` to `BaseItemKind.BOOK` and
`:53` adds `BaseItemKind.BOOK` to the Mixed/Folders list, and
`CollectionType.supported` (`data/.../CollectionType.kt:19`) now contains `Books`,
which also creates a "Books" home row through `HomeViewModel.kt:129-146` and
un-hides book collections in `FindroidCollection.kt:28`.

The TV app's click handlers have no book branch —
`app/tv/.../presentation/film/LibraryScreen.kt:83` and `HomeScreen.kt:54` match only
`FindroidMovie`, `FindroidShow`, `FindroidEpisode`. A book therefore renders as a
card and does nothing when selected. The phone app routes correctly via
`NavigationRoot.kt:552 → BookRoute`.

**Proposal.** Introduce a per-app capability so the shared module can ask what the
host app supports:

```kotlin
// core/.../presentation/capability/ItemCapabilities.kt
interface ItemCapabilities {
    val supportsBooks: Boolean
}
```

- `app/phone` Hilt module → `supportsBooks = true`
- `app/tv` Hilt module → `supportsBooks = false`

Then in `modes/film/.../LibraryViewModel.kt` omit `BaseItemKind.BOOK` from the
Mixed/Folders list when unsupported, and in `HomeViewModel.loadViews()` filter out
`CollectionType.Books`.

**Risk.** Low. The alternative — implementing a TV reader — is a separate feature,
not a fix for a dead row.

**Done when.** No book card appears in the TV library or home rows, and the phone
behaviour is unchanged.

### P0-2 The reader reports playback progress on every page turn

**Problem.** `BookReaderActivity.kt:537-559` posts `postPlaybackProgress` from
`onLocatorChanged`, which fires on every locator change — every page turn, and on
every scroll step in continuous mode. There is no debounce, so a long read
generates a request per page.

**Proposal.** Keep the local write immediate (it powers instant resume) and debounce
only the network call:

```kotlin
nav.currentLocator
    .distinctUntilChangedBy { it.locations.totalProgression }
    .debounce(5_000)
    .collect { locator -> postProgress(locator) }
```

`debounce` comes from `kotlinx.coroutines.flow` and is still a `@FlowPreview` API in
kotlinx-coroutines 1.11.0, so the call site needs `@OptIn(FlowPreview::class)`;
`distinctUntilChangedBy` is stable and prevents a burst of identical positions from
re-arming the timer.

**Risk.** Low. If progress must survive a crash, keep writing the local locator on
every change and debounce only the server call.

**Done when.** Reading a chapter produces a handful of requests instead of one per
page, and the server still shows the final position after the reader closes.

### P0-3 Reading position is restored only from local storage

**Problem.** The reader restores from `SharedPreferences` (`KEY_LOCATOR_PREFIX`,
`BookReaderActivity.kt:404-413`). The fork already maps the server-side position
into the model (`FindroidBook.playbackPositionTicks`,
`data/.../FindroidBook.kt:87`), so a book read on another device opens at the start.

**Proposal.** When no local locator exists, derive one from the server position.
Readium 3.4 has no `locatorFromProgression`; use the positions service that
`PublicationOpener` registers for EPUB (`EpubPositionsService`) and Divina
(`PerResourcePositionsService`):

```kotlin
val positions = publication.positions()          // suspend, PositionsService
val index = (positions.size * progression).toInt().coerceIn(0, positions.lastIndex)
val locator = positions[index]
```

The progression is not `playbackPositionTicks / runtimeTicks`. The reader posts
`ticks = progression * VIRTUAL_TICKS_TOTAL` (`BookReaderActivity.kt:553`, with
`VIRTUAL_TICKS_TOTAL = 1_000_000_000L`), so the inverse is
`playbackPositionTicks / VIRTUAL_TICKS_TOTAL` — a synthetic scale that has nothing
to do with the runtime Jellyfin reports for a book. `VIRTUAL_TICKS_TOTAL` is a
private constant in the reader's companion object, so it needs to move somewhere
both the reader and the resume path can see it.

Keep the local locator authoritative when present — it is the more precise of the two.

**Risk.** Low. The derived progression is exact for positions written by this
reader, and approximate for positions written by another client that uses real
runtime ticks; that is acceptable for a resume position.

**Done when.** Opening a book that was read elsewhere resumes near the previous
position.

### P0-4 The publication is closed while the navigator may still use it

**Problem.** `BookReaderActivity.onDestroy()` (`:620-624`) calls `super.onDestroy()`
and then `publication?.close()`. Fragment teardown happens inside
`super.onDestroy()`, but Readium's navigator runs coroutines that can still touch the
publication while it is being closed.

**Proposal.** Close after the container view is detached:

```kotlin
override fun onDestroy() {
    super.onDestroy()
    locatorJob?.cancel()
    binding.fragmentContainer.post { publication?.close() }
}
```

**Risk.** Low, but the failure mode is a crash in a background coroutine, which is
hard to reproduce — worth fixing rather than monitoring.

**Done when.** Rotating the device and leaving the reader repeatedly produces no
crash in the logcat.

### P0-5 The access token is embedded in the reader's download URL

**Problem.** `JellyfinRepositoryImpl.getDownloadUrl()` (`:551-556`) builds
`"$baseUrl/Items/$itemId/Download?api_key=$token"`. The reader fetches the file
with `HttpURLConnection` (`BookReaderActivity.kt:357-370`), so the token ends up in
a URL that can reach logs and error messages. The token must be in the URL for
`DownloadManager` (it cannot send headers), so the URL form is required — but not
for the reader.

**Proposal.** Split the two consumers:

```kotlin
// JellyfinRepository
fun getDownloadUrl(itemId: UUID): String            // ?api_key=… — DownloadManager
suspend fun openDownloadStream(itemId: UUID): InputStream  // Authorization header
```

`DownloadManager` keeps using the URL; the reader uses a header-based request.

**Constraint.** The Jellyfin SDK cannot supply either. In `jellyfin-api` 1.9.2,
`LibraryApi.getDownloadUrl(itemId)` builds the URL with empty query parameters and
no token, and `LibraryApi.getDownload(itemId)` returns the whole file as
`Response<ByteArray>` — unusable for large books. The SDK also contains no `api_key`
literal in any artifact.

**Risk.** Low. The token stops travelling in URLs for the reader path.

**Done when.** The reader's download request carries an `Authorization` header and
no token appears in any URL it constructs.

---

## P1 — Structural alignment with upstream

The fork edits files upstream keeps refactoring (`DownloaderImpl`, the repository
layer, `PlayerViewModel`). Every extra conditional in those files is a future merge
conflict, so these items are about keeping the fork's diff small and its shape
recognisable.

### P1-1 Keep the libass types inside `player/local`

**Problem.** `player/local/build.gradle.kts` exposes `api(libs.libass.media)`, so
`app/phone/PlayerActivity.kt:104` and `app/tv/PlayerScreen.kt:182` import
`io.github.peerless2012.ass.media.kt.withAssSupport` — a top-level extension on
`SubtitleView` — and both apps must name an `androidx.media3.ui` type to call it.

**Proposal.** Move the call behind the module boundary:

```kotlin
// player/local/.../PlayerViewModel.kt
fun attachSubtitleOverlay(subtitleView: SubtitleView?) {
    val handler = assHandler ?: return
    subtitleView?.withAssSupport(handler)
}
```

`player/local` already depends on `androidx.media3.ui`, so it can name
`SubtitleView` itself. It can then use `implementation(libs.libass.media)` instead
of `api(...)`, and the app modules drop the libass import and the direct
`withAssSupport` call — they pass `binding.playerView.subtitleView` and nothing
else.

**Risk.** None. The library's own helper `buildWithAssSupport` applies the overlay
the same way; only the call site moves.

**Done when.** `grep -r "peerless2012" app/` returns nothing.

### P1-2 Extract the player construction from `PlayerViewModel.init`

**Problem.** `PlayerViewModel.kt:148-213` is now a 65-line `when` that builds the
ExoPlayer, and the libass branch mutates the builder across ~25 lines before
building. The ViewModel is the wrong place for that much construction logic.

**Proposal.** Extract it:

```kotlin
internal object ExoPlayerFactory {
    fun create(
        context: Context,
        useLibass: Boolean,
        audioAttributes: AudioAttributes,
        trackSelector: TrackSelector,
        seekBackMs: Long,
        seekForwardMs: Long,
    ): Pair<ExoPlayer, AssHandler?>
}
```

The ViewModel then reads `val (player, handler) = ExoPlayerFactory.create(...)`.

**Risk.** Low. Behaviour is identical — the current wiring already matches the
library's `buildWithAssSupport` recipe exactly (verified in
`ass-media` 0.5.1 sources: `withAssMkvSupport` on the extractors factory,
`setSubtitleParserFactory`, `renderersFactory.withAssSupport`, `handler.init(player)`).

**Done when.** `PlayerViewModel.init` is a short, readable block and the player
construction is unit-testable in isolation.

### P1-3 Make downloads polymorphic instead of type-checking

**Problem.** `core/.../DownloaderImpl.kt` carries seven `is FindroidBook` checks
(lines 64, 92, 94, 164, 178, 192, 244) inside functions that upstream keeps
type-driven with a single `when (item)`. Every new item type now needs edits in
four places, and the book cases are interleaved with movie/episode logic.

**Proposal.** Give the download flow one seam, so the downloader stops asking what
type it holds:

```kotlin
interface DownloadableItem : FindroidItem {
    suspend fun resolveDownloadSource(
        sourceId: String,
        repository: JellyfinRepository,
    ): FindroidSource

    suspend fun persistDownloadMetadata(
        database: ServerDatabaseDao,
        repository: JellyfinRepository,
    )
}
```

`FindroidMovie`, `FindroidEpisode` and `FindroidBook` implement it; `DownloaderImpl`
goes back to dispatching on the type. The book-specific source fallback chain moves
into `FindroidBook`. The episode branch still kicks off the show/season image
downloads, so that part stays in the downloader rather than in the item.

**Risk.** Medium — `DownloaderImpl` is central to offline mode. Land it as its own
commit with the movie and episode paths unchanged, and verify a movie download and
an episode download end to end before switching the book path over.

**Done when.** `DownloaderImpl` contains no `is FindroidBook` check.

### P1-4 Replace exception-driven lookups in the offline repository

**Problem.** `JellyfinRepositoryOfflineImpl.getItem()` (`:82-97`) returns `null` in
upstream. The fork replaced it with nested `try/catch` blocks that rely on Room
throwing `EmptyResultSetException` for a missing row. Two consequences: the lookup
path is exception-driven, and `getUserDataOrCreateNew()` writes a user-data row as
a side effect of a read.

There is also a behaviour change beyond books. `getItem()` is called by
`core/.../work/ImagesDownloaderWorker.kt:35`, so in offline mode the fork now
downloads images for offline items where upstream did not. That may be a fix, but it
is undocumented and untested.

**Proposal.** Add nullable DAO queries and make the contract explicit:

```kotlin
// The existing getMovie/getEpisode/getBook return non-null types, so Room throws
// EmptyResultSetException on a miss. Add nullable counterparts next to them.
@Query("SELECT * FROM movies WHERE id = :id") suspend fun findMovie(id: UUID): FindroidMovieDto?
@Query("SELECT * FROM episodes WHERE id = :id") suspend fun findEpisode(id: UUID): FindroidEpisodeDto?
@Query("SELECT * FROM books WHERE id = :id") suspend fun findBook(id: UUID): FindroidBookDto?
```

```kotlin
override suspend fun getItem(itemId: UUID): FindroidItem? {
    val userId = jellyfinApi.userId!!
    return database.findMovie(itemId)?.toFindroidMovie(database, userId)
        ?: database.findEpisode(itemId)?.toFindroidEpisode(database, userId)
        ?: database.findBook(itemId)?.toFindroidBook(database, userId)
}
```

Then decide the offline-image question on purpose: either keep the new behaviour and
note it in the commit message, or restore upstream's behaviour by returning `null`
for non-book items.

**Risk.** Low for the query change; the offline-image behaviour needs a decision, not
a decision by accident.

**Done when.** No `try/catch` is used for control flow in the repository, and the
offline-image behaviour is either restored or covered by a test.

### P1-5 Share the action bar between movies and books

**Problem.** `app/phone/.../presentation/book/components/BookButtonsBar.kt` (209 lines)
duplicates most of upstream's phone `ItemButtonsBar` (282 lines) — mark played,
favourite, download, cancel, delete. The two will drift; the fork has already had to
fix one copy (`ItemButtonsBar.kt:198-219`, moving `context.getString` out of a
`remember` block into `LocalResources`, because a `remember` keyed on nothing would
not recompose on a configuration change).

**Proposal.** Extract the shared pieces into `core` — `MarkPlayedButton`,
`FavoriteButton`, `DownloadButton` — and let each screen compose them.

**Risk.** Low. The upstream `ItemButtonsBar` has no book branch to preserve, so the
extraction is mechanical.

**Done when.** The download/mark/favourite logic exists once.

---

## P2 — Polish

### P2-1 The libass toggle is shown to mpv users, and defaults to on

`SettingsViewModel.kt:257-264` places `pref_player_exo_libass` right after the
backend selector with no condition, so mpv users see a switch that does nothing.
The settings model has no display condition today: `Preference` carries
`dependencies: List<PreferenceBackend<Boolean>>`
(`settings/.../models/Preference.kt:11`), which the ViewModel evaluates into an
`enabled` flag (`SettingsViewModel.kt:842`) — but that only greys a row out, and
it only works for boolean preferences. `playerBackend` is a string preference
(`AppPreferences.kt:29`), so it cannot express "only when the backend is
ExoPlayer".

Add a value-based condition, then apply it to the switch:

```kotlin
data class PreferenceSwitch(
    …,
    val visibleForBackendValues: Set<String>? = null,
)
```

The switch should be hidden when the backend is `mpv`; a disabled-but-visible row
would still be confusing next to a backend selector that changes nothing about it.

Separately, `AppPreferences.kt:31` defaults the preference to `true`, which turns
libass on for every ExoPlayer user. The cost is low — `AssHandler` loads libass
lazily, "to avoid loading libass if the played media does not have ASS tracks"
(`ass-media` 0.5.1, `AssHandler.kt:36-38`) — but a default-on change to the
rendering path deserves an explicit mention. The fork's README already advertises
"SSA/ASS (with libass rendering support)" (`README.md:39`); that line should say
that it is on by default and how to turn it off.

### P2-2 Book size is always zero

Books have no media sources, so `BaseItemDto.toFindroidBook()`
(`data/.../FindroidBook.kt:61,95`) sets the item `size` to `null` and the
fallback `FindroidSource.size` to `0L`. The pre-flight storage check in
`DownloaderImpl.kt:113` compares `stats.availableBytes < source.size`, so for
books it always passes and the download starts without a known total.

Resolve the size from the server instead — a `HEAD` request on the download URL for
`Content-Length`, or the item size from the Jellyfin API if the server reports one —
or state explicitly that books skip the pre-flight check.

### P2-3 Reader cache handling

`BookReaderActivity.prepareBookFile()` (`:313-378`) stores books under
`cacheDir/books`. The code does reuse a cached file when one is present
(`:345-355`), so a book is downloaded once per cache lifetime — but `cacheDir` is
evictable by the OS, nothing ever prunes it, and there is no conditional request:
once the file is gone, the whole book is fetched again with no `If-None-Match` or
`Last-Modified` check.

Use `filesDir` for a persistent copy, prune old entries, and send conditional
request headers so an unchanged book is not transferred again.

### P2-4 First unit tests

The repository currently contains no test sources (`find . -name "*Test.kt"` returns
nothing). Two mapping functions in the fork are good first targets:

- `BaseItemDto.toFindroidBook()` — the source fallback and author-resolution logic
- `FindroidBookDto.toFindroidBook()` — the offline path

Both are pure mapping functions. The first is `suspend` and takes a
`JellyfinRepository`, so it needs a small fake for the two methods it calls
(`getDownloadUrl`, and whatever `toFindroidImages` uses); the second takes a
`ServerDatabaseDao`, so an in-memory Room database covers it.

---

## Suggested sequence

1. **P0-1** (capability gate) and **P0-2** (debounce) first — small, isolated, and
   they stop the visible defects.
2. **P0-3**, **P0-4**, **P0-5** — reader correctness and token handling.
3. **P1-4** (nullable DAO queries) and **P1-3** (polymorphic downloads) together —
   they touch the same offline path, so land them in one branch with the movie and
   episode flows verified end to end.
4. **P1-1**, **P1-2**, **P1-5** — structural cleanups, each in its own commit.
5. **P2** — polish, opportunistically.

Keep the fork's changes in small commits that replay cleanly on upstream `main`.
Upstream is actively refactoring the same files (`DownloaderImpl`, the repository
layer, `PlayerViewModel`), and a smaller diff is what makes future syncs cheap.

---

## Verification notes

What was checked, and how:

- **Upstream comparison.** `jarnedemeulemeester/findroid` was cloned to
  `/tmp/opencode/upstream-findroid`; upstream `main` is `f7d090f7`, byte-identical
  to this fork's branch point, so `git diff f7d090f7..HEAD` is the complete fork
  delta. Upstream has no book code (`grep -ril book` matches only
  `data/.../CollectionType.kt`) and no libass in the player.
- **libass integration.** `io.github.peerless2012:ass-media:0.5.1` and its transitive
  `ass-kt:0.5.1` were downloaded from Maven Central and their sources inspected.
  `AssSubtitleParserFactory.create()` delegates to `DefaultSubtitleParserFactory` for
  every format except `TEXT_SSA`, so SRT/VTT subtitles keep working with libass
  enabled — no regression. `ass-kt` ships `libass.so`, `libasskt.so` and
  `libc++_shared.so` for all four ABIs, which is why the `pickFirsts` entries in both
  app build files are needed.
- **Download URL.** `org.jellyfin.sdk:jellyfin-api-jvm:1.9.2` sources were inspected:
  `LibraryApi.getDownloadUrl()` builds the URL with empty query parameters and no
  token, `LibraryApi.getDownload()` returns `Response<ByteArray>`, and no `api_key`
  literal exists in the `jellyfin-api`, `jellyfin-core` or `jellyfin-api-okhttp`
  artifacts.
- **Readium.** `readium-shared` and `readium-streamer` 3.4.0 sources were
  inspected: `Publication` has no `locatorFromProgression`, and
  `DefaultPublicationParser` registers `EpubPositionsService` for EPUB and
  `PerResourcePositionsService` for Divina, so `publication.positions()` is
  available for both formats the reader supports.
- **Not verified.** No JDK is installed in the environment used for this review, so
  the fork was not compiled and no runtime behaviour was observed. Every claim above
  comes from reading code, or from the published sources of the libraries involved.
