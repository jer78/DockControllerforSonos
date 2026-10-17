package io.github.jer78.dockcontroller

import android.text.format.DateFormat
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// Weather condition icons are Google's Material Symbols (res/drawable/
// ic_weather_*.xml), since the older Material icon set has no partly
// cloudy, rain, hail or sleet.
@DrawableRes
fun weatherIconRes(condition: String, night: Boolean): Int = when (condition) {
    "sunny" -> if (night) R.drawable.ic_weather_clear_night else R.drawable.ic_weather_sunny
    "clear-night" -> R.drawable.ic_weather_clear_night
    "partlycloudy" -> if (night) R.drawable.ic_weather_partly_cloudy_night else R.drawable.ic_weather_partly_cloudy_day
    "cloudy" -> R.drawable.ic_weather_cloud
    "fog" -> R.drawable.ic_weather_foggy
    "rainy" -> R.drawable.ic_weather_rainy
    "pouring" -> R.drawable.ic_weather_rainy_heavy
    "snowy" -> R.drawable.ic_weather_weather_snowy
    "snowy-rainy" -> R.drawable.ic_weather_weather_mix
    "hail" -> R.drawable.ic_weather_weather_hail
    "lightning", "lightning-rainy" -> R.drawable.ic_weather_thunderstorm
    "windy", "windy-variant" -> R.drawable.ic_weather_air
    "exceptional" -> R.drawable.ic_weather_warning
    else -> R.drawable.ic_weather_cloud
}

fun weatherTint(condition: String, night: Boolean): Color = when (condition) {
    "sunny" -> if (night) Color(0xFFC5CAE9) else Color(0xFFFFC107)
    "clear-night" -> Color(0xFFC5CAE9)
    "partlycloudy" -> if (night) Color(0xFFC5CAE9) else Color(0xFFFFD54F)
    "cloudy", "fog" -> Color(0xFFB0BEC5)
    "rainy" -> Color(0xFF4FC3F7)
    "pouring" -> Color(0xFF29B6F6)
    "snowy", "snowy-rainy", "hail" -> Color(0xFFE1F5FE)
    "lightning", "lightning-rainy" -> Color(0xFFFFD54F)
    "windy", "windy-variant" -> Color(0xFF80CBC4)
    "exceptional" -> Color(0xFFFF7043)
    else -> Color.White
}

fun weatherConditionLabel(condition: String): String = when (condition) {
    "sunny" -> "Sunny"
    "clear-night" -> "Clear"
    "partlycloudy" -> "Partly cloudy"
    "cloudy" -> "Cloudy"
    "fog" -> "Fog"
    "rainy" -> "Rain"
    "pouring" -> "Heavy rain"
    "snowy" -> "Snow"
    "snowy-rainy" -> "Sleet"
    "hail" -> "Hail"
    "lightning" -> "Lightning"
    "lightning-rainy" -> "Thunderstorms"
    "windy" -> "Windy"
    "windy-variant" -> "Windy and cloudy"
    "exceptional" -> "Exceptional"
    else -> condition.replaceFirstChar { it.uppercase() }
}

// Whether a moment is after dark, from sun.sun's next rising and setting
// (their time of day stands in for any day's), else a 6am-6pm guess.
private fun isNightAt(time: Long, sun: HaSunState?): Boolean {
    val zone = ZoneId.systemDefault()
    val tod = Instant.ofEpochMilli(time).atZone(zone).toLocalTime()
    val rising = sun?.nextRising?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
    val setting = sun?.nextSetting?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
    if (rising == null || setting == null) return tod.hour < 6 || tod.hour >= 18
    val day = if (rising < setting) tod >= rising && tod < setting else tod >= rising || tod < setting
    return !day
}

private fun HaWeatherState.isNightNow(): Boolean = sun?.let { !it.isUp } ?: isNightAt(System.currentTimeMillis(), null)

