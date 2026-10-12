import soco
from soco.discovery import by_name
from soco.data_structures import to_didl_string
import re
import requests

# Persistent hardware map to fix grouping and discovery hangs
HARDWARE_MAP = {}

def get_speaker_instance(name=None):
    global HARDWARE_MAP
    if name:
        key = name.lower()
        if key in HARDWARE_MAP:
            try:
                device = soco.SoCo(HARDWARE_MAP[key])
                device.get_current_transport_info()
                return device
            except: del HARDWARE_MAP[key]
        try:
            device = by_name(name)
            if device:
                HARDWARE_MAP[name.lower()] = device.ip_address
                return device
        except: pass
    speakers = soco.discover(timeout=1)
    if speakers:
        for s in speakers:
            HARDWARE_MAP[s.player_name.lower()] = s.ip_address
        if name:
            key = name.lower()
            for s in speakers:
                if s.player_name.lower() == key: return s
        return list(speakers)[0]
    return None

def get_high_res_art(artist, album, current_uri=""):
    if current_uri:
        if "mzstatic.com" in current_uri:
            return re.sub(r'/\d+x\d+bb', '/600x600bb', current_uri)
        if "images-amazon.com" in current_uri:
            return re.sub(r'_SL\d+._', '_SL600_', current_uri)
    return current_uri

def _all_favorites(master):
    # SoCo stops at 100 items unless asked for the complete result.
    return list(master.music_library.get_sonos_favorites(complete_result=True))

def _browse_all(master, ml_item):
    # browse() has no complete_result option and also stops at 100 items,
    # so page through until everything in the folder has been fetched.
    items = []
    while True:
        page = master.music_library.browse(ml_item=ml_item, start=len(items), max_items=100)
        items.extend(page)
        if len(page) == 0 or len(items) >= page.total_matches:
            return items

def get_sonos_favorites(speaker_name=None, filter_types="albums,playlists,radio", limit=100):
    """
    HA-STYLE DISCOVERY: Captures native SoCo references for 100% reliable replay.
    limit caps how many (alphabetically first) items are returned; 0 = all.
    """
    try:
        device = get_speaker_instance(speaker_name)
        if not device: return [{"title": "DIAG", "artist": "Speaker Offline"}]
        master = device.group.coordinator

        # 1. Fetch raw favorites (The HA Method)
        all_raw = _all_favorites(master)

        requested = filter_types.split(',')
        results = []
        seen_ids = set()

        def process_item(item):
            item_id = item.item_id
            if item_id in seen_ids: return

            # Metadata Classification
            # DidlFavorite wrappers always report their OWN generic class
            # ("object.itemobject.item.sonos-favorite"), never the class of
            # what they actually point to. Resolve to the real underlying
            # item (same as HA does) before classifying it.
            try:
                resolved = item.reference
            except Exception:
                resolved = item
            item_class = resolved.item_class.lower()
            is_p = 'playlist' in item_class or 'SA:7' in item_id
            is_r = 'audiobroadcast' in item_class or 'radio' in item_class
            # Individual track favorites ("object.item.audioItem.musicTrack")
            # aren't playlists or radio either, so without this they'd fall
            # through to "anything else defaults to album" below and show up
            # mixed in with real albums.
            is_track = 'musictrack' in item_class
            is_a = not (is_p or is_r or is_track)

            keep = False
            if 'albums' in requested and is_a: keep = True
            if 'playlists' in requested and is_p: keep = True
            if 'radio' in requested and is_r: keep = True

            if keep:
                seen_ids.add(item_id)
                # ROBUST NAME EXTRACTION
                title = item.title
                # Ignore generic service titles
                if title.lower() in ['sonos', 'apple music', 'amazon music']:
                    title = "Unknown Album"

                # Sonos never stores a creator/artist field for album
                # favorites (confirmed empty on both the wrapper and the
                # resolved reference), so this is unknown until the Kotlin
                # side backfills it by observing the album actually play.
                # An artist-type favorite is the one case where we already
                # know it for free - the title IS the artist name.
                if 'musicartist' in item_class:
                    artist = title
                elif is_p:
                    # A playlist mixes tracks from many artists, so there's
                    # no single creator to show.
                    artist = "Various Artists"
                else:
                    artist = str(getattr(item, 'creator', "Unknown Artist"))
                    if artist.lower() in ['sonos', 'apple music', 'amazon music']: artist = "Unknown Artist"

                art = str(getattr(item, 'album_art_uri', ""))
                if art and art.startswith('/'): art = f"http://{master.ip_address}:1400{art}"
                art = get_high_res_art(artist, title, art)

                results.append({
                    "title": title,
                    "uri": item_id, # Store native ID for HA replay
                    "meta": item_class,
                    "artist": artist,
                    "art": art
                })

        for fav in all_raw:
            try:
                # S2 Category Dive
                if 'container' in fav.item_class.lower() and not getattr(fav, 'uri', None):
                    try:
                        sub = _browse_all(master, fav)
                        for s in sub: process_item(s)
                    except: process_item(fav)
                else:
                    process_item(fav)
            except: continue

        if not results: return [{"title": "DIAG", "artist": "No items matched filters."}]
        results.sort(key=lambda x: x['title'].lower())
        return results[:limit] if limit > 0 else results
    except Exception as e:
        return [{"title": "DIAG", "artist": f"Crawl Error: {str(e)}"}]

