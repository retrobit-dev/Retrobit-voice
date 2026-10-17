package dev.retrobit.voice

import dev.retrobit.voice.assistant.CurrentWeatherClient
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentWeatherClientTest {

    @Test
    fun `formats current weather in Indonesian with attribution`() {
        val report = CurrentWeatherClient().formatReport(
            location = "Jepara, Jawa Tengah, Indonesia",
            forecastJson = """{"current":{"temperature_2m":28.5,"relative_humidity_2m":76,"apparent_temperature":31.0,"weather_code":3,"wind_speed_10m":8.0}}""",
            language = "id-ID",
        )

        assertTrue(report.contains("Jepara"))
        assertTrue(report.contains("mendung"))
        assertTrue(report.contains("28,5 derajat Celsius"))
        assertTrue(report.contains("kelembapan 76 persen"))
        assertTrue(report.contains("Open-Meteo"))
    }

    @Test
    fun `formats current weather in English`() {
        val report = CurrentWeatherClient().formatReport(
            location = "London, England, United Kingdom",
            forecastJson = """{"current":{"temperature_2m":12.0,"relative_humidity_2m":84,"weather_code":61,"wind_speed_10m":10.0}}""",
            language = "en-GB",
        )

        assertTrue(report.contains("Current weather estimate for London"))
        assertTrue(report.contains("light rain"))
        assertTrue(report.contains("12 degrees Celsius"))
    }
}
