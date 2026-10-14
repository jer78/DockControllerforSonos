package io.github.jer78.dockcontroller

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// Instant updates from Home Assistant. Holds a WebSocket to HA's
// /api/websocket and subscribes (subscribe_entities) to just the widget
// (light/climate/cover) and media player entities in use; HA pushes a message the
// moment any of them changes. Like the Sonos speaker events, a message only
// says WHICH entity changed - the manager reacts by re-reading state through
// the existing REST calls, and keeps polling (more slowly) as a fallback.
class HomeAssistantEvents {
    private val client = OkHttpClient.Builder()
        // Spots a silently dropped connection (Wi-Fi blip, HA restart).
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private data class Config(val url: String, val token: String, val entityIds: List<String>)

    private var socket: WebSocket? = null
    private var config: Config? = null
    // A token HA rejected - don't hammer the server retrying it until the
    // settings change.
    private var rejected: Config? = null

    // True once HA has sent the subscription's initial state, i.e. changes
    // really are being pushed to us.
    @Volatile
    var isReceiving = false
        private set

    // Connects, or reconnects if the settings changed or the connection
    // dropped. Cheap to call on every HA refresh. onChange gets the id of
    // each entity HA reports a change for.
    @Synchronized
    fun ensure(url: String, token: String, entityIds: List<String>, onChange: (String) -> Unit) {
        val wanted = Config(url, token, entityIds.filter { it.isNotBlank() }.sorted())
        if (url.isBlank() || token.isBlank() || wanted.entityIds.isEmpty()) {
            stop()
            return
        }
        if (wanted == config && socket != null) return
        if (wanted == rejected) return
        stop()
        config = wanted

        val base = url.trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
        val wsUrl = base.replaceFirst(Regex("^http"), "ws") + "/api/websocket"
        socket = client.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = try { JSONObject(text) } catch (e: Exception) { return }
                when (message.optString("type")) {
                    "auth_required" -> webSocket.send(
                        JSONObject().put("type", "auth").put("access_token", wanted.token).toString()
                    )
                    "auth_ok" -> webSocket.send(
                        JSONObject()
                            .put("id", 1)
                            .put("type", "subscribe_entities")
                            .put("entity_ids", JSONArray(wanted.entityIds))
                            .toString()
                    )
                    "auth_invalid" -> {
                        Log.w("HAEvents", "Home Assistant rejected the token")
                        synchronized(this@HomeAssistantEvents) { rejected = wanted }
                        webSocket.close(1000, null)
                    }
                    "event" -> {
                        // subscribe_entities sends compressed diffs: "a"
                        // (added - the initial state), "c" (changed) and
                        // "r" (removed), each keyed by entity id.
                        val event = message.optJSONObject("event") ?: return
                        isReceiving = true
                        val changed = mutableSetOf<String>()
                        event.optJSONObject("a")?.keys()?.forEach { changed += it }
                        event.optJSONObject("c")?.keys()?.forEach { changed += it }
                        event.optJSONArray("r")?.let { r -> for (i in 0 until r.length()) changed += r.optString(i) }
                        changed.forEach(onChange)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w("HAEvents", "WebSocket failed: ${t.message}")
                dropped(webSocket)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                dropped(webSocket)
            }
        })
    }

    // Forget a dead socket so the next ensure() reconnects - ignoring
    // callbacks from a socket that's already been replaced.
    @Synchronized
    private fun dropped(webSocket: WebSocket) {
        if (socket === webSocket) {
            socket = null
            isReceiving = false
        }
    }

    @Synchronized
    fun stop() {
        socket?.close(1000, null)
        socket = null
        config = null
        isReceiving = false
    }
}
