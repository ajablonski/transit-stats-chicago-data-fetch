package com.github.ajablonski

import com.github.ajablonski.Constants.ETAG_HEADER
import com.google.cloud.PageImpl
import com.google.cloud.storage.Blob
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.common.testing.TestLogHandler
import io.cloudevents.CloudEvent
import io.cloudevents.CloudEventData
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.logging.Level
import java.util.logging.Logger
import javax.net.ssl.SSLHandshakeException

class FetchStaticGtfsDataTest {
    private lateinit var messageHandler: FetchStaticGtfsData
    private val mockHttpEngine = MockEngine { request ->
        if (request.method == HttpMethod.Head && request.url.toString() == FetchStaticGtfsData.GTFS_URL) {
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    ETAG_HEADER to listOf(sampleETag2),
                    "Last-Modified" to listOf("Thu, 28 Jul 2022 23:42:33 GMT")
                )
            )
        } else if (request.method == HttpMethod.Get && request.url.toString() == FetchStaticGtfsData.GTFS_URL) {
            respond(
                content = ByteReadChannel(testBody),
                status = HttpStatusCode.OK,
                headers = headersOf(
                    ETAG_HEADER to listOf(sampleETag2),
                    "Last-Modified" to listOf("Thu, 28 Jul 2022 23:42:33 GMT")
                )
            )
        } else {
            respondBadRequest()
        }
    }
    private val mockStorage = mockk<Storage>(relaxed = true) {
        every {
            list(Constants.BUCKET_ID, *anyVararg())
        } returns PageImpl(null, null, emptyList())
    }

    @BeforeEach
    fun setUp() {
        messageHandler = FetchStaticGtfsData()
        messageHandler.httpClientEngine = mockHttpEngine
        messageHandler.storage = mockStorage
        logHandler.clear()
    }

    @Test
    fun shouldLoadMostRecentGtfsEtagFirstAndLogValue() {
        every {
            mockStorage.get(BlobId.of(Constants.BUCKET_ID, "static/latest.txt")).getContent()
        } returns sampleETag1.toByteArray(Charsets.UTF_8)

        messageHandler.accept(message)

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
            mockStorage.get(BlobId.of(Constants.BUCKET_ID, "static/latest.txt"))
        } returns null

        messageHandler.accept(message)

        assertThat(logHandler.storedLogRecords[0])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Could not find static/latest.txt file, will download current GTFS zip file."
            )
    }

    @Test
    fun shouldRetrieveCurrentEtagAndLogValue() {
        messageHandler.accept(message)

        assertThat(logHandler.storedLogRecords[1])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Currently available static GTFS zip file has ETag $sampleETag2"
            )
    }

    @Test
    fun shouldLogWhenEtagHeaderNotPresent() {
        mockHttpEngine.config.requestHandlers[0] = { request ->
            if (request.method == HttpMethod.Head && request.url.toString() == FetchStaticGtfsData.GTFS_URL) {
                respondOk()
            } else {
                respondBadRequest()
            }
        }

        messageHandler.accept(message)

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
            mockStorage.get(Constants.BUCKET_ID, "static/latest.txt").getContent()
        } returns sampleETag1.toByteArray(Charsets.UTF_8)

        messageHandler.accept(message)

        assertThat(logHandler.storedLogRecords[2])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Downloading newer version and storing at gs://tsc-gtfs-data/static/2022/07/28/gtfs_0.zip"
            )

        val expectedBlob = BlobInfo.newBuilder(Constants.BUCKET_ID, "static/2022/07/28/gtfs_0.zip").build()
        verify {
            mockStorage.createFrom(expectedBlob, match<InputStream> { it.readAllBytes().toString(Charsets.UTF_8) == testBody })
        }
        assertThat(logHandler.storedLogRecords[3])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Successfully saved file, updating ETag"
            )
    }


    @Test
    fun shouldRetryOnFailureUpToThreeTimesOnException() {
        every {
            mockStorage.get(Constants.BUCKET_ID, "static/latest.txt").getContent()
        } returns sampleETag1.toByteArray(Charsets.UTF_8)

        val originalHandler = mockHttpEngine.config.requestHandlers[0]
        mockHttpEngine.config.requestHandlers[0] = { request ->
            throw SSLHandshakeException("Error")
        }
        val errorHandler = mockHttpEngine.config.requestHandlers[0]
        mockHttpEngine.config.addHandler(errorHandler)
        mockHttpEngine.config.addHandler(errorHandler)
        mockHttpEngine.config.addHandler(originalHandler)
        mockHttpEngine.config.addHandler(originalHandler)
        mockHttpEngine.config.reuseHandlers = false
        messageHandler.accept(message)

        assertThat(logHandler.storedLogRecords[2])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Downloading newer version and storing at gs://tsc-gtfs-data/static/2022/07/28/gtfs_0.zip"
            )

        val expectedBlob = BlobInfo.newBuilder(Constants.BUCKET_ID, "static/2022/07/28/gtfs_0.zip").build()
        verify {
            mockStorage.createFrom(
                expectedBlob,
                match<InputStream> { it.readAllBytes().toString(Charsets.UTF_8) == testBody })
        }
        assertThat(logHandler.storedLogRecords[3])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Successfully saved file, updating ETag"
            )
    }


    @Test
    fun shouldRetryOnFailureUpToThreeTimesOnServerError() {
        every {
            mockStorage.get(Constants.BUCKET_ID, "static/latest.txt").getContent()
        } returns sampleETag1.toByteArray(Charsets.UTF_8)

        val originalHandler = mockHttpEngine.config.requestHandlers[0]
        mockHttpEngine.config.requestHandlers[0] = {
            respondError(HttpStatusCode.GatewayTimeout)
        }
        val errorHandler = mockHttpEngine.config.requestHandlers[0]
        mockHttpEngine.config.addHandler(errorHandler)
        mockHttpEngine.config.addHandler(errorHandler)
        mockHttpEngine.config.addHandler(originalHandler)
        mockHttpEngine.config.addHandler(originalHandler)
        mockHttpEngine.config.reuseHandlers = false
        messageHandler.accept(message)

        assertThat(logHandler.storedLogRecords[2])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Downloading newer version and storing at gs://tsc-gtfs-data/static/2022/07/28/gtfs_0.zip"
            )

        val expectedBlob = BlobInfo.newBuilder(Constants.BUCKET_ID, "static/2022/07/28/gtfs_0.zip").build()
        verify {
            mockStorage.createFrom(
                expectedBlob,
                match<InputStream> { it.readAllBytes().toString(Charsets.UTF_8) == testBody })
        }
        assertThat(logHandler.storedLogRecords[3])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Successfully saved file, updating ETag"
            )
    }

    @Test
    fun shouldStoreGtfsDataIfNewWithIncrementedIndexIfFileAlreadyExists() {
        every {
            mockStorage.get(Constants.BUCKET_ID, "static/latest.txt").getContent()
        } returns sampleETag1.toByteArray(Charsets.UTF_8)

        val mockBlob = mockk<Blob>()
        every {
            mockStorage.list(Constants.BUCKET_ID, Storage.BlobListOption.prefix("static/2022/07/28/gtfs_"))
        } returns PageImpl(null, null, listOf(mockBlob))
        every { mockBlob.name } returns "static/2022/07/28/gtfs_1.zip"

        messageHandler.accept(message)

        assertThat(logHandler.storedLogRecords[2])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Downloading newer version and storing at gs://tsc-gtfs-data/static/2022/07/28/gtfs_2.zip"
            )


        val expectedBlob = BlobInfo.newBuilder(Constants.BUCKET_ID, "static/2022/07/28/gtfs_2.zip").build()
        verify {
            mockStorage.createFrom(expectedBlob, match<InputStream> { it.readAllBytes().toString(Charsets.UTF_8) == testBody })
        }
        assertThat(logHandler.storedLogRecords[3])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Successfully saved file, updating ETag"
            )
    }

    @Test
    fun shouldStoreGtfsDataIfNewWithIncrementedIndexIfFileAlreadyExistsAndIterateThroughBlobPages() {
        every {
            mockStorage.get(Constants.BUCKET_ID, "static/latest.txt").getContent()
        } returns sampleETag1.toByteArray(Charsets.UTF_8)

        val mockBlob1 = mockk<Blob> { every { name }.returns("static/2022/07/28/gtfs_2.zip") }
        val mockBlob2 = mockk<Blob> { every { name }.returns("static/2022/07/28/gtfs_1.zip") }
        val page2 = PageImpl(null, null, listOf(mockBlob2))
        val page1 = PageImpl({ page2 }, "cursor", listOf(mockBlob1))
        every {
            mockStorage.list(Constants.BUCKET_ID, Storage.BlobListOption.prefix("static/2022/07/28/gtfs_"))
        } returns page1


        messageHandler.accept(message)

        assertThat(logHandler.storedLogRecords[2])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Downloading newer version and storing at gs://tsc-gtfs-data/static/2022/07/28/gtfs_3.zip"
            )


        val expectedBlob = BlobInfo.newBuilder(Constants.BUCKET_ID, "static/2022/07/28/gtfs_3.zip").build()
        verify {
            mockStorage.createFrom(expectedBlob, match<InputStream> { it.readAllBytes().toString(Charsets.UTF_8) == testBody })
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
            mockStorage.get(Constants.BUCKET_ID, "static/latest.txt").getContent()
        } returns sampleETag1.toByteArray(Charsets.UTF_8)

        mockHttpEngine.config.requestHandlers[0] = { request ->
            if (request.method == HttpMethod.Head && request.url.toString() == FetchStaticGtfsData.GTFS_URL) {
                respond(
                    content = ByteReadChannel.Empty,
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        ETAG_HEADER to listOf(sampleETag2),
                    )
                )
            } else if (request.method == HttpMethod.Get && request.url.toString() == FetchStaticGtfsData.GTFS_URL) {
                respond(
                    content = ByteReadChannel(testBody),
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        ETAG_HEADER to listOf(sampleETag2),
                    )
                )
            } else {
                respondBadRequest()
            }
        }
        messageHandler.accept(message)

        assertThat(logHandler.storedLogRecords[2])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Downloading newer version and storing at gs://tsc-gtfs-data/static/2022/04/04/gtfs_0.zip"
            )

        val expectedBlob = BlobInfo.newBuilder(Constants.BUCKET_ID, "static/2022/04/04/gtfs_0.zip").build()
        verify {
            mockStorage.createFrom(expectedBlob, match<InputStream> { it.readAllBytes().toString(Charsets.UTF_8) == testBody })
        }
    }

    @Test
    fun shouldStoreGtfsDataIfNoPreviousETagExists() {
        every {
            mockStorage.get(Constants.BUCKET_ID, "static/latest.txt")
        } returns null

        mockHttpEngine.config.requestHandlers[0] = { request ->
            if (request.method == HttpMethod.Head && request.url.toString() == FetchStaticGtfsData.GTFS_URL) {
                respond(
                    content = ByteReadChannel.Empty,
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        ETAG_HEADER to listOf(sampleETag2),
                        "Last-Modified" to listOf("Thu, 28 Jul 2022 23:42:33 GMT")
                    )
                )
            } else if (request.method == HttpMethod.Get && request.url.toString() == FetchStaticGtfsData.GTFS_URL) {
                respond(
                    content = ByteReadChannel(testBody),
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        ETAG_HEADER to listOf(sampleETag2),
                        "Last-Modified" to listOf("Thu, 28 Jul 2022 23:42:33 GMT")
                    )
                )
            } else {
                respondBadRequest()
            }
        }
        messageHandler.accept(message)

        assertThat(logHandler.storedLogRecords[2])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Downloading newer version and storing at gs://tsc-gtfs-data/static/2022/07/28/gtfs_0.zip"
            )

        val expectedBlob = BlobInfo.newBuilder(Constants.BUCKET_ID, "static/2022/07/28/gtfs_0.zip").build()
        verify {
            mockStorage.createFrom(expectedBlob, match<InputStream> { it.readAllBytes().toString(Charsets.UTF_8) == testBody })
        }
    }

    @Test
    fun shouldUpdateSavedETagIfOneAlreadyPresent() {
        every {
            mockStorage.get(Constants.BUCKET_ID, "static/latest.txt").getContent()
        } returns sampleETag1.toByteArray(Charsets.UTF_8)

        messageHandler.accept(message)

        val latestFileBlob = BlobInfo.newBuilder(Constants.BUCKET_ID, "static/latest.txt").build()
        verify {
            mockStorage.create(
                latestFileBlob, sampleETag2.toByteArray(Charsets.UTF_8)
            )
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
            mockStorage.get(Constants.BUCKET_ID, "static/latest.txt")
        } returns null

        messageHandler.accept(message)

        val latestFileBlob = BlobInfo.newBuilder(Constants.BUCKET_ID, "static/latest.txt").build()
        verify {
            mockStorage.create(
                latestFileBlob, sampleETag2.toByteArray(Charsets.UTF_8)
            )
        }
    }

    @Test
    fun shouldNotStoreGtfsDataIfAlreadyExists() {
        every {
            mockStorage.get(BlobId.of(Constants.BUCKET_ID, "static/latest.txt")).getContent()
        } returns sampleETag2.toByteArray(Charsets.UTF_8)

        messageHandler.accept(message)

        assertThat(logHandler.storedLogRecords[2])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Latest ETag matches current ETag, skipping download."
            )

        assertThat(mockHttpEngine.requestHistory).hasSize(1)
        assertThat(mockHttpEngine.requestHistory[0].method).isEqualTo(HttpMethod.Head)
        assertThat(mockHttpEngine.requestHistory[0].url.toString()).isEqualTo(FetchStaticGtfsData.GTFS_URL)

        verify(exactly = 0) {
            mockStorage.create(any(), any<ByteArray>())
        }
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun setUpForAll() {
            logger.addHandler(logHandler)
        }

        private const val sampleETag1 = "807a42b5dba2d81:0"
        private const val sampleETag2 = "807a42b5dba2d82:0"
        private const val testBody = "Test Body"
        private val logHandler = TestLogHandler()
        private val logger = Logger.getLogger(FetchStaticGtfsData::class.qualifiedName)

        private val messageData = """{
                "trigger": "fetch-gtfs-data"
            }""".trimIndent().toByteArray(Charsets.UTF_8)

        private val message = mockk<CloudEvent> {
            every { data } returns CloudEventData { messageData }

            every { id } returns "messageId"

            every { time } returns OffsetDateTime.of(2022, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        }
    }
}
