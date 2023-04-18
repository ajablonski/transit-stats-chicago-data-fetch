package com.github.ajablonski

import com.github.ajablonski.Constants.ETagHeadder
import com.google.cloud.functions.CloudEventsFunction
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import io.cloudevents.CloudEvent
import org.jetbrains.annotations.TestOnly
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.logging.Logger
import kotlin.jvm.optionals.getOrDefault

class FetchStaticGtfsData : CloudEventsFunction {
    var httpClient: HttpClient = HttpClient.newHttpClient()
        @TestOnly set
    var storage: Storage = StorageOptions.getDefaultInstance().service
        @TestOnly set

    override fun accept(payload: CloudEvent?) {
        val lastDownloadedETag = fetchLastRetrievedGtfsEtag()
            .also {
                if (it == null) {
                    logger.info("Could not find $lastDownloadedETagFileName file, will download current GTFS zip file.")
                } else {
                    logger.info("Most recently fetched static GTFS zip file had ETag $it")
                }
            }

        val currentETag = fetchCurrentGtfsEtag()
            .also { logger.info("Currently available static GTFS zip file has ETag $it") }

        if (currentETag == lastDownloadedETag) {
            logger.info("Latest ETag matches current ETag, skipping download.")
            return
        }

        val gtfsStaticDataResponse = httpClient.send(
            HttpRequest.newBuilder(URI.create(gtfsUrl)).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream()
        )

        val filePath = generateFilePath(gtfsStaticDataResponse)

        logger.info("Downloading newer version and storing at gs://${Constants.bucketId}/$filePath")
        storage.createFrom(BlobInfo.newBuilder(Constants.bucketId, filePath).build(), gtfsStaticDataResponse.body())
        logger.info("Successfully saved file, updating ETag")

        storage.create(lastDownloadedETagBlobInfo, currentETag.toByteArray(Charsets.UTF_8))
        logger.info("Successfully updated ETag")
    }

    private fun generateFilePath(gtfsStaticDataResponse: HttpResponse<InputStream>): String {
        val lastModifiedDateTime = gtfsStaticDataResponse
            .headers()
            .firstValue("Last-Modified")
            .map { LocalDateTime.parse(it, DateTimeFormatter.ofPattern("EE, dd MMM yyyy HH:mm:ss zzz")) }
            .getOrDefault(LocalDateTime.ofInstant(Instant.now(), ZoneId.of("GMT")))

        val subPath = lastModifiedDateTime.format(DateTimeFormatter.ofPattern("'static'/yyyy/MM/dd"))
        val prefix = "$subPath/gtfs_"
        var blobPage = storage.list(Constants.bucketId, Storage.BlobListOption.prefix(prefix))
        var largestIndex = -1
        while (blobPage != null) {
            largestIndex = maxOf(blobPage
                .values
                .mapNotNull { it.name.removePrefix(prefix).removeSuffix(".zip").toIntOrNull() }
                .maxOrNull() ?: -1, largestIndex)

            blobPage = blobPage.nextPage
        }

        return "$subPath/gtfs_${largestIndex + 1}.zip"
    }

    private fun fetchCurrentGtfsEtag(): String {
        val headRequestHeaders = httpClient.send(
            HttpRequest.newBuilder(URI.create(gtfsUrl)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString()
        ).headers()

        return headRequestHeaders.firstValue(ETagHeadder).getOrDefault("<No ETag Provided>")
    }

    private fun fetchLastRetrievedGtfsEtag(): String? {
        val blob = storage.get(lastDownloadedETagBlobInfo.blobId)

        return blob?.getContent()?.toString(Charsets.UTF_8)
    }

    companion object {
        const val gtfsUrl = "https://www.transitchicago.com/downloads/sch_data/google_transit.zip"
        private const val lastDownloadedETagFileName = "static/latest.txt"
        private val lastDownloadedETagBlobInfo = BlobInfo.newBuilder(Constants.bucketId, lastDownloadedETagFileName).build()
        private val logger = Logger.getLogger(FetchStaticGtfsData::class.java.name)
    }
}
