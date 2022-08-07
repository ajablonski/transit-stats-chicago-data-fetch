package com.github.ajablonski

import com.google.cloud.functions.Context
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.common.testing.TestLogHandler
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandler
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.io.path.toPath

class FetchRealtimeGtfsDataTest {
    private val mockStorage = mockk<Storage>(relaxed = true)
    private lateinit var messageHandler: FetchRealtimeGtfsData
    private val mockContext = mockk<Context> { every { timestamp() }.returns("2022-01-02T00:00:00") }
    private val mockHttpClient = mockk<HttpClient>(relaxed = true) {
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
                any<BodyHandler<String>>()
            )
        }.returns(mockk<HttpResponse<String>> {
            every { body() }.returns(testData)
        })
    }

    @BeforeEach
    fun setUp() {
        messageHandler = FetchRealtimeGtfsData()
        messageHandler.httpClient = mockHttpClient
        messageHandler.secretPath = ClassLoader.getSystemResource("testsecrets.json").toURI().toPath()
        messageHandler.storage = mockStorage
        logHandler.clear()
    }

    @Test
    fun shouldCallRealTimeApiWithCorrectParameters() {
        messageHandler.accept(message, null)

        verify {
            messageHandler.httpClient.send(
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
                any<BodyHandler<String>>()
            )
        }
    }

    @Test
    fun shouldSaveResultInTimestampedFile() {
        messageHandler.accept(message, null)

        verify {
            mockStorage.create(
                BlobInfo
                    .newBuilder(
                        Constants.bucketId,
                        "realtime/raw/2022/08/06/2022-08-06T18:54:12.json"
                    )
                    .setContentType("application/json")
                    .setCustomTime(
                        ZonedDateTime.of(2022, 8, 6, 18, 54, 12, 0, ZoneId.of("America/Chicago")).toEpochSecond() * 1000
                    )
                    .build(),
                testData.toByteArray(),
            )
        }
    }

    @Test
    fun shouldLogProgress() {
        messageHandler.accept(message, mockContext)

        logHandler.storedLogRecords[0].apply {
            assertThat(message).isEqualTo("Retrieved trigger event with timestamp 2022-01-02T00:00:00")
            assertThat(level).isEqualTo(Level.INFO)
        }
        logHandler.storedLogRecords[1].apply {
            assertThat(message).isEqualTo("Fetching data")
            assertThat(level).isEqualTo(Level.INFO)
        }
        logHandler.storedLogRecords[2].apply {
            assertThat(message).isEqualTo("Storing data at gs://tsc-gtfs-data/realtime/raw/2022/08/06/2022-08-06T18:54:12.json")
            assertThat(level).isEqualTo(Level.INFO)
        }
        logHandler.storedLogRecords[3].apply {
            assertThat(message).isEqualTo("Data successfully stored")
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
        private val logger = Logger.getLogger(FetchRealtimeGtfsData::class.qualifiedName)

        private val data = Base64.getEncoder().encodeToString(
            """{
                "trigger": "fetch-realtime-gtfs-data"
            }""".trimIndent().toByteArray(Charsets.UTF_8)
        )
        private const val trainTrackerApiKey = "fakeTrainTrackerApiKey"
        private const val testData = """{
          "ctatt": {
            "tmst": "2022-08-06T18:54:12"
          }
        }"""
        private val message =
            PubSubMessage(data, "messageId", "2022-01-01T00:00:00Z", emptyMap())
    }
}