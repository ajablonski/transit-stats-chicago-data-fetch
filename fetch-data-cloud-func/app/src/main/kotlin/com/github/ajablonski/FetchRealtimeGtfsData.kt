package com.github.ajablonski

import com.google.cloud.functions.BackgroundFunction
import com.google.cloud.functions.CloudEventsFunction
import com.google.cloud.functions.Context
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import io.cloudevents.CloudEvent
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jetbrains.annotations.TestOnly
import java.net.http.HttpClient
import java.nio.file.Path
import java.time.ZonedDateTime
import java.util.logging.Logger
import kotlin.io.path.readText

class FetchRealtimeGtfsData(secretPath: Path = defaultSecretPath) : CloudEventsFunction {
    var storage: Storage = StorageOptions.getDefaultInstance().service
        @TestOnly set
    var httpClient: HttpClient = HttpClient.newHttpClient()
        @TestOnly set

    private val apiKeys = json.decodeFromString<Secrets>(secretPath.readText())

    var railDataFetcher: RailDataFetcher = RailDataFetcher(httpClient, storage, apiKeys.trainTrackerApiKey)
        @TestOnly set
    var busDataFetcher: BusDataFetcher = BusDataFetcher(
        httpClient,
        storage,
        apiKeys.busTrackerApiKey
    )
        @TestOnly set

    override fun accept(payload: CloudEvent?) {
        logger.info("Retrieved trigger event with timestamp ${payload?.time}")
        logger.info("Fetching rail data")
        railDataFetcher.fetch()
        val time = payload?.time
        if (time != null && time.minute % 2 == 0) {
            logger.info("Fetching bus data for minute ${time.minute}")
            busDataFetcher.fetch(time.toZonedDateTime())
        } else {
            logger.info("Skipping bus data for minute ${time?.minute}")
        }
    }

    companion object {
        private val defaultSecretPath = Path.of("/etc/secrets/gtfs_secrets.json")
        private val json = Json { ignoreUnknownKeys = true }
        private val logger = Logger.getLogger(FetchRealtimeGtfsData::class.java.name)
    }
}

