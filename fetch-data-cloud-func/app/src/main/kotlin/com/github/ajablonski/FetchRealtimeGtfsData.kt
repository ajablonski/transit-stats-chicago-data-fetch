package com.github.ajablonski

import com.google.cloud.functions.BackgroundFunction
import com.google.cloud.functions.Context
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jetbrains.annotations.TestOnly
import java.net.http.HttpClient
import java.nio.file.Path
import java.time.ZonedDateTime
import java.util.logging.Logger
import kotlin.io.path.readText

class FetchRealtimeGtfsData(secretPath: Path = defaultSecretPath) : BackgroundFunction<PubSubMessage> {
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

    override fun accept(payload: PubSubMessage?, context: Context?) {
        logger.info("Retrieved trigger event with timestamp ${context?.timestamp()}")
        logger.info("Fetching rail data")
        railDataFetcher.fetch()
        val time = if (context?.timestamp() != null) ZonedDateTime.parse(context.timestamp()) else null
        if (time != null && time.minute % 2 == 0) {
            logger.info("Fetching bus data for minute ${time.minute}")
            busDataFetcher.fetch(time)
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