// 18.0 -> "18", 18.25 -> "18.3"
private fun Double.formatted(decimals: Int = 1): String {
    val rounded = Math.round(this * Math.pow(10.0, decimals.toDouble())) / Math.pow(10.0, decimals.toDouble())
    return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else "%.${decimals}f".format(rounded)
}

private fun Double.degrees(): String = "${roundToInt()}°"

private fun compassPoint(bearing: Double): String =
    COMPASS_POINTS[(((bearing % 360) + 360) % 360 / 22.5).roundToInt() % 16]

private fun uvLevel(uv: Double): String = when {
    uv < 3 -> "Low"
    uv < 6 -> "Moderate"
    uv < 8 -> "High"
    uv < 11 -> "Very high"
    else -> "Extreme"
}

private val TempCold = Color(0xFF4FC3F7)
private val TempWarm = Color(0xFFFFB74D)
private val RainColor = Color(0xFF4FC3F7)

// Now Playing's chip: the condition's icon and the temperature. Opens the
// forecast pop-up only when the entity has forecasts to show.
@Composable
fun WeatherWidget(weather: HaWeatherState, onClick: () -> Unit) {
    val night = weather.isNightNow()
    val hasForecast = !weather.forecasts.isEmpty
    GlassSurface(
        fill = Color.Transparent,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(enabled = hasForecast, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painterResource(weatherIconRes(weather.condition, night)),
                contentDescription = "${weather.name}: ${weatherConditionLabel(weather.condition)}",
                tint = weatherTint(weather.condition, night),
                modifier = Modifier.size(24.dp)
            )
            weather.temperature?.let {
                Spacer(modifier = Modifier.width(6.dp))
                Text(it.degrees(), color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private enum class ForecastTab(val label: String) { HOURLY("Hourly"), DAILY("Daily"), TWICE_DAILY("Day & Night") }

// Close to full screen, so every hour and day fits with room to read it:
// current conditions and details across the top, then whichever forecasts
// the entity has, one tab each.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeatherDialog(weather: HaWeatherState, onDismiss: () -> Unit) {
    val forecasts = weather.forecasts
    val tabs = buildList {
        if (forecasts.hourly.isNotEmpty()) add(ForecastTab.HOURLY)
        if (forecasts.daily.isNotEmpty()) add(ForecastTab.DAILY)
        if (forecasts.twiceDaily.isNotEmpty()) add(ForecastTab.TWICE_DAILY)
    }
    var selected by remember { mutableStateOf(tabs.firstOrNull()) }
    if (selected !in tabs) selected = tabs.firstOrNull()
    val night = weather.isNightNow()
    val tint = weatherTint(weather.condition, night)

    GlassDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        GlassCard(modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.92f)) {
            Column(modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        painterResource(weatherIconRes(weather.condition, night)),
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(88.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.widthIn(min = 180.dp)) {
                        Text(weather.name, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            weather.temperature?.let { "${it.formatted()}${weather.temperatureUnit}" } ?: "--",
                            color = Color.White,
                            fontSize = 56.sp,
                            fontWeight = FontWeight.Light,
                            lineHeight = 60.sp
                        )
                        Text(weatherConditionLabel(weather.condition), color = tint, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        // Today's high and low, from the first daily forecast.
                        forecasts.daily.firstOrNull()?.let { today ->
                            val parts = listOfNotNull(today.temperature?.let { "H ${it.degrees()}" }, today.tempLow?.let { "L ${it.degrees()}" })
                            if (parts.isNotEmpty()) Text(parts.joinToString("  "), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                        }
                    }
                    Spacer(Modifier.width(24.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        WeatherDetails(weather)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                Spacer(Modifier.height(20.dp))
                if (tabs.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tabs.forEach { tab ->
                            val isSelected = tab == selected
                            Surface(
                                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { selected = tab }
                            ) {
                                Text(
                                    tab.label,
                                    color = if (isSelected) Color.Black else Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (selected) {
                        ForecastTab.HOURLY -> HourlyForecast(forecasts.hourly, weather)
                        ForecastTab.DAILY -> DailyForecast(forecasts.daily, weather, twiceDaily = false)
                        ForecastTab.TWICE_DAILY -> DailyForecast(forecasts.twiceDaily, weather, twiceDaily = true)
                        null -> {}
                    }
                }

                if (weather.attribution.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(weather.attribution, color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// The current-conditions tiles - only the ones this entity reports.
@Composable
private fun WeatherDetails(weather: HaWeatherState) {
    val unit = weather.temperatureUnit
    weather.apparentTemperature?.let {
        DetailTile(rememberVectorPainter(Icons.Default.Thermostat), "Feels like", "${it.formatted()}$unit")
    }
    weather.humidity?.let {
        DetailTile(painterResource(R.drawable.ic_weather_humidity_percentage), "Humidity", "${it.roundToInt()}%")
    }
    weather.dewPoint?.let {
        DetailTile(painterResource(R.drawable.ic_weather_dew_point), "Dew point", "${it.formatted()}$unit")
    }
    weather.windSpeed?.let { speed ->
        val direction = weather.windBearing?.let { " ${compassPoint(it)}" }.orEmpty()
        DetailTile(
            rememberVectorPainter(Icons.Default.Navigation),
            "Wind",
            "${speed.formatted()} ${weather.windSpeedUnit}$direction".trim(),
            // The arrow points the way the wind blows - bearing is where it
            // comes from.
            iconRotation = weather.windBearing?.let { it.toFloat() + 180f }
        )
    }
    weather.windGustSpeed?.let {
        DetailTile(painterResource(R.drawable.ic_weather_air), "Gusts", "${it.formatted()} ${weather.windSpeedUnit}".trim())
    }
    weather.pressure?.let {
        DetailTile(rememberVectorPainter(Icons.Default.Compress), "Pressure", "${it.formatted()} ${weather.pressureUnit}".trim())
    }
    weather.uvIndex?.let {
        DetailTile(painterResource(R.drawable.ic_weather_sunny), "UV index", "${it.formatted()} · ${uvLevel(it)}")
    }
    weather.visibility?.let {
        DetailTile(rememberVectorPainter(Icons.Default.Visibility), "Visibility", "${it.formatted()} ${weather.visibilityUnit}".trim())
    }
    weather.cloudCoverage?.let {
        DetailTile(painterResource(R.drawable.ic_weather_cloud), "Cloud cover", "${it.roundToInt()}%")
    }
}

@Composable
private fun DetailTile(icon: Painter, label: String, value: String, iconRotation: Float? = null) {
    Surface(
        color = Color.White.copy(alpha = 0.08f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.width(158.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(22.dp).rotate(iconRotation ?: 0f)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                Text(value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private val HourWidth = 76.dp

// A side-scrolling strip, one column per hour: time, icon, temperature
// (with a curve drawn through them), chance and amount of rain, wind and
// humidity. Rows a forecast doesn't provide are left out.
@Composable
private fun HourlyForecast(hours: List<HaForecast>, weather: HaWeatherState) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val timeFormat = remember {
        DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h a", Locale.getDefault())
    }
    val dayFormat = remember { DateTimeFormatter.ofPattern("EEE", Locale.getDefault()) }
    val temps = hours.map { it.temperature }
    val hasRain = hours.any { it.precipitationProbability != null || (it.precipitation ?: 0.0) > 0.0 }
    val hasWind = hours.any { it.windSpeed != null }
    val hasHumidity = hours.any { it.humidity != null }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            HourRow(hours) { i, hour ->
                val local = Instant.ofEpochMilli(hour.time).atZone(zone)
                val newDay = i == 0 || local.toLocalDate() != Instant.ofEpochMilli(hours[i - 1].time).atZone(zone).toLocalDate()
                Text(
                    if (newDay) dayLabel(local.toLocalDate(), dayFormat) else "",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(local.format(timeFormat), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp))
            HourRow(hours) { _, hour ->
                val night = isNightAt(hour.time, weather.sun)
                Icon(
                    painterResource(weatherIconRes(hour.condition, night)),
                    contentDescription = weatherConditionLabel(hour.condition),
                    tint = weatherTint(hour.condition, night),
                    modifier = Modifier.size(34.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            HourRow(hours) { _, hour ->
                Text(hour.temperature?.degrees() ?: "", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            TemperatureCurve(temps, modifier = Modifier.width(HourWidth * hours.size).height(56.dp))
            if (hasRain) {
                Spacer(Modifier.height(8.dp))
                HourRow(hours) { _, hour -> RainCell(hour, weather.precipitationUnit) }
            }
            if (hasWind) {
                Spacer(Modifier.height(10.dp))
                HourRow(hours) { _, hour -> WindCell(hour, weather.windSpeedUnit) }
            }
            if (hasHumidity) {
                Spacer(Modifier.height(10.dp))
                HourRow(hours) { _, hour ->
                    hour.humidity?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painterResource(R.drawable.ic_weather_humidity_percentage),
                                contentDescription = "Humidity",
                                tint = Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(2.dp))
                            Text("$it%", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HourRow(hours: List<HaForecast>, cell: @Composable ColumnScope.(Int, HaForecast) -> Unit) {
    Row {
        hours.forEachIndexed { i, hour ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(HourWidth)
            ) {
                cell(i, hour)
            }
        }
    }
}

// A line through each hour's temperature, centered on its column and
// colored from cool to warm across the day's range.
@Composable
private fun TemperatureCurve(temps: List<Double?>, modifier: Modifier = Modifier) {
    val known = temps.filterNotNull()
    if (known.size < 2) {
        Spacer(modifier)
        return
    }
    val min = known.min()
    val max = known.max()
    Canvas(modifier = modifier) {
        val step = size.width / temps.size
        val pad = 8.dp.toPx()
        fun y(t: Double): Float =
            if (max == min) size.height / 2 else pad + ((max - t) / (max - min)).toFloat() * (size.height - pad * 2)
        val points = temps.mapIndexedNotNull { i, t -> t?.let { Offset(step * i + step / 2, y(it)) } }
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 1 until points.size) {
                val prev = points[i - 1]
                val cur = points[i]
                val midX = (prev.x + cur.x) / 2
                cubicTo(midX, prev.y, midX, cur.y, cur.x, cur.y)
            }
        }
        val brush = Brush.verticalGradient(listOf(TempWarm, TempCold), startY = pad, endY = size.height - pad)
        drawPath(path, brush, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        points.forEach { drawCircle(brush, radius = 4.dp.toPx(), center = it) }
    }
}

@Composable
private fun RainCell(forecast: HaForecast, unit: String) {
    val probability = forecast.precipitationProbability
    val amount = forecast.precipitation?.takeIf { it > 0.0 }
    // A small bar whose height and strength follow the chance of rain.
    Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.height(32.dp)) {
        if (probability != null && probability > 0) {
            Box(
                modifier = Modifier
                    .width(18.dp)
                    .height((32 * probability / 100f).coerceAtLeast(3f).dp)
                    .background(RainColor.copy(alpha = 0.3f + 0.6f * probability / 100f), RoundedCornerShape(4.dp))
            )
        }
    }
    Text(probability?.let { "$it%" } ?: "", color = RainColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Text(amount?.let { "${it.formatted()} $unit".trim() } ?: "", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
}

@Composable
private fun WindCell(forecast: HaForecast, unit: String) {
    val speed = forecast.windSpeed ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        forecast.windBearing?.let {
            Icon(
                Icons.Default.Navigation,
                contentDescription = "From ${compassPoint(it)}",
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(14.dp).rotate(it.toFloat() + 180f)
            )
            Spacer(Modifier.width(2.dp))
        }
        Text(speed.formatted(0), color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
    }
    Text(unit, color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
}

private fun dayLabel(date: LocalDate, format: DateTimeFormatter): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> date.format(format)
    }
}

// One row per day (or half-day): when, icon and condition, rain, wind, then
// the low-to-high range as a bar on a scale shared by every row.
@Composable
private fun DailyForecast(days: List<HaForecast>, weather: HaWeatherState, twiceDaily: Boolean) {
    val zone = ZoneId.systemDefault()
    val dayFormat = remember { DateTimeFormatter.ofPattern("EEE d", Locale.getDefault()) }
    val allTemps = days.flatMap { listOfNotNull(it.tempLow, it.temperature) }
    val scaleMin = allTemps.minOrNull() ?: 0.0
    val scaleMax = allTemps.maxOrNull() ?: 0.0
    val hasRain = days.any { it.precipitationProbability != null || (it.precipitation ?: 0.0) > 0.0 }
    val hasWind = days.any { it.windSpeed != null }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(days) { i, day ->
            if (i > 0) HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            val date = Instant.ofEpochMilli(day.time).atZone(zone).toLocalDate()
            val night = twiceDaily && day.isDaytime == false
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().height(64.dp)
            ) {
                Column(modifier = Modifier.width(120.dp)) {
                    Text(dayLabel(date, dayFormat), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    if (twiceDaily && day.isDaytime != null) {
                        Text(if (day.isDaytime) "Day" else "Night", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                }
                Icon(
                    painterResource(weatherIconRes(day.condition, night)),
                    contentDescription = null,
                    tint = weatherTint(day.condition, night),
                    modifier = Modifier.size(36.dp)
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    weatherConditionLabel(day.condition),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(150.dp)
                )
                if (hasRain) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(130.dp)) {
                        val probability = day.precipitationProbability
                        val amount = day.precipitation?.takeIf { it > 0.0 }
                        if (probability != null || amount != null) {
                            Icon(
                                painterResource(R.drawable.ic_weather_rainy),
                                contentDescription = null,
                                tint = RainColor.copy(alpha = 0.8f),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                listOfNotNull(probability?.let { "$it%" }, amount?.let { "${it.formatted()} ${weather.precipitationUnit}".trim() })
                                    .joinToString(" · "),
                                color = RainColor,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
                if (hasWind) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(120.dp)) {
                        day.windSpeed?.let { speed ->
                            Icon(
                                Icons.Default.Navigation,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp).rotate((day.windBearing ?: 0.0).toFloat() + 180f)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("${speed.formatted(0)} ${weather.windSpeedUnit}".trim(), color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                        }
                    }
                }
                Text(
                    day.tempLow?.degrees() ?: "",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 17.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(52.dp)
                )
                Spacer(Modifier.width(12.dp))
                TemperatureRangeBar(day.tempLow, day.temperature, scaleMin, scaleMax, modifier = Modifier.weight(1f).height(8.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    day.temperature?.degrees() ?: "",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(52.dp)
                )
            }
        }
    }
}

// Low-to-high as a bar on the shared scale, colored by where it falls
// between the coldest and warmest of the whole forecast. A forecast with
// only one temperature shows as a dot.
@Composable
private fun TemperatureRangeBar(low: Double?, high: Double?, scaleMin: Double, scaleMax: Double, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(Color.White.copy(alpha = 0.1f), cornerRadius = radius)
        val from = low ?: high ?: return@Canvas
        val to = high ?: low ?: return@Canvas
        val span = (scaleMax - scaleMin).takeIf { it > 0 } ?: 1.0
        val x1 = ((from - scaleMin) / span).toFloat() * size.width
        val x2 = ((to - scaleMin) / span).toFloat() * size.width
        val start = minOf(x1, x2).coerceAtMost(size.width - size.height)
        val width = (kotlin.math.abs(x2 - x1)).coerceAtLeast(size.height)
        drawRoundRect(
            brush = Brush.horizontalGradient(listOf(TempCold, TempWarm), startX = 0f, endX = size.width),
            topLeft = Offset(start, 0f),
            size = Size(width, size.height),
            cornerRadius = radius
        )
    }
}
