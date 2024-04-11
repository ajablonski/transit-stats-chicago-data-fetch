package com.github.ajablonski

import com.github.ajablonski.serdes.PartialLocationResponse
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.logging.Logger

class RailDataFetcher(private val httpClient: HttpClient, private val storage: Storage, private val apiKey: String) {
    fun fetch() {
        val uri = generateUri()

        val responseBody =
            httpClient.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString()).body()

        val timestamp = json.decodeFromString<PartialLocationResponse>(responseBody).ctatt.tmst

        val timestampLocalDateTime = LocalDateTime.parse(timestamp)
        val prefix = timestampLocalDateTime.format(DateTimeFormatter.ofPattern("'realtime/raw/rail'/YYYY/MM/dd"))
        logger.info("Storing rail data at gs://${Constants.BUCKET_ID}/$prefix/$timestamp.json")
        storage.create(
            BlobInfo
                .newBuilder(
                    Constants.BUCKET_ID,
                    "$prefix/$timestamp.json"
                )
                .setContentType("application/json")
                .setCustomTimeOffsetDateTime(
                    timestampLocalDateTime.atZone(timeZone)
                        .toOffsetDateTime()
                )
                .build(),
            responseBody.toByteArray()
        )
        logger.info("Rail data successfully stored")
    }

    private fun generateUri(): URI {
        val queryParams = mapOf(
            ROUTE_PARAM to routesValue,
            OUTPUT_TYPE_PARAM to OUTPUT_TYPE_VALUE,
            KEY_PARAM to apiKey
        )

        val queryString = queryParams.map { "${it.key}=${it.value}" }.joinToString("&")
        return URI("${BASE_URL}?$queryString")
    }

    companion object {
        private const val BASE_URL = "https://lapi.transitchicago.com/api/1.0/ttpositions.aspx"
        private const val ROUTE_PARAM = "rt"
        private const val OUTPUT_TYPE_PARAM = "outputType"
        private const val OUTPUT_TYPE_VALUE = "JSON"
        private const val KEY_PARAM = "key"
        private val timeZone = ZoneId.of("America/Chicago")
        private val routesValue = listOf("Red", "Blue", "Brn", "G", "Org", "P", "Pink", "Y").joinToString(",")
        private val json = Json { ignoreUnknownKeys = true }
        private val logger = Logger.getLogger(RailDataFetcher::class.java.name)
    }
}
