package com.github.ajablonski

import com.google.cloud.functions.Context
import com.google.cloud.storage.Storage
import com.google.common.testing.TestLogHandler
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.io.path.toPath

class FetchRealtimeGtfsDataTest {
    private val mockStorage = mockk<Storage>(relaxed = true) {}
    private lateinit var messageHandler: FetchRealtimeGtfsData
    private val mockContext = mockk<Context> { every { timestamp() }.returns("2022-08-21T20:00:00.000Z") }
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
        every { mockContext.timestamp() }.returns("2022-08-21T20:00:00.000Z")
        messageHandler.accept(message, mockContext)
        verify { mockRailDataFetcher.fetch() }
        clearMocks(mockRailDataFetcher)
        every { mockContext.timestamp() }.returns("2022-08-21T20:01:00.000Z")
        messageHandler.accept(message, mockContext)
        verify { mockRailDataFetcher.fetch() }
    }

    @Test
    fun shouldFetchBusDataOnEvenMinuteTriggers() {
        every { mockContext.timestamp() }.returns("2022-08-21T20:00:00.000Z")
        messageHandler.accept(message, mockContext)
        verify { mockBusDataFetcher.fetch() }
        clearMocks(mockBusDataFetcher)
        every { mockContext.timestamp() }.returns("2022-08-21T20:01:00.000Z")
        messageHandler.accept(message, mockContext)
        verify(exactly = 0) { mockBusDataFetcher.fetch() }
    }

    @Test
    fun shouldLogProgressWhenFetchingBus() {
        messageHandler.accept(message, mockContext)

        logHandler.storedLogRecords[0].apply {
            assertThat(message).isEqualTo("Retrieved trigger event with timestamp 2022-08-21T20:00:00.000Z")
            assertThat(level).isEqualTo(Level.INFO)
        }
        logHandler.storedLogRecords[1].apply {
            assertThat(message).isEqualTo("Fetching rail data")
            assertThat(level).isEqualTo(Level.INFO)
        }
        logHandler.storedLogRecords[2].apply {
            assertThat(message).isEqualTo("Fetching bus data")
            assertThat(level).isEqualTo(Level.INFO)
        }
    }

    @Test
    fun shouldLogProgressWhenNotFetchingBus() {
        every { mockContext.timestamp() }.returns("2022-08-21T20:01:00.000Z")

        messageHandler.accept(message, mockContext)

        logHandler.storedLogRecords[0].apply {
            assertThat(message).isEqualTo("Retrieved trigger event with timestamp 2022-08-21T20:01:00.000Z")
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

        private val data = Base64.getEncoder().encodeToString(
            """{
                "trigger": "fetch-realtime-gtfs-data"
            }""".trimIndent().toByteArray(Charsets.UTF_8)
        )
        private val message =
            PubSubMessage(data, "messageId", "2022-01-01T00:00:00Z", emptyMap())
    }
}