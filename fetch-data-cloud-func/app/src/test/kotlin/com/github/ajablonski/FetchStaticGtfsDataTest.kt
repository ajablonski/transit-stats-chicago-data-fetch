package com.github.ajablonski

import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.common.testing.TestLogHandler
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandler
import java.time.Instant
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger

class FetchStaticGtfsDataTest {
    private lateinit var messageHandler: FetchStaticGtfsData
    private val mockHttpClient = mockk<HttpClient>(relaxed = true)
    private val fakeStorage = mockk<Storage>(relaxed = true)

    @BeforeEach
    fun setUp() {
        messageHandler = FetchStaticGtfsData()
        messageHandler.httpClient = mockHttpClient
        messageHandler.gtfsUrl = fakeGtfsUrl
        messageHandler.storage = fakeStorage
        logHandler.clear()

        every {
            mockHttpClient.send(match { request ->
                request.method() == "HEAD" && request.uri().toString() == fakeGtfsUrl
            }, any<BodyHandler<String>>()).headers()
        }.returns(
            HttpHeaders.of(
                mapOf(
                    "ETag" to listOf(sampleETag2),
                    "Last-Modified" to listOf("Thu, 28 Jul 2022 23:42:33 GMT")
                )
            ) { _, _ -> true }
        )

        val mockResponse = mockk<HttpResponse<InputStream>>()
        every {
            mockHttpClient.send(match { request ->
                request.method() == "GET" && request.uri().toString() == fakeGtfsUrl
            }, any<BodyHandler<InputStream>>())
        }.returns(mockResponse)
        every { mockResponse.body() }.returns(testBody)
        every { mockResponse.headers() }.returns(HttpHeaders.of(
            mapOf(
                "ETag" to listOf(sampleETag2),
                "Last-Modified" to listOf("Thu, 28 Jul 2022 23:42:33 GMT")
            )
        ) { _, _ -> true })
    }

