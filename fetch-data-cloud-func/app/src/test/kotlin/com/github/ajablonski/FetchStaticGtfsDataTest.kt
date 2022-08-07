package com.github.ajablonski

import com.github.ajablonski.Constants.ETagHeadder
import com.google.cloud.PageImpl
import com.google.cloud.storage.Blob
import com.google.cloud.storage.BlobId
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
    private val mockHttpClient = mockk<HttpClient>(relaxed = true) {
        every {
            send(
                match { it.method() == "HEAD" && it.uri().toString() == FetchStaticGtfsData.gtfsUrl },
                any<BodyHandler<String>>()
            ).headers()
        }.returns(
            HttpHeaders.of(
                mapOf(
                    ETagHeadder to listOf(sampleETag2),
                    "Last-Modified" to listOf("Thu, 28 Jul 2022 23:42:33 GMT")
                )
            ) { _, _ -> true }
        )

        every {
            send(
                match { it.method() == "GET" && it.uri().toString() == FetchStaticGtfsData.gtfsUrl },
                any<BodyHandler<InputStream>>()
            )
        }.returns(mockk<HttpResponse<InputStream>> {
            every { body() }.returns(testBody)
            every { headers() }.returns(HttpHeaders.of(
                mapOf(
                    ETagHeadder to listOf(sampleETag2),
                    "Last-Modified" to listOf("Thu, 28 Jul 2022 23:42:33 GMT")
                )
            ) { _, _ -> true })
        })
    }
    private val mockStorage = mockk<Storage>(relaxed = true) {
        every {
            list(Constants.bucketId, *anyVararg())
        }.returns(PageImpl(null, null, emptyList()))
    }

    @BeforeEach
    fun setUp() {
        messageHandler = FetchStaticGtfsData()
        messageHandler.httpClient = mockHttpClient
        messageHandler.storage = mockStorage
        logHandler.clear()
    }

    @Test
    fun shouldLoadMostRecentGtfsEtagFirstAndLogValue() {
        every {
            mockStorage.get(BlobId.of(Constants.bucketId, "static/latest.txt")).getContent()
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
            mockStorage.get(BlobId.of(Constants.bucketId, "static/latest.txt"))
        }.returns(null)

        messageHandler.accept(message, null)

        assertThat(logHandler.storedLogRecords[0])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Could not find static/latest.txt file, will download current GTFS zip file."
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
                request.method() == "HEAD" && request.uri().toString() == FetchStaticGtfsData.gtfsUrl
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
            mockStorage.get(Constants.bucketId, "static/latest.txt").getContent()
        }.returns(sampleETag1.toByteArray(Charsets.UTF_8))

        messageHandler.accept(message, null)

        assertThat(logHandler.storedLogRecords[2])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Downloading newer version and storing at gs://tsc-gtfs-data/static/2022/07/28/gtfs_0.zip"
            )

        val expectedBlob = BlobInfo.newBuilder(Constants.bucketId, "static/2022/07/28/gtfs_0.zip").build()
        verify {
            mockStorage.createFrom(expectedBlob, testBody)
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
            mockStorage.get(Constants.bucketId, "static/latest.txt").getContent()
        }.returns(sampleETag1.toByteArray(Charsets.UTF_8))

        val mockBlob = mockk<Blob>()
        every {
            mockStorage.list(Constants.bucketId, Storage.BlobListOption.prefix("static/2022/07/28/gtfs_"))
        }.returns(PageImpl(null, null, listOf(mockBlob)))
        every { mockBlob.name }.returns("static/2022/07/28/gtfs_1.zip")

        messageHandler.accept(message, null)

        assertThat(logHandler.storedLogRecords[2])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Downloading newer version and storing at gs://tsc-gtfs-data/static/2022/07/28/gtfs_2.zip"
            )


        val expectedBlob = BlobInfo.newBuilder(Constants.bucketId, "static/2022/07/28/gtfs_2.zip").build()
        verify {
            mockStorage.createFrom(expectedBlob, testBody)
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
            mockStorage.get(Constants.bucketId, "static/latest.txt").getContent()
        }.returns(sampleETag1.toByteArray(Charsets.UTF_8))

        val mockBlob1 = mockk<Blob> { every { name }.returns("static/2022/07/28/gtfs_2.zip") }
        val mockBlob2 = mockk<Blob> { every { name }.returns("static/2022/07/28/gtfs_1.zip") }
        val page2 = PageImpl(null, null, listOf(mockBlob2))
        val page1 = PageImpl({ page2 }, "cursor", listOf(mockBlob1))
        every {
            mockStorage.list(Constants.bucketId, Storage.BlobListOption.prefix("static/2022/07/28/gtfs_"))
        }.returns(page1)


        messageHandler.accept(message, null)

        assertThat(logHandler.storedLogRecords[2])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Downloading newer version and storing at gs://tsc-gtfs-data/static/2022/07/28/gtfs_3.zip"
            )


        val expectedBlob = BlobInfo.newBuilder(Constants.bucketId, "static/2022/07/28/gtfs_3.zip").build()
        verify {
            mockStorage.createFrom(expectedBlob, testBody)
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
            mockStorage.get(Constants.bucketId, "static/latest.txt").getContent()
        }.returns(sampleETag1.toByteArray(Charsets.UTF_8))

        every {
            mockHttpClient.send(match { request ->
                request.method() == "HEAD" && request.uri().toString() == FetchStaticGtfsData.gtfsUrl
            }, any<BodyHandler<String>>()).headers()
        }.returns(
            HttpHeaders.of(
                mapOf(
                    ETagHeadder to listOf(sampleETag2)
                )
            ) { _, _ -> true }
        )

        every {
            mockHttpClient.send(match { request ->
                request.method() == "GET" && request.uri().toString() == FetchStaticGtfsData.gtfsUrl
            }, any<BodyHandler<InputStream>>()).body()
        }.returns(testBody)

        messageHandler.accept(message, null)

        assertThat(logHandler.storedLogRecords[2])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Downloading newer version and storing at gs://tsc-gtfs-data/static/2022/04/04/gtfs_0.zip"
            )

        val expectedBlob = BlobInfo.newBuilder(Constants.bucketId, "static/2022/04/04/gtfs_0.zip").build()
        verify {
            mockStorage.createFrom(expectedBlob, testBody)
        }
    }

    @Test
    fun shouldStoreGtfsDataIfNoPreviousETagExists() {
        every {
            mockStorage.get(Constants.bucketId, "static/latest.txt")
        }.returns(null)

        every {
            mockHttpClient.send(match { request ->
                request.method() == "HEAD" && request.uri().toString() == FetchStaticGtfsData.gtfsUrl
            }, any<BodyHandler<String>>()).headers()
        }.returns(
            HttpHeaders.of(
                mapOf(
                    ETagHeadder to listOf(sampleETag2),
                    "Last-Modified" to listOf("Thu, 28 Jul 2022 23:42:33 GMT")
                )
            ) { _, _ -> true }
        )

        every {
            mockHttpClient.send(match { request ->
                request.method() == "GET" && request.uri().toString() == FetchStaticGtfsData.gtfsUrl
            }, any<BodyHandler<InputStream>>()).body()
        }.returns(testBody)

        messageHandler.accept(message, null)

        assertThat(logHandler.storedLogRecords[2])
            .hasFieldOrPropertyWithValue("level", Level.INFO)
            .hasFieldOrPropertyWithValue(
                "message",
                "Downloading newer version and storing at gs://tsc-gtfs-data/static/2022/07/28/gtfs_0.zip"
            )

        val expectedBlob = BlobInfo.newBuilder(Constants.bucketId, "static/2022/07/28/gtfs_0.zip").build()
        verify {
            mockStorage.createFrom(expectedBlob, testBody)
        }
    }

    @Test
    fun shouldUpdateSavedETagIfOneAlreadyPresent() {
        every {
            mockStorage.get(Constants.bucketId, "static/latest.txt").getContent()
        }.returns(sampleETag1.toByteArray(Charsets.UTF_8))

        messageHandler.accept(message, null)

        val latestFileBlob = BlobInfo.newBuilder(Constants.bucketId, "static/latest.txt").build()
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
            mockStorage.get(Constants.bucketId, "static/latest.txt")
        }.returns(null)

        messageHandler.accept(message, null)

        val latestFileBlob = BlobInfo.newBuilder(Constants.bucketId, "static/latest.txt").build()
        verify {
            mockStorage.create(
                latestFileBlob, sampleETag2.toByteArray(Charsets.UTF_8)
            )
        }
    }

    @Test
    fun shouldNotStoreGtfsDataIfAlreadyExists() {
        every {
            mockStorage.get(BlobId.of(Constants.bucketId, "static/latest.txt")).getContent()
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
                request.method() == "GET" && request.uri().toString() == FetchStaticGtfsData.gtfsUrl
            }, any<BodyHandler<ByteArray>>())
        }

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