def _display_title(title):
    # Mirrors get_sonos_favorites' title cleanup, so a title shown in the UI
    # can be compared against the raw favorite it came from.
    return "Unknown Album" if title.lower() in ['sonos', 'apple music', 'amazon music'] else title

def delete_sonos_favorite(item_id, expected_title="", speaker_name=None):
    """
    PERMANENTLY removes a Sonos Favorite from the whole Sonos system (it
    disappears from the Sonos app too). SoCo has no delete helper, so this
    calls the ContentDirectory DestroyObject action directly - the same one
    SoCo's own remove_sonos_playlist() uses.
    """
    try:
        # Only real top-level favorites ("FV:2/<n>") can be destroyed.
        # Items found by get_sonos_favorites' S2 category dive carry their
        # service's IDs instead, and must never be passed to DestroyObject.
        if not item_id or not item_id.startswith("FV:2/"):
            return "FAIL: This item can't be deleted from here"

        device = get_speaker_instance(speaker_name)
        if not device: return "FAIL: Speaker Offline"
        master = device.group.coordinator

        # FV:2 IDs aren't guaranteed stable across changes made elsewhere
        # (e.g. the Sonos app), so re-check the ID still points at the
        # favorite the user actually confirmed before destroying anything.
        favs = master.music_library.get_sonos_favorites(complete_result=True)
        match = next((f for f in favs if f.item_id == item_id), None)
        if match is None:
            return "FAIL: Favorite no longer exists"
        if expected_title and _display_title(match.title) != expected_title:
            return "FAIL: Favorites changed - refresh and try again"

        master.contentDirectory.DestroyObject([("ObjectID", item_id)])
        return "OK"
    except Exception as e:
        return f"FAIL: {str(e)}"

# Per-coordinator cache of the previous/next queue tracks, so the queue is
# only re-read when the current track actually changes rather than on every
# 2s poll. Keyed by queue position + track URI + title.
_ADJACENT_CACHE = {}

def _adjacent_tracks(master, info):
    """
    The tracks either side of the current one in the Sonos queue, as
    {"prev": {...} or None, "next": {...} or None}. Only meaningful while
    the queue itself is playing - radio, TV, line-in, Spotify Connect and
    AirPlay don't play from the queue (which may still hold an old, unrelated
    queue), and in shuffle the queue order isn't the play order - so both
    are None in those cases rather than showing the wrong tracks.
    """
    key = (info.get('playlist_position', ''), info.get('uri', ''), info.get('title', ''))
    cached = _ADJACENT_CACHE.get(master.uid)
    if cached and cached[0] == key:
        return cached[1]

    result = {"prev": None, "next": None}
    try:
        pos = int(info.get('playlist_position') or 0)
        media_uri = master.get_current_media_info().get('uri', '')
        if pos > 0 and media_uri.startswith('x-rincon-queue:') and 'SHUFFLE' not in master.play_mode:
            start = max(pos - 2, 0)
            items = list(master.get_queue(start=start, max_items=3, full_album_art_uri=True))
            current = pos - 1 - start

            def describe(track):
                return {
                    "title": getattr(track, 'title', '') or '',
                    "artist": getattr(track, 'creator', '') or '',
                    "album": getattr(track, 'album', '') or '',
                    "art": get_high_res_art('', '', getattr(track, 'album_art_uri', '') or '')
                }

            if 0 <= current - 1 < len(items):
                result["prev"] = describe(items[current - 1])
            if current + 1 < len(items):
                result["next"] = describe(items[current + 1])
    except Exception:
        pass

    _ADJACENT_CACHE[master.uid] = (key, result)
    return result

