package com.github.ajablonski

import com.google.cloud.functions.BackgroundFunction
import com.google.cloud.functions.Context
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import org.jetbrains.annotations.TestOnly
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

class FetchStaticGtfsData : BackgroundFunction<PubSubMessage> {
    var httpClient: HttpClient = HttpClient.newHttpClient()
        @TestOnly set
    var gtfsUrl: String = "https://www.transitchicago.com/downloads/sch_data/google_transit.zip"
        @TestOnly set
    var storage: Storage = StorageOptions.getDefaultInstance().service
        @TestOnly set
    private val bucketId = "tsc-gtfs-data"
    private val latestETagBlobPath = "static/latest.txt"
    private val etagHeader = "ETag"

    @OptIn(ExperimentalStdlibApi::class)
    override fun accept(payload: PubSubMessage?, context: Context?) {
        val blob = storage.get(bucketId, latestETagBlobPath)
        val latestDownloadedETag = if (blob == null) null else String(blob.getContent(), Charsets.UTF_8)

        if (latestDownloadedETag == null) {
            logger.info("Could not find latest.txt file, will download current GTFS zip file.")
        } else {
            logger.info("Most recently fetched static GTFS zip file had ETag $latestDownloadedETag")
        }

        val headRequestHeaders = httpClient.send(
            HttpRequest.newBuilder(URI.create(gtfsUrl)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString()
        ).headers()

        val currentETag = headRequestHeaders.firstValue(etagHeader).getOrDefault("<No ETag Provided>")
        logger.info("Currently available static GTFS zip file has ETag $currentETag")

        if (currentETag == latestDownloadedETag) {
            logger.info("Latest ETag matches current ETag, skipping download.")
            return
        }

        val fullResponse = httpClient.send(
            HttpRequest.newBuilder(URI.create(gtfsUrl)).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream()
        )

        val lastModifiedDateTime = fullResponse
            .headers()
            .firstValue("Last-Modified")
            .map { LocalDateTime.parse(it, DateTimeFormatter.ofPattern("EE, dd MMM yyyy HH:mm:ss zzz")) }
            .getOrDefault(LocalDateTime.ofInstant(Instant.now(), ZoneId.of("GMT")))

        val subPath = lastModifiedDateTime.format(DateTimeFormatter.ofPattern("yyyy/MM/dd")) + "/gtfs_0.zip"

        logger.info("Downloading newer version and storing at gs://$bucketId/static/$subPath")

        storage.create(BlobInfo.newBuilder(bucketId, "static/$subPath").build(), fullResponse.body())

        logger.info("Successfully saved file, updating ETag")

        storage.create(BlobInfo.newBuilder(bucketId, latestETagBlobPath).build(), currentETag.toByteArray(Charsets.UTF_8))

        logger.info("Successfully updated ETag")
    }

    companion object {
        private val logger = Logger.getLogger(FetchStaticGtfsData::class.java.name)
    }
}

data class PubSubMessage(
    val data: String,
    val messageID: String,
    val publishTime: String,
    val attributes: Map<String, String>
)
