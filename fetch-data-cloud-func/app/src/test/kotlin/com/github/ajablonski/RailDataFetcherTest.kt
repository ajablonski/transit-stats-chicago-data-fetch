package com.github.ajablonski

import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.common.testing.TestLogHandler
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.logging.Level
import java.util.logging.Logger
import javax.net.ssl.SSLHandshakeException

internal class RailDataFetcherTest {
    private val storage = mockk<Storage>(relaxed = true)
    private val httpClientEngine = MockEngine { request ->
        if (request.method == HttpMethod.Get
            && request.url.parameters["key"] == trainTrackerApiKey
            && request.url.parameters["outputType"] == "JSON"
            && request.url.parameters["rt"]!!.split(",")
                .containsAll(setOf("Red", "Blue", "Brn", "G", "Org", "P", "Pink"))
        ) {
            respond(
                content = ByteReadChannel(testData),
                status = HttpStatusCode.OK
            )
        } else {
            respondBadRequest()
        }

    }
    private val railDataFetcher = RailDataFetcher(httpClientEngine, storage, trainTrackerApiKey)

    @Test
    fun shouldCallRealTimeApiWithCorrectParameters() {
        runBlocking { railDataFetcher.fetch() }

        assertThat(httpClientEngine.requestHistory[0].method).isEqualTo(HttpMethod.Get)
        assertThat(httpClientEngine.requestHistory[0].url.parameters["rt"]!!.split(",")).containsAll(
            setOf(
                "Red",
                "Blue",
                "Brn",
                "G",
                "Org",
                "P",
                "Pink"
            )
        )
        assertThat(httpClientEngine.requestHistory[0].url.parameters["outputType"]).isEqualTo("JSON")
        assertThat(httpClientEngine.requestHistory[0].url.parameters["key"]).isEqualTo(trainTrackerApiKey)
    }

    @Test
    fun shouldSaveResultInTimestampedFile() {
        runBlocking { railDataFetcher.fetch() }

        verify {
            storage.create(
                BlobInfo
                    .newBuilder(
                        Constants.BUCKET_ID,
                        "realtime/raw/rail/2022/08/06/2022-08-06T18:54:12.json"
                    )
                    .setContentType("application/json")
                    .setCustomTimeOffsetDateTime(
                        ZonedDateTime.of(2022, 8, 6, 18, 54, 12, 0, ZoneId.of("America/Chicago")).toOffsetDateTime()
                    )
                    .build(),
                testData.toByteArray(),
            )
        }
    }


    @Test
    fun shouldRetryOnServerException() {
        var errorCount = 3
        httpClientEngine.config.requestHandlers[0] = { request ->
            if (errorCount > 0) {
                errorCount--
                throw SSLHandshakeException("Error")
            }

            if (request.method == HttpMethod.Get
                && request.url.parameters["key"] == trainTrackerApiKey
                && request.url.parameters["outputType"] == "JSON"
                && request.url.parameters["rt"]!!.split(",")
                    .containsAll(setOf("Red", "Blue", "Brn", "G", "Org", "P", "Pink"))
            ) {
                respond(
                    content = ByteReadChannel(testData),
                    status = HttpStatusCode.OK
                )
            } else {
                respondBadRequest()
            }
        }
        runBlocking { railDataFetcher.fetch() }

        verify {
            storage.create(
                BlobInfo
                    .newBuilder(
                        Constants.BUCKET_ID,
                        "realtime/raw/rail/2022/08/06/2022-08-06T18:54:12.json"
                    )
                    .setContentType("application/json")
                    .setCustomTimeOffsetDateTime(
                        ZonedDateTime.of(2022, 8, 6, 18, 54, 12, 0, ZoneId.of("America/Chicago")).toOffsetDateTime()
                    )
                    .build(),
                testData.toByteArray(),
            )
        }
    }


    @Test
    fun shouldRetryOnServerError() {
        var errorCount = 3
        httpClientEngine.config.requestHandlers[0] = { request ->
            if (errorCount > 0) {
                errorCount--
                respondError(HttpStatusCode.InternalServerError)
            } else if (request.method == HttpMethod.Get
                && request.url.parameters["key"] == trainTrackerApiKey
                && request.url.parameters["outputType"] == "JSON"
                && request.url.parameters["rt"]!!.split(",")
                    .containsAll(setOf("Red", "Blue", "Brn", "G", "Org", "P", "Pink"))
            ) {
                respond(
                    content = ByteReadChannel(testData),
                    status = HttpStatusCode.OK
                )
            } else {
                respondBadRequest()
            }
        }

        runBlocking { railDataFetcher.fetch() }

        verify {
            storage.create(
                BlobInfo
                    .newBuilder(
                        Constants.BUCKET_ID,
                        "realtime/raw/rail/2022/08/06/2022-08-06T18:54:12.json"
                    )
                    .setContentType("application/json")
                    .setCustomTimeOffsetDateTime(
                        ZonedDateTime.of(2022, 8, 6, 18, 54, 12, 0, ZoneId.of("America/Chicago")).toOffsetDateTime()
                    )
                    .build(),
                testData.toByteArray(),
            )
        }
    }

    @Test
    fun shouldLogAppropriateMessages() {
        logHandler.storedLogRecords[0].apply {
            assertThat(message).isEqualTo("Storing rail data at gs://tsc-gtfs-data/realtime/raw/rail/2022/08/06/2022-08-06T18:54:12.json")
            assertThat(level).isEqualTo(Level.INFO)
        }
        logHandler.storedLogRecords[1].apply {
            assertThat(message).isEqualTo("Rail data successfully stored")
            assertThat(level).isEqualTo(Level.INFO)
        }
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun setUpForAll() {
            logger.addHandler(logHandler)
        }

        private val logHandler = TestLogHandler()
        private val logger = Logger.getLogger(RailDataFetcher::class.qualifiedName)

        private const val trainTrackerApiKey = "fakeTrainTrackerApiKey"
        private const val testData = """{
          "ctatt": {
            "tmst": "2022-08-06T18:54:12"
          }
        }"""
    }
}