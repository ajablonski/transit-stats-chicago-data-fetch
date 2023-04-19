package com.github.ajablonski

import com.google.cloud.functions.Context
import com.google.cloud.storage.Storage
import com.google.common.testing.TestLogHandler
import io.cloudevents.CloudEvent
import io.cloudevents.CloudEventData
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.io.path.toPath

class FetchRealtimeGtfsDataTest {
    private val mockStorage = mockk<Storage>(relaxed = true) {}
    private lateinit var messageHandler: FetchRealtimeGtfsData
    private val mockHttpClient = mockk<HttpClient>(relaxed = true)
    private val mockRailDataFetcher = mockk<RailDataFetcher>(relaxed = true)
    private val mockBusDataFetcher = mockk<BusDataFetcher>(relaxed = true)

    @BeforeEach
    fun setUp() {
        messageHandler = FetchRealtimeGtfsData(ClassLoader.getSystemResource("testsecrets.json").toURI().toPath())
        messageHandler.httpClient = mockHttpClient
        messageHandler.storage = mockStorage
        messageHandler.railDataFetcher = mockRailDataFetcher
        messageHandler.busDataFetcher = mockBusDataFetcher
        logHandler.clear()
    }

    @Test
    fun shouldFetchRailDataOnEveryTrigger() {
        every { message.time } returns OffsetDateTime.parse("2022-08-21T20:00:00.000Z")
        messageHandler.accept(message)
        verify { mockRailDataFetcher.fetch() }
        clearMocks(mockRailDataFetcher)
        every { message.time } returns OffsetDateTime.parse("2022-08-21T20:01:00.000Z")
        messageHandler.accept(message)
        verify { mockRailDataFetcher.fetch() }
    }

    @Test
    fun shouldFetchBusDataOnEvenMinuteTriggers() {
        every { message.time } returns OffsetDateTime.parse("2022-08-21T20:00:00.000Z")
        messageHandler.accept(message)
        verify {
            mockBusDataFetcher.fetch(
                ZonedDateTime.parse("2022-08-21T20:00:00.000Z")
            )
        }
        clearMocks(mockBusDataFetcher)
        every { message.time } returns OffsetDateTime.parse("2022-08-21T20:01:00.000Z")
        messageHandler.accept(message)
        verify(exactly = 0) { mockBusDataFetcher.fetch(any()) }
    }

    @Test
    fun shouldLogProgressWhenFetchingBus() {
        every { message.time } returns OffsetDateTime.parse("2022-08-21T20:00:00Z")

        messageHandler.accept(message)

        logHandler.storedLogRecords[0].apply {
            assertThat(message).isEqualTo("Retrieved trigger event with timestamp 2022-08-21T20:00Z")
            assertThat(level).isEqualTo(Level.INFO)
        }
        logHandler.storedLogRecords[1].apply {
            assertThat(message).isEqualTo("Fetching rail data")
            assertThat(level).isEqualTo(Level.INFO)
        }
        logHandler.storedLogRecords[2].apply {
            assertThat(message).isEqualTo("Fetching bus data for minute 0")
            assertThat(level).isEqualTo(Level.INFO)
        }
    }

    @Test
    fun shouldLogProgressWhenNotFetchingBus() {
        every { message.time } returns OffsetDateTime.parse("2022-08-21T20:01:00.000Z")

        messageHandler.accept(message)

        logHandler.storedLogRecords[0].apply {
            assertThat(message).isEqualTo("Retrieved trigger event with timestamp 2022-08-21T20:01Z")
            assertThat(level).isEqualTo(Level.INFO)
        }
        logHandler.storedLogRecords[1].apply {
            assertThat(message).isEqualTo("Fetching rail data")
            assertThat(level).isEqualTo(Level.INFO)
        }
        logHandler.storedLogRecords[2].apply {
            assertThat(message).isEqualTo("Skipping bus data for minute 1")
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

        private val messageData = """{
                "trigger": "fetch-realtime-gtfs-data"
            }""".trimIndent().toByteArray(Charsets.UTF_8)

        private val message =
            mockk<CloudEvent> {
                every { data } returns CloudEventData { messageData }
                every { id } returns "messageId"
                every { time } returns OffsetDateTime.parse("2022-01-01T00:00:00Z")
            }
    }
}