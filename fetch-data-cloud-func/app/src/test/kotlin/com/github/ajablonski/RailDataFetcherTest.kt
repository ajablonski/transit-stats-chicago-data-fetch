package com.github.ajablonski

import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.common.testing.TestLogHandler
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.logging.Level
import java.util.logging.Logger

internal class RailDataFetcherTest {
    private val storage = mockk<Storage>(relaxed = true)
    private val httpClient = mockk<HttpClient> {
        every {
            send(
                match { request ->
                    val queryParamMap = request
                        .uri()
                        .query
                        .split("&")
                        .map { queryPart -> queryPart.split("=").let { it[0] to it[1] } }
                        .groupBy { it.first }
                        .mapValues { it.value.first().second }
                    queryParamMap["rt"]!!.split(",")
                        .containsAll(setOf("Red", "Blue", "Brn", "G", "Org", "P", "Pink"))
                            && queryParamMap["outputType"] == "JSON"
                            && queryParamMap["key"] == trainTrackerApiKey
                            && request.method() == "GET"
                },
                any<HttpResponse.BodyHandler<String>>()
            )
        }.returns(mockk<HttpResponse<String>> {
            every { body() }.returns(testData)
        })
    }
    private val railDataFetcher = RailDataFetcher(httpClient, storage, trainTrackerApiKey)

    @Test
    fun shouldCallRealTimeApiWithCorrectParameters() {
        railDataFetcher.fetch()

        verify {
            httpClient.send(
                match { request ->
                    val queryParamMap = request
                        .uri()
                        .query
                        .split("&")
                        .map { queryPart -> queryPart.split("=").let { it[0] to it[1] } }
                        .groupBy { it.first }
                        .mapValues { it.value.first().second }
                    queryParamMap["rt"]!!.split(",")
                        .containsAll(setOf("Red", "Blue", "Brn", "G", "Org", "P", "Pink", "Y"))
                            && queryParamMap["outputType"] == "JSON"
                            && queryParamMap["key"] == trainTrackerApiKey
                            && request.method() == "GET"
                },
                any<HttpResponse.BodyHandler<String>>()
            )
        }
    }

    @Test
    fun shouldSaveResultInTimestampedFile() {
        railDataFetcher.fetch()

        verify {
            storage.create(
                BlobInfo
                    .newBuilder(
                        Constants.bucketId,
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