def get_now_playing(speaker_name=None):
    device = get_speaker_instance(speaker_name)
    if not device: return {"error": "No Sonos device found"}
    try:
        master = device.group.coordinator
        info = master.get_current_track_info()
        trans = master.get_current_transport_info()
        group_members = []
        for m in master.group.members:
            # Bonded home theater satellites (surrounds, sub) share the
            # room's player_name and even report is_coordinator via that
            # name match, but they're marked invisible and aren't a real
            # separate room - skip them or the wrong (satellite) model/name
            # ends up representing the whole room.
            if not getattr(m, 'is_visible', True):
                continue
            try:
                model = m.get_speaker_info().get('model_name', '')
            except Exception:
                model = ''
            group_members.append({
                "name": m.player_name,
                "volume": m.volume,
                "is_coordinator": m.uid == master.uid,
                "model": model
            })
        current_art = info.get('album_art', '')
        if current_art.startswith('/'): current_art = f"http://{master.ip_address}:1400{current_art}"
        state = trans.get('current_transport_state', 'STOPPED')
        # Sonos keeps reporting the TV stream's URI even after the transport
        # goes to STOPPED (TV turned off, nothing queued next) - only treat
        # it as TV while actually playing, so idle detection (not playing,
        # not TV) can still trigger once the TV source goes quiet.
        is_tv = state in ('PLAYING', 'TRANSITIONING') and master.music_source_from_uri(info.get('uri', '')) == 'TV'
        adjacent = {"prev": None, "next": None} if is_tv else _adjacent_tracks(master, info)
        prev_track = adjacent["prev"] or {}
        next_track = adjacent["next"] or {}
        return {
            "title": info.get('title', 'Not Playing'),
            "artist": info.get('artist', 'Sonos'),
            "album": info.get('album', 'Station'),
            "album_art": current_art,
            "state": state,
            "volume": device.volume,
            "mute": bool(device.mute),
            "group_members": group_members,
            "is_tv": is_tv,
            # "H:MM:SS" - live streams/radio report "NOT_IMPLEMENTED" or
            # "0:00:00" for one or both, so these are left as raw strings
            # for the Kotlin side to parse defensively rather than assumed
            # always-valid here.
            "position": info.get('position', ''),
            "duration": info.get('duration', ''),
            # Blank when there's no previous/next track to show (see
            # _adjacent_tracks).
            "prev_title": prev_track.get("title", ""),
            "prev_artist": prev_track.get("artist", ""),
            "prev_album": prev_track.get("album", ""),
            "prev_art": prev_track.get("art", ""),
            "next_title": next_track.get("title", ""),
            "next_artist": next_track.get("artist", ""),
            "next_art": next_track.get("art", "")
        }
    except Exception as e:
        return {"error": str(e)}

# Sonos-specific AVTransport error codes SoCo reports as just "Unknown error".
SONOS_UPNP_ERRORS = {
    "800": "speaker isn't the group coordinator, or rejected the content",
    "804": "Sonos couldn't use this favorite's music service link - try re-adding it in the Sonos app",
}

def _describe_error(e):
    code = str(getattr(e, 'error_code', '') or '')
    if code:
        return f"UPnP Error {code}: {SONOS_UPNP_ERRORS.get(code, getattr(e, 'error_description', '') or 'Unknown error')}"
    return str(e)

