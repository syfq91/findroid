# Findroid fork — improvement plan

Status review, 2026-10-29. The original plan was written against a fork of six
commits on top of upstream `f7d090f7`; the fork has since merged upstream and added
the EPUB/CBZ reader, Divina support and the libass rework. Most of the original plan
has landed. This revision records what is closed, what is still open, and what the
newer code has exposed.

Scope is the fork's delta against upstream
[`jarnedemeulemeester/findroid`](https://github.com/jarnedemeulemeester/findroid)
`main`, plus the shared upstream code that delta touches.

## Baseline

| Fact | Value |
|---|---|
| Fork HEAD | `871388ce` (`main`) |
| Upstream `main` merged into the fork | `a451220b` (merge commit `80113009`) |
| Fork delta | `git diff a451220b..HEAD` → 66 files, +6261 / −302 |
| Fork commits since that upstream tip | 35 non-merge commits |
| Previous delta (original plan) | `f7d090f7..HEAD` → 42 files, +3239 / −41 |
| Upstream equivalents | still none for the book reader or libass in ExoPlayer |

`f7d090f7` is an ancestor of `a451220b`, so the delta has grown in both directions:
upstream moved forward, and the fork added the reader work on top of the merge.
The two headline features do not exist upstream, so there is still no reference
implementation to align with. Upstream only matters for the shared code the fork
edits: `DownloaderImpl`, `JellyfinRepository{,Impl,OfflineImpl}`,
`ServerDatabase{,Dao}`, `Converters`, `FindroidItem`, `CollectionType`,
`PlayerViewModel`, `PlayerActivity`, `PlayerScreen`, `AppPreferences`.

## Status at a glance

| ID | Item | Status | Evidence |
|---|---|---|---|
| P0-1 | Capability gate so shared code stops emitting books to TV | **Done** | `ItemCapabilities.kt`; phone `true` / TV `false`; `57325f5d` |
| P0-2 | Debounce reader progress sync | **Done** | `BookReaderActivity.kt:813-824`, 5 s debounce; `7e165dc5` |
| P0-3 | Resume from the server position | **Done** | `restoreLocator()` `:580-598`; `a9ceebcc` |
| P0-4 | Close the publication after view teardown | **Done** | `onDestroy()` `:950-957`; `fe6efa06` |
| P0-5 | Keep the access token out of reader URLs | **Partial** | reader streams via `openDownloadStream()` `:473`; `getDownloadUrl()` still appends `?api_key=` → see below |
| P0-6 | TV still lists an empty "Books" library tile | **Open** | `MediaViewModel.kt:21`; `MediaScreen.kt:75` |
| P1-1 | Keep libass types inside `player/local` | **Done** | `implementation(libs.libass.media)`; `attachSubtitleOverlay()` `PlayerViewModel.kt:238`; `cc56e4b6` |
| P1-2 | Extract player construction | **Done** | `player/local/.../ExoPlayerFactory.kt`; `94ebb5f5` |
| P1-3 | Polymorphic downloads | **Done** | `data/.../DownloadableItem.kt`; no `is FindroidBook` in `DownloaderImpl`; `9f590968` |
| P1-4 | Nullable offline item lookup | **Done (narrowed)** | `findBook()` only; `getItem()` resolves books only; `9dc9c102` |
| P1-5 | Share the item action bar | **Done** | `presentation/components/{ItemButtons,DownloadControls}.kt`; `BookButtonsBar` is 100 lines; `46ddf838` |
| P1-6 | `DownloadReceiver` scans every table to find one item | **Open** | `DownloadReceiver.kt:76-97` |
| P2-1 | libass switch is visible to mpv users and defaults on | **Open** | `SettingsViewModel.kt:284-291`; `AppPreferences.kt:31` |
| P2-2 | Book size is always zero | **Open (low)** | `FindroidBook.kt:112,162`; guard at `DownloaderImpl.kt:86` |
| P2-3 | Reader cache is unbounded and never revalidated | **Open** | `BookReaderActivity.kt:439-468` |
| P2-4 | First unit tests | **Done** | `EpubProgressTest.kt` (15), `CbzTest.kt` (15) |
| P2-5 | Local reading position is not scoped to the user | **Open** | `BookReaderActivity.kt:582,874,964` |
| P2-6 | `BookReaderActivity` is 1326 lines | **Open** | `wc -l` |
| P2-7 | `ktfmtCheck` may reject fork-added lines (unverified) | **Open** | `build.gradle.kts:21-27`; `.github/workflows/format.yaml` |

---

## Closed since the plan

Kept here because the design decisions still explain the code. Each item was
verified in the tree, not just by commit message.

### P0-1 Capability gate (`57325f5d`)

`core/.../capability/ItemCapabilities.kt` declares `supportsBooks`; the Hilt modules
set `true` for phone and `false` for TV (`app/*/.../di/AppModule.kt`). The shared
module now asks the host instead of assuming:

- `LibraryViewModel.kt:47-50` returns an empty flow for a `Books` library when
  unsupported, and `:65` omits `BaseItemKind.BOOK` from the Mixed/Folders list.
- `HomeViewModel.kt:103` filters books out of resume items and `:148` skips book
  collections.

This is the right shape: `modes/film` stays app-agnostic and the host declares what
it can open. The residual gap is the library *tile* — see P0-6.

### P0-2 Progress debounce (`7e165dc5`)

`observeNavigator()` (`:802-824`) runs two collectors under `repeatOnLifecycle`:
the local locator write stays immediate (it powers instant resume), and the server
sync is `distinctUntilChangedBy { calculateProgressTicks(...).first }` +
`debounce(PLAYBACK_PROGRESS_DEBOUNCE_MS)` where the constant is `5_000L`
(`:1053`). The activity opts in with `@OptIn(ExperimentalReadiumApi::class,
FlowPreview::class)`.

`distinctUntilChangedBy` keying on ticks (not raw progression) is the better choice:
identical progress from different locators does not re-arm the timer.

### P0-3 Server-side resume (`a9ceebcc`)

`prepareBookFile()` carries `serverPositionTicks`/`serverRuntimeTicks` into
`PreparedBook` (`:404-496`, `:501-503`). `restoreLocator()` (`:580-598`) prefers the
local locator and otherwise uses `publication.positions()`, with
`calculateProgressionFromServerTicks()` (`:1030-1038`) handling legacy
`1_000_000_000L` virtual ticks. That matches the two Jellyfin scales (EPUB
`10_000_000L`, Divina `pageCount * 10_000L`).

### P0-4 Publication close timing (`fe6efa06`)

`onDestroy()` (`:950-957`) cancels `locatorJob` and posts `publication?.close()` on
`binding.fragmentContainer`, so the navigator's coroutines finish before the
publication goes away.

### P1-1 libass stays inside `player/local` (`cc56e4b6`)

`player/local/build.gradle.kts:32` uses `implementation(libs.libass.media)`, and
`grep -r peerless2012 app/` returns nothing. Both apps call
`viewModel.attachSubtitleOverlay(playerView.subtitleView)`
(`PlayerActivity.kt:102`, `app/tv/.../PlayerScreen.kt:180`); the extension on
`SubtitleView` lives only in `PlayerViewModel.kt:238`.

### P1-2 `ExoPlayerFactory` (`94ebb5f5`)

`internal object ExoPlayerFactory` with `create(...)` now owns the builder wiring
that used to sit in `PlayerViewModel.init`.

### P1-3 Polymorphic downloads (`9f590968`)

`data/.../models/DownloadableItem.kt` defines `hasVideoExtras`,
`resolveDownloadSource(...)` and `persistDownloadMetadata(database, repository,
serverId)`; `FindroidMovie`, `FindroidEpisode` and `FindroidBook` implement it.
`grep "is FindroidBook" DownloaderImpl.kt` returns nothing.

### P1-4 Nullable offline lookup (`9dc9c102`)

`ServerDatabaseDao.findBook(id)` (`:260`) is the nullable counterpart of
`getBook(id)`. `JellyfinRepositoryOfflineImpl.getItem()` now resolves books only and
returns `null` for everything else, restoring upstream's behaviour for
`ImagesDownloaderWorker`. `findMovie`/`findEpisode` were deliberately not added —
see P1-6, where they are needed for a different caller.

The plan's original proposal (add all three `find*` queries and resolve movies,
episodes and books) was not taken because that would have kept the fork-only
movie/episode image-download behaviour. Books-only is the narrower correct fix.

