package com.github.ajablonski

import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.common.io.Resources
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import java.net.URI
import java.net.URL
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.logging.Logger

class BusDataFetcher(
    private val httpClientEngine: HttpClientEngine,
    private val storage: Storage,
    private val apiKey: String,
    routeFile: URL = Resources.getResource(BusDataFetcher::class.java, "/bus_routes.txt"),
    private val httpClient: HttpClient = HttpClient(httpClientEngine) {
        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 3)
            retryOnException(maxRetries = 3, retryOnTimeout = true)
        }
    }
) {
    private val routeBatches =
        Resources.readLines(routeFile, Charsets.UTF_8)
            .drop(1)
            .map { it.split(",")[0] }
            .windowed(10, 10, true)
            .map { it.joinToString(",") }

    suspend fun fetch(time: ZonedDateTime) {
        val trackerResponseBodies = routeBatches.map {
            httpClient.get(
                getUrl(it)
            ).body<String>()
        }

        val filename = time.withZoneSameInstant(centralTime)
            .format(DateTimeFormatter.ofPattern("'realtime/raw/bus'/YYYY/MM/dd/YYYY-MM-dd'T'HH_mm_ss'.json'"))

        logger.info("Storing bus data at gs://${Constants.BUCKET_ID}/$filename")
        storage.create(
            BlobInfo.newBuilder(Constants.BUCKET_ID, filename).build(),
            trackerResponseBodies.joinToString(",\n", prefix = "[", postfix = "]").toByteArray(Charsets.UTF_8)
        )
        logger.info("Bus data successfully stored")
    }


    private fun getUrl(routeBatchString: String): Url {
        val queryParams = mapOf(
            FORMAT_PARAM to JSON_FORMAT,
            ROUTE_PARAM to routeBatchString,
            KEY_PARAM to apiKey,
            TIME_RES_PARAM to SECONDS_TIME_RESOLUTION
        )
        val queryString = queryParams.map { "${it.key}=${it.value}" }.joinToString("&")

        return Url(URI("${CTA_BUS_TRACKER_URL}?$queryString"))
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