def play_album_smart(*args):
    """
    HA PLAYBACK REPLICATION: Uses native reference injection.
    This is the only way to avoid 714 and 800 errors for good.
    """
    try:
        fav_id = args[2] if len(args) > 2 else ""
        speaker_name = args[4] if len(args) > 4 else None

        device = get_speaker_instance(speaker_name)
        if not device: return "ERR: Speaker Offline"
        master = device.group.coordinator

        if fav_id:
            # 1. LIVE RESOLUTION (The HA Secret)
            # Fetch the high-level SoCo objects to get the 'reference' attribute
            all_favs = _all_favorites(master)
            target = next((f for f in all_favs if f.item_id == fav_id), None)

            if not target:
                for f in all_favs:
                    if 'container' in f.item_class.lower():
                        try:
                            sub = _browse_all(master, f)
                            target = next((s for s in sub if s.item_id == fav_id), None)
                            if target: break
                        except: continue

            if not target:
                # Sonos requires a reference it resolved itself (via Browse/
                # Favorites) to reliably queue and play cloud-service content -
                # a captured/replayed URI+metadata, even when byte-accurate,
                # gets rejected (UPnP error 800). So only real Sonos Favorite
                # IDs are playable here; anything else can't be resolved.
                return "FAIL: Not a valid Sonos Favorite"

            # 2. HA NATIVE COMMANDS
            try:
                import time

                # Crucial Fix: HA uses favorite.reference to get the real,
                # playable item (correct class + account tokens). The
                # DidlFavorite wrapper's own item_class is always the generic
                # "object.itemobject.item.sonos-favorite" string, so checking
                # it (as before) never detects radio/streams and everything
                # fell through to add_to_queue() -> error 714.
                # Some resolved references come back with an empty <res>
                # list (no resources) - prefer whichever candidate actually
                # has one, instead of blindly using .reference and crashing
                # with "list index out of range".
                media_item = None
                try:
                    ref = target.reference
                    if getattr(ref, 'resources', None):
                        media_item = ref
                except Exception:
                    pass
                if media_item is None and getattr(target, 'resources', None):
                    media_item = target
                if media_item is None:
                    return "FAIL: No playable resource for this favorite"

                # Crucial Fix #2: resMD often omits the <desc> element (the
                # account-linking token for cloud services like Apple/Amazon
                # Music). When that happens SoCo sets reference.desc = None,
                # which serializes to an EMPTY <desc> tag. AddURIToQueue still
                # succeeds in that case, but Sonos can't authenticate to the
                # service, so the track silently fails to stream - this is
                # the "returns SUCCESS but nothing plays" case. Borrow the
                # favorite's own desc token (it always has the real one) when
                # the resolved reference is missing it.
                own_desc = getattr(media_item, 'desc', None)
                candidates = []
                if media_item is not target and not own_desc:
                    fav_desc = getattr(target, 'desc', None)
                    if fav_desc:
                        media_item.desc = fav_desc
                        candidates.append(("reference", media_item.get_uri(), to_didl_string(media_item)))
                        media_item.desc = own_desc
                candidates.append(("reference (own desc)", media_item.get_uri(), to_didl_string(media_item)))
                # Last resort: replay the favorite exactly as the Sonos app
                # does - its own <res> URI plus the raw resMD Sonos stored
                # with it, with no SoCo re-serialization in between. Sonos
                # rejects some re-serialized references (UPnP error 804)
                # that it accepts in this original form.
                fav_res = getattr(target, 'resources', None)
                fav_md = getattr(target, 'resource_meta_data', None)
                if fav_res and fav_md:
                    candidates.append(("favorite resMD", fav_res[0].uri, fav_md))

                # HA-STYLE SOURCE DETECTION: classify by the resolved URI,
                # exactly what music_source_from_uri() uses under the hood.
                source = master.music_source_from_uri(candidates[0][1])

                errors = []
                started = False
                for label, uri, meta in candidates:
                    try:
                        if source == 'RADIO':
                            master.play_uri(uri, meta=meta, title=media_item.title)
                        else:
                            # Containers/tracks (Albums/Playlists/Songs): Clear -> Add REFERENCE -> Play
                            master.clear_queue()
                            master.avTransport.AddURIToQueue([
                                ("InstanceID", 0),
                                ("EnqueuedURI", uri),
                                ("EnqueuedURIMetaData", meta),
                                ("DesiredFirstTrackNumberEnqueued", 0),
                                ("EnqueueAsNext", 0),
                            ])

                            # Large cloud playlists/albums can take a moment to
                            # resolve into actual queue tracks server-side. Poll
                            # instead of guessing a fixed delay.
                            waited = 0.0
                            while master.queue_size in (None, 0) and waited < 4.0:
                                time.sleep(0.25)
                                waited += 0.25

                            master.play_from_queue(0)
                            master.play()
                        started = True
                        break
                    except Exception as ce:
                        errors.append(f"{label}: {_describe_error(ce)}")
                if not started:
                    # Report the first attempt's error - it's the real
                    # rejection; later ones are just the fallbacks failing too.
                    return f"FAIL: {errors[0].split(': ', 1)[1]}" + (f" (+{len(errors) - 1} fallback(s) failed)" if len(errors) > 1 else "")

                # Verify playback actually started. AVTransport calls can
                # return success even when the speaker silently rejects or
                # skips content it can't resolve (stale account tokens,
                # unsupported item, etc.) - don't report SUCCESS if nothing
                # is actually playing.
                waited = 0.0
                state = ""
                while waited < 3.0:
                    time.sleep(0.3)
                    waited += 0.3
                    state = master.get_current_transport_info().get('current_transport_state', '')
                    if state in ('PLAYING', 'TRANSITIONING'):
                        return "SUCCESS"

                return f"FAIL: Speaker did not start playback (state: {state or 'UNKNOWN'})"
            except Exception as pe:
                return f"FAIL: {_describe_error(pe)}"
        return "FAIL: No ID"
    except Exception as e:
        return f"CRITICAL ERR: {str(e)}"

