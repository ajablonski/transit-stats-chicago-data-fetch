package com.github.ajablonski

import com.github.ajablonski.serdes.Secrets
import com.google.cloud.functions.CloudEventsFunction
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import io.cloudevents.CloudEvent
import io.ktor.client.engine.*
import io.ktor.client.engine.cio.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.annotations.TestOnly
import java.nio.file.Path
import java.util.logging.Logger
import kotlin.io.path.readText

class FetchRealtimeGtfsData(secretPath: Path = defaultSecretPath) : CloudEventsFunction {
    var storage: Storage = StorageOptions.getDefaultInstance().service
        @TestOnly set
    private val ktorHttpClientEngine: HttpClientEngine = CIO.create {
        requestTimeout = 120_000
    }

    private val apiKeys = json.decodeFromString<Secrets>(secretPath.readText())

    var railDataFetcher: RailDataFetcher = RailDataFetcher(ktorHttpClientEngine, storage, apiKeys.trainTrackerApiKey)
        @TestOnly set
    var busDataFetcher: BusDataFetcher = BusDataFetcher(
        ktorHttpClientEngine,
        storage,
        apiKeys.busTrackerApiKey
    )
        @TestOnly set

    override fun accept(payload: CloudEvent?) {
        logger.info("Retrieved trigger event with timestamp ${payload?.time}")
        logger.info("Fetching rail data")
        runBlocking { railDataFetcher.fetch() }
        val time = payload?.time
        if (time != null) {
            logger.info("Fetching bus data")
            runBlocking {
                busDataFetcher.fetch(time.toZonedDateTime())
            }
        }
    }

    companion object {
        private val defaultSecretPath = Path.of("/etc/secrets/gtfs_secrets.json")
        private val json = Json { ignoreUnknownKeys = true }
        private val logger = Logger.getLogger(FetchRealtimeGtfsData::class.java.name)
    }
}

