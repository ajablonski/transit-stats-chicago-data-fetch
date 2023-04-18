package com.github.ajablonski

import com.google.api.client.util.IOUtils
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.common.io.Resources
import java.io.InputStreamReader
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.logging.Logger
import kotlin.io.path.toPath

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

        logger.info("Storing bus data at gs://${Constants.bucketId}/$filename")
        storage.create(
            BlobInfo.newBuilder(Constants.bucketId, filename).build(),
            trackerResponses.joinToString("\n") { it.body() }.toByteArray(Charsets.UTF_8)
        )
        logger.info("Bus data successfully stored")
    }


    private fun getUri(routeBatchString: String): URI {
        val queryParams = mapOf(
            formatParam to jsonFormat,
            routeParam to routeBatchString,
            keyParam to apiKey,
            timeResParam to secondsTimeResolution
        )
        val queryString = queryParams.map { "${it.key}=${it.value}" }.joinToString("&")

        return URI("${ctaBusTrackerUrl}?$queryString")
    }

    companion object {
        private const val formatParam = "format"
        private const val routeParam = "Rt"
        private const val keyParam = "Key"
        private const val timeResParam = "tmres"
        private const val jsonFormat = "json"
        private const val secondsTimeResolution = "s"
        private val centralTime = ZoneId.of("America/Chicago")

        val logger: Logger = Logger.getLogger(BusDataFetcher::class.java.name)
        private const val ctaBusTrackerUrl = "http://www.ctabustracker.com/bustime/api/v2/getvehicles"

    }
}