### P1-5 Shared action bar (`46ddf838`)

`app/phone/.../presentation/components/ItemButtons.kt` (`MarkPlayedButton`,
`FavoriteButton`) and `DownloadControls.kt`
(`rememberDownloadControlsState`, `DownloadButton`, `DownloadProgress`,
`DownloadDialogs`) are shared with the book screen. `BookButtonsBar.kt` dropped
from 209 to 100 lines.

### P2-4 First unit tests

Two JVM test classes exist, both in `app/phone/src/test/java/dev/jdtech/jellyfin/`:

- `EpubProgressTest.kt` — 15 tests over the companion progress helpers:
  paginated vs continuous mode, URL normalization with fragment/query,
  `positions()` fallback when a chapter is not cached, chapter title resolution
  (locator title, TOC, nested TOC, reading order, book-title fallback).
- `CbzTest.kt` — 15 tests over format detection (mimetype, `ComicInfo.xml`,
  Shift-JIS names, image extensions, real EPUB fallback), format hints,
  `safeMoveOrCopy`, and opening CBZ/EPUB through Readium, including reopening with
  a saved locator and self-healing a misnamed cached file.

The helper functions are still `internal` members of the Activity's companion
object, so the tests reach across the app module to them. That is the main reason
P2-6 proposes extracting them.

