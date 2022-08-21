package com.github.ajablonski

import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
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
        logger.info("Storing rail data at gs://${Constants.bucketId}/$prefix/$timestamp.json")
        storage.create(
            BlobInfo
                .newBuilder(
                    Constants.bucketId,
                    "$prefix/$timestamp.json"
                )
                .setContentType("application/json")
                .setCustomTime(
                    timestampLocalDateTime.atZone(timeZone)
                        .toEpochSecond() * secondsToMilliseconds
                )
                .build(),
            responseBody.toByteArray()
        )
        logger.info("Rail data successfully stored")
    }

    private fun generateUri(): URI {
        val queryParams = mapOf(
            routeParam to routesValue,
            outputTypeParam to outputTypeValue,
            keyParam to apiKey
        )

        val queryString = queryParams.map { "${it.key}=${it.value}" }.joinToString("&")
        return URI("${baseUrl}?$queryString")
    }

    companion object {
        private const val secondsToMilliseconds = 1000
        private const val baseUrl = "https://lapi.transitchicago.com/api/1.0/ttpositions.aspx"
        private const val routeParam = "rt"
        private const val outputTypeParam = "outputType"
        private const val outputTypeValue = "JSON"
        private const val keyParam = "key"
        private val timeZone = ZoneId.of("America/Chicago")
        private val routesValue = listOf("Red", "Blue", "Brn", "G", "Org", "P", "Pink", "Y").joinToString(",")
        private val json = Json { ignoreUnknownKeys = true }
        private val logger = Logger.getLogger(RailDataFetcher::class.java.name)
    }
}

@Serializable
data class PartialLocationResponse(val ctatt: PartialCtaTt)

@Serializable
data class PartialCtaTt(val tmst: String)