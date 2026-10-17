package dev.retrobit.voice.assistant

import dev.retrobit.voice.ai.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Current, city-based weather lookup. No device-location permission is requested. */
class CurrentWeatherClient {

    private val client by lazy {
        Http.client.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    suspend fun currentWeather(location: String, language: String): String = withContext(Dispatchers.IO) {
        val requestedLocation = location.trim().takeIf { it.isNotEmpty() }
            ?: throw WeatherLookupException("Sebutkan nama kota, misalnya: cuaca di Jepara.")
        val encoded = URLEncoder.encode(requestedLocation, StandardCharsets.UTF_8.name())
        val placeJson = get("$GEOCODING_URL?name=$encoded&count=5&language=id&format=json")
        val place = parsePlace(placeJson, requestedLocation)
        val forecastJson = get(
            "$FORECAST_URL?latitude=${place.latitude}&longitude=${place.longitude}" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m" +
                "&timezone=auto&temperature_unit=celsius&wind_speed_unit=kmh",
        )
        formatReport(place.displayName, forecastJson, language)
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Retrobit-Voice")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw WeatherLookupException("Layanan cuaca sedang tidak tersedia (HTTP ${response.code}).")
            }
            response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw WeatherLookupException("Layanan cuaca tidak memberikan data.")
        }
    }

    private fun parsePlace(json: String, requestedLocation: String): Place {
        val results = parseJson(json)?.get("results")?.jsonArray
            ?: throw WeatherLookupException("Kota \"$requestedLocation\" tidak ditemukan.")
        val candidates = results.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
        val place = candidates.firstOrNull {
            it.string("name").equals(requestedLocation, ignoreCase = true)
        } ?: candidates.firstOrNull()
            ?: throw WeatherLookupException("Kota \"$requestedLocation\" tidak ditemukan. Coba sebutkan nama kota yang lebih lengkap.")

        val latitude = place.number("latitude")
            ?: throw WeatherLookupException("Koordinat kota \"$requestedLocation\" tidak tersedia.")
        val longitude = place.number("longitude")
            ?: throw WeatherLookupException("Koordinat kota \"$requestedLocation\" tidak tersedia.")
        val displayName = listOfNotNull(
            place.string("name"),
            place.string("admin1"),
            place.string("country"),
        ).distinct().joinToString(", ").ifBlank { requestedLocation }
        return Place(latitude, longitude, displayName)
    }

    internal fun formatReport(location: String, forecastJson: String, language: String): String {
        val current = parseJson(forecastJson)?.get("current")?.jsonObject
            ?: throw WeatherLookupException("Data cuaca terkini tidak tersedia.")
        val temperature = current.number("temperature_2m")
            ?: throw WeatherLookupException("Suhu terkini tidak tersedia.")
        val locale = Locale.forLanguageTag(language).takeIf { it.language != "und" } ?: Locale.getDefault()
        val isIndonesian = locale.language == "id"
        val number = NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = 1
            minimumFractionDigits = 0
        }
        val condition = weatherDescription(current.int("weather_code"), isIndonesian)
        val apparent = current.number("apparent_temperature")
        val humidity = current.int("relative_humidity_2m")
        val wind = current.number("wind_speed_10m")

        return buildString {
            if (isIndonesian) {
                append("Perkiraan cuaca terkini di ").append(location).append(": ")
                append(condition).append(", suhu ").append(number.format(temperature)).append(" derajat Celsius")
                if (apparent != null && abs(apparent - temperature) >= 1.0) {
                    append(", terasa seperti ").append(number.format(apparent)).append(" derajat")
                }
                if (humidity != null) append(", kelembapan ").append(humidity).append(" persen")
                if (wind != null) append(", angin ").append(number.format(wind)).append(" kilometer per jam")
                append(". Data Open-Meteo.")
            } else {
                append("Current weather estimate for ").append(location).append(": ")
                append(condition).append(", ").append(number.format(temperature)).append(" degrees Celsius")
                if (apparent != null && abs(apparent - temperature) >= 1.0) {
                    append(", feels like ").append(number.format(apparent)).append(" degrees")
                }
                if (humidity != null) append(", humidity ").append(humidity).append(" percent")
                if (wind != null) append(", wind ").append(number.format(wind)).append(" kilometers per hour")
                append(". Data from Open-Meteo.")
            }
        }
    }

    private fun parseJson(json: String): JsonObject? = try {
        Http.json.parseToJsonElement(json).jsonObject
    } catch (error: SerializationException) {
        throw WeatherLookupException("Format data cuaca tidak dikenali.", error)
    } catch (error: IllegalArgumentException) {
        throw WeatherLookupException("Format data cuaca tidak dikenali.", error)
    }

    private fun weatherDescription(code: Int?, indonesian: Boolean): String = when (code) {
        0 -> if (indonesian) "cerah" else "clear"
        1 -> if (indonesian) "umumnya cerah" else "mostly clear"
        2 -> if (indonesian) "cerah berawan" else "partly cloudy"
        3 -> if (indonesian) "mendung" else "overcast"
        45, 48 -> if (indonesian) "berkabut" else "foggy"
        51 -> if (indonesian) "gerimis ringan" else "light drizzle"
        53 -> if (indonesian) "gerimis" else "drizzle"
        55 -> if (indonesian) "gerimis lebat" else "dense drizzle"
        56, 57 -> if (indonesian) "gerimis beku" else "freezing drizzle"
        61 -> if (indonesian) "hujan ringan" else "light rain"
        63 -> if (indonesian) "hujan sedang" else "moderate rain"
        65 -> if (indonesian) "hujan lebat" else "heavy rain"
        66, 67 -> if (indonesian) "hujan beku" else "freezing rain"
        71 -> if (indonesian) "salju ringan" else "light snow"
        73 -> if (indonesian) "salju sedang" else "moderate snow"
        75 -> if (indonesian) "salju lebat" else "heavy snow"
        77 -> if (indonesian) "butiran salju" else "snow grains"
        80 -> if (indonesian) "hujan sebentar" else "light rain showers"
        81 -> if (indonesian) "hujan sebentar sedang" else "moderate rain showers"
        82 -> if (indonesian) "hujan sebentar lebat" else "heavy rain showers"
        85, 86 -> if (indonesian) "hujan salju" else "snow showers"
        95 -> if (indonesian) "badai petir" else "thunderstorm"
        96, 99 -> if (indonesian) "badai petir dengan hujan es" else "thunderstorm with hail"
        else -> if (indonesian) "kondisi langit tidak diketahui" else "unknown conditions"
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.number(key: String): Double? = string(key)?.toDoubleOrNull()

    private fun JsonObject.int(key: String): Int? = string(key)?.toIntOrNull()

    private data class Place(val latitude: Double, val longitude: Double, val displayName: String)

    private class WeatherLookupException(message: String, cause: Throwable? = null) : IOException(message, cause)

    private companion object {
        const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"
        const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
    }
}
