package dev.jdtech.jellyfin.core.presentation.capability

/**
 * Declares which optional item kinds the host app is able to open.
 *
 * The `modes/film` module is shared between the phone and TV apps, but not every app implements a
 * destination for every item kind. The phone app ships a book reader, while the TV app does not.
 * Shared view models ask the host app what it supports so they can leave unsupported items out of
 * the library and home rows instead of rendering cards that do nothing when selected.
 */
interface ItemCapabilities {
    /** Whether the host app can open a [dev.jdtech.jellyfin.models.FindroidBook]. */
    val supportsBooks: Boolean
}
