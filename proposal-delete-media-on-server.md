# Proposal: Add a button to delete media from the Jellyfin server

No code has been changed. This is a design/implementation proposal.

## Goal

Add a user-facing, permission-gated button that permanently deletes a media item from the Jellyfin server, with a confirmation dialog and safe navigation/error handling.

## Scope

- **Phone app detail screens**: Movie, Episode, Season, Show, Book.
- **TV app detail screens**: Movie, Show (second phase).
- **Data layer**: repository contract + online/offline implementations.
- **UI layer**: shared button + confirmation dialog + state-driven navigation.

## Backend capability

Jellyfin supports item deletion via `DELETE /Items/{itemId}`. The bundled SDK already exposes it:

- `JellyfinApi.libraryApi.deleteItem(itemId: UUID): Response<Unit>`  
  (verified in `jellyfin-api-jvm 1.9.4`)

Permission comes from the current user’s policy:

- `UserDto.policy.enableContentDeletion` (and `isAdministrator`)
- fetched through `JellyfinApi.userApi.getCurrentUser()`

## Data layer changes

### `JellyfinRepository`

Add two methods:

```kotlin
suspend fun deleteServerItem(itemId: UUID)
suspend fun canDeleteMedia(): Boolean
```

### `JellyfinRepositoryImpl`

- `deleteServerItem`:
  ```kotlin
  withContext(Dispatchers.IO) {
      jellyfinApi.libraryApi.deleteItem(itemId)
  }
  ```
- `canDeleteMedia`:
  ```kotlin
  val user = jellyfinApi.userApi.getCurrentUser().content
  val policy = user.policy
  policy?.isAdministrator == true || policy?.enableContentDeletion == true
  ```

### `JellyfinRepositoryOfflineImpl`

- `deleteServerItem` throws `IOException("Deleting from the server is unavailable in offline mode")`.
- `canDeleteMedia` returns `false`.

This makes the feature automatically unavailable in offline mode.

## ViewModel changes

Add to `MovieState`, `EpisodeState`, `ShowState`, `SeasonState`, and `BookState`:

```kotlin
val canDeleteFromServer: Boolean = false
val isDeletingFromServer: Boolean = false
val deletedFromServer: Boolean = false
```

Add a `DeleteFromServer` action to each corresponding action sealed interface.

ViewModel handling pattern:

```kotlin
is MovieAction.DeleteFromServer -> {
    viewModelScope.launch {
        _state.update { it.copy(isDeletingFromServer = true, error = null) }
        try {
            repository.deleteServerItem(movieId)
            _state.update {
                it.copy(isDeletingFromServer = false, deletedFromServer = true)
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(isDeletingFromServer = false, error = e)
            }
        }
    }
}
```

During load, populate permission:

```kotlin
val canDelete = try { repository.canDeleteMedia() } catch (_: Exception) { false }
```

and include it in the emitted state.

## UI changes

### Phone

1. **`ItemButtonsBar`** and **`BookButtonsBar`**:
   - Add parameters:
     ```kotlin
     canDeleteFromServer: Boolean = false,
     onDeleteFromServerClick: () -> Unit = {},
     ```
   - Render a `FilledTonalIconButton` using `CoreR.drawable.ic_trash` when `canDeleteFromServer` is true.
   - Disable it while `isDeletingFromServer` is true.

2. **New `DeleteServerItemDialog`** composable, analogous to `DeleteDownloadDialog`, using new strings.

3. **Detail screens**:
   - Manage local dialog state:
     ```kotlin
     var showDeleteServerDialog by rememberSaveable { mutableStateOf(false) }
     ```
   - Pass:
     ```kotlin
     canDeleteFromServer = state.canDeleteFromServer,
     onDeleteFromServerClick = { showDeleteServerDialog = true }
     ```
   - Show `DeleteServerItemDialog` when requested.
   - On confirm, trigger the ViewModel action and dismiss.
   - Observe `state.deletedFromServer` and `navigateBack()`.

### TV

- Add a conditional “Delete from server” button to Movie and Show screens.
- Add `navigateBack` handling for `deletedFromServer`.
- Update `NavigationRoot` to pass `goBack()` where needed.

## Strings

Add to `core/src/main/res/values/strings.xml` (and translations later):

```xml
<string name="delete_from_server">Delete from server</string>
<string name="delete_from_server_message">
    This will permanently delete this item from the Jellyfin server. Are you sure?
</string>
```

Existing `unknown_error` can cover failure messaging.

## Navigation and cleanup

- On success, navigate back / pop the back stack.
- Existing lists will refresh on next navigation.
- Optional follow-up: if the deleted item was downloaded, also remove local download metadata to avoid orphaned files.

## Error handling

- On failure, set `state.error`; existing `ErrorDialog` displays the error.
- Button remains available for retry.

## Permission UX

- Button is only shown when `canDeleteFromServer` is true.
- In offline mode, repository returns `false`, so the button is hidden.

## Risks

- Destructive operation: confirmation dialog must be clear and not easily dismissed accidentally.
- Deleting shows/seasons can remove many files server-side.
- Orphaned local downloads if server item is deleted; propose cleanup as follow-up.
- Extra `getCurrentUser()` call per detail load; acceptable for detail screens.

## Implementation order

1. Data layer: repository methods + offline behavior.
2. ViewModels + state fields + actions.
3. Phone UI: `ItemButtonsBar`, `BookButtonsBar`, and `DeleteServerItemDialog`.
4. Phone detail screens: Movie and Episode first, then Season, Show, Book.
5. Strings.
6. TV UI.
7. Tests.

## Tests

- Repository permission parsing (`administrator` or `enableContentDeletion`).
- ViewModel success sets `deletedFromServer`; failure sets `error`.
- Compose: button visibility by permission/offline, dialog confirm/cancel, navigation on success.
- Manual: delete movie/episode/season/show/book; verify server removal and back navigation; verify unauthorized/offline users do not see the button.