---

## P0 — Open

### P0-5 The download URL still embeds the access token

**State.** The reader half is fixed: `prepareBookFile()` streams through
`jellyfinRepository.openDownloadStream(bookUUID)` (`BookReaderActivity.kt:473`),
which uses an `Authorization` header (`JellyfinRepositoryImpl.kt:585-608`).

**Corrected premise.** The original plan claimed `DownloadManager` cannot send
headers, so the URL token had to stay for downloads. That is false:
`DownloadManager.Request.addRequestHeader` exists, and `DownloaderImpl.kt:110-116`
now adds `Authorization` **and** `X-Emby-Token` on every download request
(`1bdcfe63`). The `?api_key=` in `getDownloadUrl()`
(`JellyfinRepositoryImpl.kt:563-567`) is therefore redundant.

**Problem.** `getDownloadUrl()` still supplies the remote book path (and is the
fallback when a stored source has no URL) — `FindroidBook.kt:54,60,95,111` — so the
token still travels inside a URL that can reach logs, `DownloadManager`'s database
and error reports.

**Proposal.** Drop the query parameter now that the header path exists:

```kotlin
override fun getDownloadUrl(itemId: UUID): String {
    val baseUrl = getBaseUrl().trimEnd('/')
    return "$baseUrl/Items/$itemId/Download"
}
```

Keep `getAuthorizationHeader()`/`getAccessToken()` and the headers in
`DownloaderImpl`. `openDownloadStream()` is already header-based; leave it alone.

**Risk.** Low, but verify on a device that a server which only accepts `api_key`
query auth still accepts the `X-Emby-Token` header — Jellyfin accepts both, but that
assumption should be checked against the actual server version, not just the SDK
sources. The change only affects URLs built from now on.

**Done when.** `grep -rn "api_key" --include=*.kt .` finds no fork code, and a book
download plus a reader download both succeed against a live server.

### P0-6 TV still shows a "Books" library tile

**Problem.** P0-1 filtered book *rows* inside a library, but not the library list.
`MediaViewModel.loadData()` (`MediaViewModel.kt:21`) takes
`repository.getLibraries()` unfiltered. `JellyfinRepositoryImpl.getLibraries()`
maps through `toFindroidCollection()`, which accepts every type in
`CollectionType.supported` (`CollectionType.kt:19`) — and that list contains
`Books`. The TV `MediaScreen` renders every collection it receives
(`MediaScreen.kt:75`), so a TV user sees a Books tile.