def control_playback(action, speaker_name=None):
    try:
        device = get_speaker_instance(speaker_name)
        if not device: return False
        master = device.group.coordinator
        if action == "play": master.play()
        elif action == "pause": master.pause()
        elif action == "next": master.next()
        elif action == "previous": master.previous()
        return True
    except: return False

def set_volume(vol, speaker_name=None):
    try:
        device = by_name(speaker_name) if speaker_name else None
        if not device: device = get_speaker_instance(speaker_name)
        if not device: return False
        device.volume = int(vol)
        return True
    except: return False

def set_mute(muted, speaker_name=None):
    try:
        device = by_name(speaker_name) if speaker_name else None
        if not device: device = get_speaker_instance(speaker_name)
        if not device: return False
        device.mute = bool(muted)
        return True
    except: return False

def get_audio_settings(speaker_name=None):
    """
    Speaker-hardware EQ/features (Night Sound, Speech Enhancement, Bass,
    Treble, Loudness, subwoofer presence). Night Sound/Speech Enhancement
    are soundbar-only in SoCo (night_mode/dialog_mode return None on
    anything else) - each field is left None whenever the coordinator
    doesn't support it, rather than guessing a default.
    """
    try:
        device = get_speaker_instance(speaker_name)
        if not device: return {"error": "No Sonos device found"}
        master = device.group.coordinator

        result = {
            "is_soundbar": bool(master.is_soundbar),
            "has_subwoofer": False,
            "night_mode": None,
            "dialog_mode": None,
            "bass": None,
            "treble": None,
            "loudness": None,
            "sub_enabled": None
        }

        try:
            result["has_subwoofer"] = bool(master.has_subwoofer)
        except Exception:
            pass

        if result["has_subwoofer"]:
            try:
                result["sub_enabled"] = bool(master.sub_enabled)
            except Exception:
                pass

        if result["is_soundbar"]:
            try:
                result["night_mode"] = bool(master.night_mode)
            except Exception:
                pass
            try:
                result["dialog_mode"] = bool(master.dialog_mode)
            except Exception:
                pass

        try:
            result["bass"] = int(master.bass)
        except Exception:
            pass
        try:
            result["treble"] = int(master.treble)
        except Exception:
            pass
        try:
            result["loudness"] = bool(master.loudness)
        except Exception:
            pass

        return result
    except Exception as e:
        return {"error": str(e)}

def set_night_mode(enabled, speaker_name=None):
    try:
        device = get_speaker_instance(speaker_name)
        if not device: return False
        device.group.coordinator.night_mode = bool(enabled)
        return True
    except: return False

def set_dialog_mode(enabled, speaker_name=None):
    try:
        device = get_speaker_instance(speaker_name)
        if not device: return False
        device.group.coordinator.dialog_mode = bool(enabled)
        return True
    except: return False

def set_bass(value, speaker_name=None):
    try:
        device = get_speaker_instance(speaker_name)
        if not device: return False
        device.group.coordinator.bass = int(value)
        return True
    except: return False

def set_treble(value, speaker_name=None):
    try:
        device = get_speaker_instance(speaker_name)
        if not device: return False
        device.group.coordinator.treble = int(value)
        return True
    except: return False

def set_loudness(enabled, speaker_name=None):
    try:
        device = get_speaker_instance(speaker_name)
        if not device: return False
        device.group.coordinator.loudness = bool(enabled)
        return True
    except: return False

def set_sub_enabled(enabled, speaker_name=None):
    try:
        device = get_speaker_instance(speaker_name)
        if not device: return False
        device.group.coordinator.sub_enabled = bool(enabled)
        return True
    except: return False

def get_speaker_groups(speaker_name=None):
    try:
        all_spks = soco.discover(timeout=1)
        target = get_speaker_instance(speaker_name)
        results = []
        if all_spks and target:
            t_coord_name = target.group.coordinator.player_name
            for s in all_spks:
                if s.player_name == target.player_name: continue
                try:
                    model = s.get_speaker_info().get('model_name', '')
                except Exception:
                    model = ''
                results.append({
                    "name": s.player_name,
                    "is_joined": s.group.coordinator.player_name == t_coord_name,
                    "ip": s.ip_address,
                    "model": model
                })
        return results
    except: return []

def toggle_speaker_join(master_name, other_name, join=True):
    try:
        master = by_name(master_name)
        other = by_name(other_name)
        if join: other.join(master)
        else: other.unjoin()
        return True
    except: return False

def discover_speakers():
    try:
        spks = soco.discover(timeout=1)
        return [s.player_name for s in spks] if spks else []
    except: return []
