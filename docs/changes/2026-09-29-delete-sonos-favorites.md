# Delete Sonos Favorites from Cover Flow (Mode B)

**Date:** 2026-09-29

## What changed

In **Mode B (Albums, Playlists & Radio)**, tapping the **X** on the centered
album card now asks for confirmation. If you confirm, the favorite is
**permanently deleted from the Sonos system**, including from the Sonos app.

**Mode A (History)** is unchanged: there, the X still only removes the card
from the local play history.

### Behavior before this change

In both modes, the X called `removeAlbumFromHistory()`. In Mode B that only
hid the card in memory, and the favorite came back on the next favorites
refresh (a mode switch, Settings save, or the roughly 30-minute poll).

## How it works

SoCo (0.31.x) can list favorites but has **no delete method**. The new bridge
function calls the speaker's UPnP `ContentDirectory.DestroyObject` action
directly on the favorite's `FV:2/<n>` object ID. SoCo's own
`remove_sonos_playlist()` uses the same call.

Safeguards:
- Only IDs starting with `FV:2/` are accepted. Items found through the S2
  "category dive" in `get_sonos_favorites` have service IDs and are refused
  with a "can't be deleted from here" message.
- Before deleting, the favorites list is fetched again, and the ID must still
  exist and still carry the title the user confirmed. `FV:2` IDs can shift if
  favorites are changed elsewhere, such as in the Sonos app.
- The confirmation prompt is drawn inside the screen rather than as an
  `AlertDialog`, because the Cover Flow also runs inside `SonosDreamService`,
  which isn't an Activity. While the prompt is open, auto-advance is paused.
  Tapping outside the prompt cancels it.
- The card is removed right away. If the delete fails, the list is reloaded
  from the speaker so the card comes back, and a Toast explains why.

## Files touched

| File | Change |
|---|---|
| `app/src/main/python/sonos_bridge.py` | Added `_display_title()` and `delete_sonos_favorite(item_id, expected_title, speaker_name)`. |
| `app/src/main/java/.../SonosDashboardManager.kt` | Added `deleteSonosFavorite(item)` and the `android.widget.Toast` import. |
| `app/src/main/java/.../DashboardUI.kt` (`AmbientClockScreen`) | New params `isFavoritesMode` / `onDeleteFavorite`; `pendingFavoriteDelete` / `confirmedFavoriteDelete` state plus a fallback effect; the X branches on the mode; the drop animation calls `onDeleteFavorite` for confirmed favorites; auto-advance pauses while the prompt is open; confirmation overlay at the end of the root `Box`. |
| `app/src/main/java/.../MainActivity.kt` | Collects `coverFlowMode`; passes `isFavoritesMode` and `onDeleteFavorite` to `AmbientClockScreen`. |
| `app/src/main/java/.../SonosDreamService.kt` | Same wiring as `MainActivity`. |

## How to revert

Everything is in a single commit, "Delete Sonos Favorites from Cover Flow X
button". To undo it:

```sh
git log --oneline --grep "Delete Sonos Favorites"   # find the commit hash
git revert <hash>
```

To revert by hand instead: remove the two new `AmbientClockScreen` arguments
in `MainActivity.kt` and `SonosDreamService.kt`, then delete the new code
listed above. The new `AmbientClockScreen` parameters have defaults
(`false` / `{}`), so removing only the call-site arguments is enough to turn
the feature off. The X then falls back to the old "hide card" behavior in
both modes.

**Note:** reverting the code does **not** restore favorites that were already
deleted. Re-add them from the Sonos app.

## Testing notes

- Try it first on a throwaway favorite. `DestroyObject` on `FV:2` IDs is
  community-known behavior, not a documented Sonos API.
- Check both hosts: the app's Cover Flow and the screensaver (Dream).
- Check that Mode A's X still only removes the history entry.