Selecting it is worse than a dead card: navigation passes
`CollectionType.Books` to `LibraryViewModel`, whose capability guard
(`LibraryViewModel.kt:47-50`) emits an empty flow, so the TV opens an empty
library screen.

**Proposal.** Filter at the same seam as `HomeViewModel`: inject
`ItemCapabilities` into `MediaViewModel` and drop book collections when
`!supportsBooks`.

```kotlin
val libraries =
    repository.getLibraries().filter {
        itemCapabilities.supportsBooks || it.type != CollectionType.Books
    }
```

Do not move this into the repository: the repository is shared with the phone app
and has no concept of a host's screens.

**Risk.** Low. Phone behaviour is unchanged (`supportsBooks = true`).

**Done when.** The TV library list has no Books tile; the phone library list still
does.

---

## P1 — Open

### P1-6 `DownloadReceiver` loads the whole library to find one item

**Problem.** The failure branch of `DownloadReceiver` (`:76-97`) builds a list of
every movie, every episode and every book, maps each to a `FindroidItem`, then does
`firstOrNull { it.id == source.itemId }` — inside a `BroadcastReceiver`, on an IO
scope, after a download has already failed.

```kotlin
items.addAll(database.getMovies().map { it.toFindroidMovie(database, repository.getUserId()) })
items.addAll(database.getEpisodes().map { it.toFindroidEpisode(...) })
items.addAll(database.getBooks().map { it.toFindroidBook(...) })
items.firstOrNull { it.id == source.itemId }?.let { downloader.deleteItem(it, ...) }
```

The mapping functions are not free: each row goes through the DTO mappers, which
resolve images/sources. On a large offline library this is a table scan plus N+1
lookups, and it is the code path that runs when the user is already in a failure
state. `deleteItem()` only needs the item id (for `deleteUserData`, trickplay and
image cleanup) and, when the item is a `DownloadableItem`, its concrete type for
`deleteDownloadMetadata`.

**Proposal.** Add the nullable by-id queries the original P1-4 described, but use
them here rather than in `getItem()`:

```kotlin
@Query("SELECT * FROM movies WHERE id = :id") suspend fun findMovie(id: UUID): FindroidMovieDto?
@Query("SELECT * FROM episodes WHERE id = :id") suspend fun findEpisode(id: UUID): FindroidEpisodeDto?
```

Then resolve three indexed lookups instead of three table scans:

```kotlin
val item =
    database.findMovie(source.itemId)?.toFindroidMovie(database, repository.getUserId())
        ?: database.findEpisode(source.itemId)?.toFindroidEpisode(database, repository.getUserId())
        ?: database.findBook(source.itemId)?.toFindroidBook(database, repository.getUserId())
item?.let { downloader.deleteItem(it, source.toFindroidSource(database)) }
```

Keep `getItem()` books-only; this is a separate caller with a different contract.

**Risk.** Low. Pure query change on an error path; the three `find*` lookups are
primary-key hits.

**Done when.** The failure branch contains no `getMovies()` / `getEpisodes()` /
`getBooks()` full scans.

---

## P2 — Open

### P2-1 The libass toggle is shown to mpv users, and defaults to on

`SettingsViewModel.kt:284-291` places `pref_player_exo_libass` directly after the
backend selector with no condition, so mpv users see a switch that does nothing.
`PreferenceSwitch` has `enabled` and `dependencies` but no visibility field, and
`Preference` dependencies are `List<PreferenceBackend<Boolean>>` — boolean-only, so
they cannot express "only when `playerBackend == exoplayer`". `playerBackend` is a
string preference (`AppPreferences.kt:29`).

Add a value-based display condition:

```kotlin
data class PreferenceSwitch(
    …,
    val visibleForBackendValues: Set<String>? = null,
)
```

and hide the row when the backend is `mpv`. A disabled-but-visible row is still
confusing next to a selector that changes nothing about it.

Separately, `AppPreferences.kt:31` defaults the preference to `true`, turning libass
on for every ExoPlayer user. The cost is low — `AssHandler` loads libass lazily
(`ass-media` 0.5.1), and `AssSubtitleParserFactory` falls back to the default parser
for non-SSA formats — but it is still a default-on change to the rendering path.
The README should say it is on by default and how to turn it off.

