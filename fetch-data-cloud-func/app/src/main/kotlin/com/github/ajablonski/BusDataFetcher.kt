package com.github.ajablonski

import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.common.io.Resources
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.logging.Logger

class BusDataFetcher(
    private val httpClient: HttpClient,
    private val storage: Storage,
    private val apiKey: String,
    routeFile: URL = Resources.getResource(BusDataFetcher::class.java, "/bus_routes.txt")
) {
    private val routeBatches =
        Resources.readLines(routeFile, Charsets.UTF_8)
            .drop(1)
            .map { it.split(",")[0] }
            .windowed(10, 10, true)
            .map { it.joinToString(",") }

    fun fetch(time: ZonedDateTime) {
        val trackerResponses = routeBatches.map {
            val uri = getUri(it)
            httpClient.send(
                HttpRequest.newBuilder()
                    .uri(uri)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
        }

        val filename = time.withZoneSameInstant(centralTime)
            .format(DateTimeFormatter.ofPattern("'realtime/raw/bus'/YYYY/MM/dd/YYYY-MM-dd'T'HH:mm:ss'.json'"))

        logger.info("Storing bus data at gs://${Constants.BUCKET_ID}/$filename")
        storage.create(
            BlobInfo.newBuilder(Constants.BUCKET_ID, filename).build(),
            trackerResponses.joinToString(",\n", prefix = "[", postfix = "]") { it.body() }.toByteArray(Charsets.UTF_8)
        )
        logger.info("Bus data successfully stored")
    }


    private fun getUri(routeBatchString: String): URI {
        val queryParams = mapOf(
            FORMAT_PARAM to JSON_FORMAT,
            ROUTE_PARAM to routeBatchString,
            KEY_PARAM to apiKey,
            TIME_RES_PARAM to SECONDS_TIME_RESOLUTION
        )
        val queryString = queryParams.map { "${it.key}=${it.value}" }.joinToString("&")

        return URI("${CTA_BUS_TRACKER_URL}?$queryString")
    }

    companion object {
        private const val FORMAT_PARAM = "format"
        private const val ROUTE_PARAM = "rt"
        private const val KEY_PARAM = "key"
        private const val TIME_RES_PARAM = "tmres"
        private const val JSON_FORMAT = "json"
        private const val SECONDS_TIME_RESOLUTION = "s"
        private val centralTime = ZoneId.of("America/Chicago")

        val logger: Logger = Logger.getLogger(BusDataFetcher::class.java.name)
        private const val CTA_BUS_TRACKER_URL = "https://www.ctabustracker.com/bustime/api/v2/getvehicles"

    }
}