    @Test
    fun shouldLoadMostRecentGtfsEtagFirstAndLogValue() {
        every {
            fakeStorage.get("tsc-gtfs-data", "static/latest.txt").getContent()
        }.returns(sampleETag1.toByteArray(Charsets.UTF_8))

        messageHandler.accept(message, null)

        assertThat(logHandler.storedLogRecords[0])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Most recently fetched static GTFS zip file had ETag $sampleETag1"
            )
    }

    @Test
    fun shouldHandleLatestFileNotBeingPresent() {
        every {
            fakeStorage.get("tsc-gtfs-data", "static/latest.txt")
        }.returns(null)

        messageHandler.accept(message, null)

        assertThat(logHandler.storedLogRecords[0])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Could not find latest.txt file, will download current GTFS zip file."
            )
    }

    @Test
    fun shouldRetrieveCurrentEtagAndLogValue() {
        messageHandler.accept(message, null)

        assertThat(logHandler.storedLogRecords[1])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Currently available static GTFS zip file has ETag $sampleETag2"
            )
    }

    @Test
    fun shouldLogWhenEtagHeaderNotPresent() {
        every {
            mockHttpClient.send(match { request ->
                request.method() == "HEAD" && request.uri().toString() == fakeGtfsUrl
            }, any<BodyHandler<String>>()).headers()
        }.returns(
            HttpHeaders.of(
                mapOf()
            ) { _, _ -> true }
        )

        messageHandler.accept(message, null)

        assertThat(logHandler.storedLogRecords[1])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Currently available static GTFS zip file has ETag <No ETag Provided>"
            )
    }

    @Test
    fun shouldStoreGtfsDataIfNew() {
        every {
            fakeStorage.get("tsc-gtfs-data", "static/latest.txt").getContent()
        }.returns(sampleETag1.toByteArray(Charsets.UTF_8))

        messageHandler.accept(message, null)

        assertThat(logHandler.storedLogRecords[2])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Downloading newer version and storing at gs://tsc-gtfs-data/static/2022/07/28/gtfs_0.zip"
            )

        val expectedBlob = BlobInfo.newBuilder("tsc-gtfs-data", "static/2022/07/28/gtfs_0.zip").build()
        verify {
            fakeStorage.create(expectedBlob, testBody)
        }
        assertThat(logHandler.storedLogRecords[3])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Successfully saved file, updating ETag"
            )
    }

    @Test
    fun shouldStoreGtfsDataIfNewAndUseCurrentDateIfNoLastModifiedAvailable() {
        mockkStatic(Instant::class)

        every { Instant.now() }.returns(Instant.parse("2022-04-04T22:14:00Z"))
        every {
            fakeStorage.get("tsc-gtfs-data", "static/latest.txt").getContent()
        }.returns(sampleETag1.toByteArray(Charsets.UTF_8))

        every {
            mockHttpClient.send(match { request ->
                request.method() == "HEAD" && request.uri().toString() == fakeGtfsUrl
            }, any<BodyHandler<String>>()).headers()
        }.returns(
            HttpHeaders.of(
                mapOf(
                    "ETag" to listOf(sampleETag2)
                )
            ) { _, _ -> true }
        )

        every {
            mockHttpClient.send(match { request ->
                request.method() == "GET" && request.uri().toString() == fakeGtfsUrl
            }, any<BodyHandler<InputStream>>()).body()
        }.returns(testBody)

        messageHandler.accept(message, null)

        assertThat(logHandler.storedLogRecords[2])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Downloading newer version and storing at gs://tsc-gtfs-data/static/2022/04/04/gtfs_0.zip"
            )

        val expectedBlob = BlobInfo.newBuilder("tsc-gtfs-data", "static/2022/04/04/gtfs_0.zip").build()
        verify {
            fakeStorage.create(expectedBlob, testBody)
        }
    }

    @Test
    fun shouldStoreGtfsDataIfNoPreviousETagExists() {
        every {
            fakeStorage.get("tsc-gtfs-data", "static/latest.txt")
        }.returns(null)

        every {
            mockHttpClient.send(match { request ->
                request.method() == "HEAD" && request.uri().toString() == fakeGtfsUrl
            }, any<BodyHandler<String>>()).headers()
        }.returns(
            HttpHeaders.of(
                mapOf(
                    "ETag" to listOf(sampleETag2),
                    "Last-Modified" to listOf("Thu, 28 Jul 2022 23:42:33 GMT")
                )
            ) { _, _ -> true }
        )

        every {
            mockHttpClient.send(match { request ->
                request.method() == "GET" && request.uri().toString() == fakeGtfsUrl
            }, any<BodyHandler<InputStream>>()).body()
        }.returns(testBody)

        messageHandler.accept(message, null)

        assertThat(logHandler.storedLogRecords[2])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Downloading newer version and storing at gs://tsc-gtfs-data/static/2022/07/28/gtfs_0.zip"
            )

        val expectedBlob = BlobInfo.newBuilder("tsc-gtfs-data", "static/2022/07/28/gtfs_0.zip").build()
        verify {
            fakeStorage.create(expectedBlob, testBody)
        }
    }

    @Test
    fun shouldUpdateSavedETagIfOneAlreadyPresent() {
        every {
            fakeStorage.get("tsc-gtfs-data", "static/latest.txt").getContent()
        }.returns(sampleETag1.toByteArray(Charsets.UTF_8))

        messageHandler.accept(message, null)

        val latestFileBlob = BlobInfo.newBuilder("tsc-gtfs-data", "static/latest.txt").build()
        verify {
            fakeStorage.create(
                latestFileBlob, sampleETag2.toByteArray(Charsets.UTF_8))
        }

        assertThat(logHandler.storedLogRecords[4])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Successfully updated ETag"
            )
    }

    @Test
    fun shouldSaveETagIfOneNotAlreadyPresent() {
        every {
            fakeStorage.get("tsc-gtfs-data", "static/latest.txt")
        }.returns(null)

        messageHandler.accept(message, null)

        val latestFileBlob = BlobInfo.newBuilder("tsc-gtfs-data", "static/latest.txt").build()
        verify {
            fakeStorage.create(
                latestFileBlob, sampleETag2.toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun shouldNotStoreGtfsDataIfAlreadyExists() {
        every {
            fakeStorage.get("tsc-gtfs-data", "static/latest.txt").getContent()
        }.returns(sampleETag2.toByteArray(Charsets.UTF_8))

        messageHandler.accept(message, null)

        assertThat(logHandler.storedLogRecords[2])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Latest ETag matches current ETag, skipping download."
            )

        verify(exactly = 0) {
            mockHttpClient.send(match { request ->
                request.method() == "GET" && request.uri().toString() == fakeGtfsUrl
            }, any<BodyHandler<ByteArray>>())
        }

        verify(exactly = 0) {
            fakeStorage.create(any(), any<ByteArray>())
        }
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun setUpForAll() {
            logger.addHandler(logHandler)
        }

        private const val fakeGtfsUrl = "http://fake.url.com/gtfs"
        private const val sampleETag1 = "807a42b5dba2d81:0"
        private const val sampleETag2 = "807a42b5dba2d82:0"
        private val testBody = "Test Body".byteInputStream(Charsets.UTF_8)
        private val logHandler = TestLogHandler()
        private val logger = Logger.getLogger(FetchStaticGtfsData::class.qualifiedName)

        private val data = Base64.getEncoder().encodeToString(
            """{
                "trigger": "fetch-gtfs-data"
            }""".trimIndent().toByteArray(Charsets.UTF_8)
        )
        private val message = PubSubMessage(data, "messageId", "2022-01-01T00:00:00Z", emptyMap())
    }
}