### P2-2 Book size is always zero

`BaseItemDto.toFindroidBook()` sets both the remote `FindroidSource.size` and the
item `size` from `mediaSources?.firstOrNull()?.size` (`FindroidBook.kt:112` and
`:162`). Books have no media sources, so the item size is `null` and the source
size is `0L`.

`1bdcfe63` added `if (source.size > 0 && stats.availableBytes < source.size)` at
`DownloaderImpl.kt:86`, so an unknown size no longer produces a misleading
storage error — but it also means the pre-flight check is simply skipped for books.
`DownloadManager` itself gets the real length from the server, so progress is fine.

Choose deliberately: resolve the size with a `HEAD` request on the download URL
(`Content-Length`) and store it on the source, or document that books skip the
pre-flight check. The current state is the second option, just undocumented.

### P2-3 Reader cache is unbounded and never revalidated

`prepareBookFile()` uses `File(cacheDir, "books")` (`BookReaderActivity.kt:439`).
It reuses any matching `$bookId.*` file (`:440-468`), which is good, but:

- `cacheDir` can be evicted by the OS, and when it is, the whole book is fetched
  again with no `If-None-Match` / `Last-Modified` check.
- Nothing prunes the directory. Cached books accumulate until the OS clears the
  cache, and a re-uploaded book is never refreshed — the stale file is reused
  forever.

Use a persistent location (`filesDir`) for the copy the reader needs, prune entries
that have not been opened in a while, and send conditional headers so an unchanged
book is not transferred again. If the server does not return validators, fall back
to a size/mtime comparison.

### P2-5 Local reading position is not scoped to the user

The local locator is stored under `pref_book_locator_<bookId>`
(`BookReaderActivity.kt:582`, `:874`, `:964`) in the single app
`SharedPreferences` file. That key has no server or user component, and Findroid
supports multiple users/servers on one device. Two users reading the same book —
or the same server re-added under a different account — share one saved position,
so opening the book jumps to a stranger's page until the server position overrides
it.

Include the user id in the key (the repository already exposes `getUserId()`):

```kotlin
private fun locatorKey(bookId: UUID) = "$KEY_LOCATOR_PREFIX${jellyfinRepository.getUserId()}_$bookId"
```

Migration is optional: on a miss the reader already falls back to the server
position (P0-3), so the worst case is one resume that starts from the server's
value. Document that in the commit message rather than writing migration code.

### P2-6 `BookReaderActivity` is 1326 lines

The activity now holds the reader UI, file preparation, the cache, format
detection, progress math, chapter title resolution and the appearance plumbing.
The companion object alone (`:959-1326`) contains the functions the new tests
exercise, which is why `app/phone` tests have to reach into activity internals.

Extract in behaviour-preserving steps, starting with the pure parts:

1. `BookProgress` — `calculateTotalTicks`, `calculateProgressTicks`,
   `calculateProgressionFromServerTicks`, `resolveChapterTitle`. Pure functions over
   `Publication`/`Locator`; move `EpubProgressTest` with them.
2. `BookFilePreparer` — the local-source/cache/download logic of
   `prepareBookFile()` plus `detectBookFormat`, `buildFormatHints`,
   `safeMoveOrCopy`; move `CbzTest` with them.
3. Leave the activity as wiring: intents, lifecycle, view binding, navigator setup.

The natural home for 1 and 2 is a new package in `modes/book` (or a small
`reader` module) so the tests stop depending on `app/phone`. Do not move the file
preparer until it no longer needs `Context` cache/file helpers it cannot get from
parameters.

**Risk.** Medium only if done in one commit. Land each extraction separately with
the existing tests unchanged, then rerun them from the new module.

### P2-7 `ktfmtCheck` may reject fork-added lines (unverified)

CI runs `./gradlew ktfmtCheck` with `kotlinLangStyle()` on every `.kt`/`.kts` change
(`.github/workflows/format.yaml`, `build.gradle.kts:21-27`). Several fork-added
lines exceed the 100-column limit in places ktfmt would normally wrap, for example:

