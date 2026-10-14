package io.github.jer78.dockcontroller

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Result of the Settings > Home Assistant "Connect" check.
sealed class HaConnectionStatus {
    object NotTested : HaConnectionStatus()
    object Testing : HaConnectionStatus()
    data class Connected(val serverName: String, val version: String) : HaConnectionStatus()
    data class Failed(val reason: String) : HaConnectionStatus()
}

class HomeAssistantManager {
    private val client = OkHttpClient()

    private fun getFullUrl(baseUrl: String, path: String): String {
        val cleanBase = baseUrl.trimEnd('/')
        return if (cleanBase.startsWith("http")) "$cleanBase$path" else "http://$cleanBase$path"
    }

    // Checks the URL and token together against /api/config, which needs a
    // valid token and also tells us the server's name and version to show.
    // Short timeout so a wrong address fails in seconds rather than hanging
    // the Settings screen on OkHttp's default.
    fun testConnection(url: String, token: String, callback: (HaConnectionStatus) -> Unit) {
        if (url.isBlank() || token.isBlank()) {
            callback(HaConnectionStatus.Failed("Enter a server URL and token"))
            return
        }
        val request = try {
            Request.Builder()
                .url(getFullUrl(url.trim(), "/api/config"))
                .addHeader("Authorization", "Bearer ${token.trim()}")
                .build()
        } catch (e: IllegalArgumentException) {
            callback(HaConnectionStatus.Failed("That server URL isn't valid"))
            return
        }

        val testClient = client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
        testClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(HaConnectionStatus.Failed(when (e) {
                    is UnknownHostException -> "Can't find that server - check the URL"
                    is ConnectException -> "Server refused the connection - check the address and port"
                    is SocketTimeoutException -> "Timed out - is the server reachable from this network?"
                    is SSLException -> "Secure connection failed - try http:// or check the certificate"
                    else -> if (call.isCanceled() || e.message?.contains("timeout", ignoreCase = true) == true) {
                        "Timed out - is the server reachable from this network?"
                    } else {
                        "Couldn't connect: ${e.message ?: e.javaClass.simpleName}"
                    }
                }))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val status = when (response.code) {
                        200 -> try {
                            val json = JSONObject(response.body?.string().orEmpty())
                            HaConnectionStatus.Connected(
                                serverName = json.optString("location_name", "Home Assistant").ifBlank { "Home Assistant" },
                                version = json.optString("version", "")
                            )
                        } catch (e: Exception) {
                            HaConnectionStatus.Failed("Server answered, but it doesn't look like Home Assistant")
                        }
                        401, 403 -> HaConnectionStatus.Failed("Token was rejected - check the long-lived access token")
                        404 -> HaConnectionStatus.Failed("No Home Assistant API at that address - check the URL")
                        else -> HaConnectionStatus.Failed("Server returned an error (HTTP ${response.code})")
                    }
                    callback(status)
                }
            }
        })
    }

    fun fetchEntities(url: String, token: String, callback: (List<String>) -> Unit) {
        val fullUrl = getFullUrl(url, "/api/states")
        val request = Request.Builder()
            .url(fullUrl)
            .addHeader("Authorization", "Bearer $token")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("HAManager", "Fetch Entities Failed", e)
                callback(emptyList())
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    Log.e("HAManager", "Fetch Entities Error: ${response.code}")
                    callback(emptyList())
                    return
                }
                val entities = mutableListOf<String>()
                try {
                    response.body?.string()?.let {
                        val jsonArray = JSONArray(it)
                        for (i in 0 until jsonArray.length()) {
                            entities.add(jsonArray.getJSONObject(i).getString("entity_id"))
                        }
                    }
                } catch (e: Exception) {
                    Log.e("HAManager", "Parse Entities Failed", e)
                }
                callback(entities)
            }
        })
    }

    fun fetchHAMediaPlayer(url: String, token: String, entityId: String, callback: (TrackState) -> Unit) {
        if (entityId.isBlank()) {
            callback(TrackState())
            return
        }
        val fullUrl = getFullUrl(url, "/api/states/$entityId")
        Log.e("HAManager", "Fetching HA Media Player: $fullUrl")
        
        val request = Request.Builder()
            .url(fullUrl)
            .addHeader("Authorization", "Bearer $token")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("HAManager", "HA Media Fetch Failed", e)
                callback(TrackState())
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    Log.e("HAManager", "HA Media Error: ${response.code}")
                    callback(TrackState())
                    return
                }
                try {
                    response.body?.string()?.let {
                        val json = JSONObject(it)
                        val state = json.optString("state", "idle")
                        val attrs = json.optJSONObject("attributes") ?: JSONObject()
                        
                        val picture = attrs.optString("entity_picture", "")
                        val albumArtUrl = if (picture.isNotBlank()) {
                            if (picture.startsWith("http")) picture 
                            else getFullUrl(url, picture)
                        } else ""

                        Log.e("HAManager", "Parsed HA State: $state, Art: $albumArtUrl")

                        val isPlaying = state == "playing"
                        val duration = attrs.optDouble("media_duration", 0.0)
                        var position = attrs.optDouble("media_position", 0.0)
                        if (isPlaying) {
                            // HA reports media_position as of media_position_updated_at,
                            // not "right now" - add on however long has elapsed since so a
                            // one-shot fetch (no local playback clock to tick from) still
                            // lands close to the real position.
                            val updatedAt = attrs.optString("media_position_updated_at", "")
                            try {
                                val updatedAtInstant = OffsetDateTime.parse(updatedAt).toInstant()
                                val elapsedSeconds = ChronoUnit.MILLIS.between(updatedAtInstant, java.time.Instant.now()) / 1000.0
                                if (elapsedSeconds > 0) position += elapsedSeconds
                            } catch (e: Exception) {
                                // Missing/unparseable timestamp - fall back to the raw value.
                            }
                        }

                        val track = TrackState(
                            title = attrs.optString("media_title", "TV Mode"),
                            artist = attrs.optString("media_artist", "Home Assistant"),
                            album = attrs.optString("media_album_name", ""),
                            albumArtUrl = albumArtUrl,
                            isPlaying = isPlaying,
                            positionSeconds = position.coerceIn(0.0, duration).toInt(),
                            durationSeconds = duration.toInt()
                        )
                        callback(track)
                        return
                    }
                } catch (e: Exception) {
                    Log.e("HAManager", "Parse HA Media Failed", e)
                }
                callback(TrackState())
            }
        })
    }

    // Maps the same play/pause/next/previous action strings DockDashboardUI's
    // transport buttons already send for Sonos onto the equivalent HA
    // media_player service, for TV mode's HA-sourced source.
    fun sendMediaPlayerCommand(url: String, token: String, entityId: String, action: String) {
        if (entityId.isBlank()) return
        val service = when (action) {
            "play" -> "media_play"
            "pause" -> "media_pause"
            "next" -> "media_next_track"
            "previous" -> "media_previous_track"
            else -> return
        }
        val fullUrl = getFullUrl(url, "/api/services/media_player/$service")
        val json = JSONObject().put("entity_id", entityId)
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(fullUrl)
            .post(body)
            .addHeader("Authorization", "Bearer $token")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {}
        })
    }

    fun seekMediaPlayer(url: String, token: String, entityId: String, positionSeconds: Int) {
        if (entityId.isBlank()) return
        val json = JSONObject().put("entity_id", entityId).put("seek_position", positionSeconds)
        val request = Request.Builder()
            .url(getFullUrl(url, "/api/services/media_player/media_seek"))
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $token")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    fun fetchTemperatureUnit(url: String, token: String, callback: (String) -> Unit) {
        val fullUrl = getFullUrl(url, "/api/config")
        val request = Request.Builder()
            .url(fullUrl)
            .addHeader("Authorization", "Bearer $token")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { callback("°C") }
            override fun onResponse(call: Call, response: Response) {
                try {
                    if (response.isSuccessful) {
                        response.body?.string()?.let {
                            val json = JSONObject(it)
                            val unit = json.optJSONObject("unit_system")?.optString("temperature", "°C") ?: "°C"
                            callback(unit)
                            return
                        }
                    }
                } catch (e: Exception) { }
                callback("°C")
            }
        })
    }

    fun setClimateTemperature(url: String, token: String, entityId: String, temp: Double? = null, tempHigh: Double? = null, tempLow: Double? = null) {
        if (entityId.isBlank()) return
        val fullUrl = getFullUrl(url, "/api/services/climate/set_temperature")
        val json = JSONObject().put("entity_id", entityId)
        temp?.let { json.put("temperature", it) }
        tempHigh?.let { json.put("target_temp_high", it) }
        tempLow?.let { json.put("target_temp_low", it) }
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(fullUrl)
            .post(body)
            .addHeader("Authorization", "Bearer $token")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {}
        })
    }

    fun setClimateHvacMode(url: String, token: String, entityId: String, hvacMode: String) {
        if (entityId.isBlank()) return
        val fullUrl = getFullUrl(url, "/api/services/climate/set_hvac_mode")
        val json = JSONObject().put("entity_id", entityId).put("hvac_mode", hvacMode)
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(fullUrl)
            .post(body)
            .addHeader("Authorization", "Bearer $token")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {}
        })
    }

    fun setClimateFanMode(url: String, token: String, entityId: String, fanMode: String) {
        if (entityId.isBlank()) return
        val fullUrl = getFullUrl(url, "/api/services/climate/set_fan_mode")
        val json = JSONObject().put("entity_id", entityId).put("fan_mode", fanMode)
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(fullUrl)
            .post(body)
            .addHeader("Authorization", "Bearer $token")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {}
        })
    }

    fun setClimatePresetMode(url: String, token: String, entityId: String, presetMode: String) {
        if (entityId.isBlank()) return
        val fullUrl = getFullUrl(url, "/api/services/climate/set_preset_mode")
        val json = JSONObject().put("entity_id", entityId).put("preset_mode", presetMode)
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(fullUrl)
            .post(body)
            .addHeader("Authorization", "Bearer $token")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {}
        })
    }

    // Reads the current state of each widget entity in parallel, parsed by
    // its domain (light./climate./cover.). Entities that fail to load are
    // left out of the result, so their widget waits rather than showing
    // made-up values.
    fun fetchEntityStates(
        url: String,
        token: String,
        entityIds: List<String>,
        temperatureUnit: String,
        callback: (Map<String, HaEntityState>) -> Unit
    ) {
        val ids = entityIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) {
            callback(emptyMap())
            return
        }
        val results = java.util.concurrent.ConcurrentHashMap<String, HaEntityState>()
        val latch = CountDownLatch(ids.size)

        ids.forEach { entityId ->
            val request = Request.Builder()
                .url(getFullUrl(url, "/api/states/$entityId"))
                .addHeader("Authorization", "Bearer $token")
                .build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { latch.countDown() }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (response.isSuccessful) {
                                val json = JSONObject(response.body?.string().orEmpty())
                                parseEntityState(entityId, json, temperatureUnit)?.let { results[entityId] = it }
                            }
                        } catch (e: Exception) {
                            Log.e("HAManager", "Parse $entityId failed", e)
                        }
                    }
                    latch.countDown()
                }
            })
        }

        Thread {
            try {
                latch.await(5, TimeUnit.SECONDS)
            } catch (e: Exception) { }
            callback(HashMap(results))
        }.start()
    }

    private fun parseEntityState(entityId: String, json: JSONObject, temperatureUnit: String): HaEntityState? {
        val attrs = json.optJSONObject("attributes") ?: JSONObject()
        val state = json.optString("state")
        val name = attrs.optString("friendly_name", "").ifBlank { entityId }
        return when (HaWidgetType.fromDomain(entityId.substringBefore('.'))) {
            HaWidgetType.LIGHT -> parseLight(entityId, name, state, attrs)
            HaWidgetType.CLIMATE -> parseClimate(entityId, name, state, attrs, temperatureUnit)
            HaWidgetType.COVER -> parseCover(entityId, name, state, attrs)
            null -> null
        }
    }

    private fun JSONObject.stringList(key: String): List<String> =
        optJSONArray(key)?.let { array -> (0 until array.length()).map { array.getString(it) } } ?: emptyList()

    private fun JSONObject.doubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

    private fun JSONObject.intOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun parseLight(entityId: String, name: String, state: String, attrs: JSONObject): HaLightState {
        val rgb = attrs.optJSONArray("rgb_color")
        return HaLightState(
            entityId = entityId,
            name = name,
            isOn = state == "on",
            brightness = attrs.optInt("brightness", 0),
            colorModes = attrs.stringList("supported_color_modes"),
            rgbColor = if (rgb != null && rgb.length() == 3) listOf(rgb.getInt(0), rgb.getInt(1), rgb.getInt(2)) else null,
            colorTempKelvin = attrs.intOrNull("color_temp_kelvin"),
            minColorTempKelvin = attrs.optInt("min_color_temp_kelvin", 2000),
            maxColorTempKelvin = attrs.optInt("max_color_temp_kelvin", 6500)
        )
    }

    private fun parseClimate(entityId: String, name: String, state: String, attrs: JSONObject, temperatureUnit: String): HaClimateState {
        val hvacAction = attrs.optString("hvac_action", "idle")
        return HaClimateState(
            entityId = entityId,
            name = name,
            climateTemp = attrs.optString("current_temperature", ""),
            hvacState = state.ifBlank { "off" },
            isFanRunning = hvacAction == "fan" || hvacAction == "cooling" || hvacAction == "heating",
            targetTemp = attrs.doubleOrNull("temperature"),
            targetTempHigh = attrs.doubleOrNull("target_temp_high"),
            targetTempLow = attrs.doubleOrNull("target_temp_low"),
            currentHumidity = attrs.intOrNull("current_humidity"),
            hvacAction = hvacAction,
            hvacModes = attrs.stringList("hvac_modes"),
            fanMode = attrs.optString("fan_mode", ""),
            fanModes = attrs.stringList("fan_modes"),
            presetMode = attrs.optString("preset_mode", ""),
            presetModes = attrs.stringList("preset_modes"),
            minTemp = attrs.optDouble("min_temp", 10.0),
            maxTemp = attrs.optDouble("max_temp", 35.0),
            tempStep = attrs.optDouble("target_temp_step", 0.5),
            temperatureUnit = temperatureUnit
        )
    }

    private fun parseCover(entityId: String, name: String, state: String, attrs: JSONObject): HaCoverState =
        HaCoverState(
            entityId = entityId,
            name = name,
            state = state.ifBlank { "unknown" },
            deviceClass = attrs.optString("device_class", ""),
            position = attrs.intOrNull("current_position"),
            supportedFeatures = attrs.optInt("supported_features", 0)
        )

    // Fire-and-forget call to a HA service on one entity, e.g.
    // ("cover", "open_cover"). Pushed state changes refresh the widget.
    private fun callService(url: String, token: String, domain: String, service: String, entityId: String, data: JSONObject = JSONObject()) {
        if (entityId.isBlank()) return
        val body = data.put("entity_id", entityId).toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(getFullUrl(url, "/api/services/$domain/$service"))
            .post(body)
            .addHeader("Authorization", "Bearer $token")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    fun openCover(url: String, token: String, entityId: String) =
        callService(url, token, "cover", "open_cover", entityId)

    fun closeCover(url: String, token: String, entityId: String) =
        callService(url, token, "cover", "close_cover", entityId)

    fun stopCover(url: String, token: String, entityId: String) =
        callService(url, token, "cover", "stop_cover", entityId)

    fun setCoverPosition(url: String, token: String, entityId: String, position: Int) =
        callService(url, token, "cover", "set_cover_position", entityId, JSONObject().put("position", position.coerceIn(0, 100)))

    fun setLightBrightness(url: String, token: String, entityId: String, brightness: Int) {
        if (entityId.isBlank()) return
        val fullUrl = getFullUrl(url, "/api/services/light/turn_on")
        val json = JSONObject()
            .put("entity_id", entityId)
            .put("brightness", brightness)
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(fullUrl)
            .post(body)
            .addHeader("Authorization", "Bearer $token")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {}
        })
    }

    fun setLightOn(url: String, token: String, entityId: String, on: Boolean) {
        if (entityId.isBlank()) return
        val fullUrl = getFullUrl(url, "/api/services/light/turn_${if (on) "on" else "off"}")
        val json = JSONObject().put("entity_id", entityId)
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(fullUrl)
            .post(body)
            .addHeader("Authorization", "Bearer $token")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {}
        })
    }

    fun setLightColorRgb(url: String, token: String, entityId: String, r: Int, g: Int, b: Int) {
        if (entityId.isBlank()) return
        val fullUrl = getFullUrl(url, "/api/services/light/turn_on")
        val json = JSONObject()
            .put("entity_id", entityId)
            .put("rgb_color", JSONArray().put(r).put(g).put(b))
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(fullUrl)
            .post(body)
            .addHeader("Authorization", "Bearer $token")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {}
        })
    }

    fun setLightColorTempKelvin(url: String, token: String, entityId: String, kelvin: Int) {
        if (entityId.isBlank()) return
        val fullUrl = getFullUrl(url, "/api/services/light/turn_on")
        val json = JSONObject()
            .put("entity_id", entityId)
            .put("color_temp_kelvin", kelvin)
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(fullUrl)
            .post(body)
            .addHeader("Authorization", "Bearer $token")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {}
        })
    }
}
