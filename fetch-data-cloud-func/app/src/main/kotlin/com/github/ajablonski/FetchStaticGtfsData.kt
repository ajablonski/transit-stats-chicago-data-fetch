package com.github.ajablonski

import com.github.ajablonski.Constants.ETAG_HEADER
import com.google.cloud.functions.CloudEventsFunction
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import io.cloudevents.CloudEvent
import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.annotations.TestOnly
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.logging.Logger

class FetchStaticGtfsData : CloudEventsFunction {
    var storage: Storage = StorageOptions.getDefaultInstance().service
        @TestOnly set
    var httpClientEngine: HttpClientEngine = CIO.create()
        @TestOnly set
    private val httpClient: HttpClient by lazy {
        HttpClient(httpClientEngine)
    }

    override fun accept(payload: CloudEvent?) {
        val lastDownloadedETag = fetchLastRetrievedGtfsEtag()
            .also {
                if (it == null) {
                    logger.info("Could not find $LAST_DOWNLOADED_ETAG_FILENAME file, will download current GTFS zip file.")
                } else {
                    logger.info("Most recently fetched static GTFS zip file had ETag $it")
                }
            }
        runBlocking {

            val currentETag = fetchCurrentGtfsEtag()
                .also { logger.info("Currently available static GTFS zip file has ETag $it") }

            if (currentETag == lastDownloadedETag) {
                logger.info("Latest ETag matches current ETag, skipping download.")
                return@runBlocking
            }

            httpClient.prepareGet(
                Url(GTFS_URL)
            ).execute { gtfsStaticDataResponse ->
                val filePath = generateFilePath(gtfsStaticDataResponse)

                logger.info("Downloading newer version and storing at gs://${Constants.BUCKET_ID}/$filePath")
                storage.createFrom(
                    BlobInfo.newBuilder(Constants.BUCKET_ID, filePath).build(),
                    gtfsStaticDataResponse.bodyAsChannel().toInputStream()
                )
                logger.info("Successfully saved file, updating ETag")

                storage.create(lastDownloadedETagBlobInfo, currentETag.toByteArray(Charsets.UTF_8))
                logger.info("Successfully updated ETag")
            }
        }
    }

    private fun generateFilePath(gtfsStaticDataResponse: io.ktor.client.statement.HttpResponse): String {
        val lastModifiedDateTime = gtfsStaticDataResponse
            .headers["Last-Modified"]
            ?.let { LocalDateTime.parse(it, DateTimeFormatter.ofPattern("EE, dd MMM yyyy HH:mm:ss zzz")) }
            ?: LocalDateTime.ofInstant(Instant.now(), ZoneId.of("GMT"))

        val subPath = lastModifiedDateTime.format(DateTimeFormatter.ofPattern("'static'/yyyy/MM/dd"))
        val prefix = "$subPath/gtfs_"
        var blobPage = storage.list(Constants.BUCKET_ID, Storage.BlobListOption.prefix(prefix))
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

    private suspend fun fetchCurrentGtfsEtag(): String {
        val headRequestHeaders = httpClient.head(
            Url(GTFS_URL)
        ).headers

        return headRequestHeaders[ETAG_HEADER] ?: "<No ETag Provided>"
    }

    private fun fetchLastRetrievedGtfsEtag(): String? {
        val blob = storage.get(lastDownloadedETagBlobInfo.blobId)

        return blob?.getContent()?.toString(Charsets.UTF_8)
    }

    companion object {
        const val GTFS_URL = "https://www.transitchicago.com/downloads/sch_data/google_transit.zip"
        private const val LAST_DOWNLOADED_ETAG_FILENAME = "static/latest.txt"
        private val lastDownloadedETagBlobInfo =
            BlobInfo.newBuilder(Constants.BUCKET_ID, LAST_DOWNLOADED_ETAG_FILENAME).build()
        private val logger = Logger.getLogger(FetchStaticGtfsData::class.java.name)
    }
}