- `app/phone/.../presentation/film/components/ProgressBar.kt:21` (104 chars)
- `app/tv/.../ui/components/ItemCard.kt:67-68` (added condition and progress calc)
- helper lines in `CbzTest.kt` (up to 125 chars)

Width alone is not proof: upstream `PlayerScreen.kt` already contains lines over
100 that CI accepts, because a formatter cannot always break them. But the
`ProgressBar`/`ItemCard` lines are assignment/condition lines that ktfmt can break,
so they are plausible failures. There is no JDK in this environment, so
`ktfmtCheck` could not be run.

**Action.** Run `./gradlew ktfmtFormat ktfmtCheck` before the next push. If the
formatter rewrites those files, fold the result into the next commit rather than a
format-only commit, and keep long string literals in tests as-is.

---

## Suggested sequence

Only open work remains:

1. **P0-6** (TV Books tile) — smallest user-visible defect, mirrors P0-1's shape.
2. **P0-5** (drop the URL token) — needs one device check against a live server.
3. **P1-6** (receiver by-id lookup) — same nullable-query pattern as `findBook`.
4. **P2-5** (user-scoped locator key) — one-line key change, no migration.
5. **P2-1**, **P2-2**, **P2-3** — settings and cache polish, independent of each other.
6. **P2-6** (activity extraction) — do it last and in small commits, with the
   existing tests moved alongside each extraction.
7. **P2-7** — run the formatter once, opportunistically.

Keep the fork's changes in small commits that replay cleanly on upstream `main`.
Upstream is actively refactoring the same files (`DownloaderImpl`, the repository
layer, `PlayerViewModel`), and a smaller diff is what makes future syncs cheap.

---

## Verification notes

How the status above was checked, and what was not checked:

- **Baseline.** `git log`/`git diff` against `a451220b`, the upstream tip merged at
  `80113009`. `git diff --stat a451220b..HEAD` gives 66 files,
  +6261 / −302; `git log --no-merges --oneline a451220b..HEAD` gives 35 commits.
  The previous plan's `f7d090f7` is an ancestor of `a451220b`, so the two deltas
  are not directly comparable.
- **Per-item status.** Each closed item was located in the working tree and its
  line numbers re-read, not inferred from the commit message. Commit SHAs are the
  ones that introduced the change; later commits touched some of the same files
  (`281d6655`, `82a874ee`, `40c81900`).
- **P0-5 premise correction.** The original plan's claim that `DownloadManager`
  cannot send headers is wrong. `DownloadManager.Request.addRequestHeader` is part
  of the Android API, and `DownloaderImpl.kt:110-116` uses it for both
  `Authorization` and `X-Emby-Token`. The remaining issue is only that
  `getDownloadUrl()` still puts the token in the query string.
- **P1-4 scope.** `grep "fun find" ServerDatabaseDao.kt` shows `findBook` only, and
  `JellyfinRepositoryOfflineImpl.getItem()` is books-only. The plan's original
  three-query proposal was deliberately narrowed; P1-6 is where the other two
  queries would actually be used.
- **P0-6.** `MediaViewModel` injects only `JellyfinRepository`;
  `getLibraries()` is unfiltered; `CollectionType.supported` includes `Books`;
  `app/tv/.../MediaScreen.kt:75` renders every collection. The phone app is
  unaffected because `supportsBooks = true`.
- **P1-6.** `DownloadReceiver.kt:76-97` was read in full: three full-table
  `getMovies()`/`getEpisodes()`/`getBooks()` reads followed by `firstOrNull` by id.
- **P2-7.** `.github/workflows/format.yaml` runs `ktfmtCheck`; `ktfmtCheck` itself
  could not be executed.
- **Not verified.** No JDK (`java`/`javac` absent) and no Android SDK
  (`ANDROID_HOME`/`ANDROID_SDK_ROOT` unset, no `android.jar`) in this environment,
  so the fork was not compiled and no tests or formatter were run. Every claim
  above comes from reading the tree, or from the published sources of the libraries
  involved (`ass-media` 0.5.1, Jellyfin SDK 1.9.2, Readium 3.4.0) as recorded in the
  original version of this plan.
