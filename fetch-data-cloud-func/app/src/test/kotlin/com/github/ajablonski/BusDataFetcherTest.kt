package com.github.ajablonski

import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

internal class BusDataFetcherTest {

    private val storage = mockk<Storage>(relaxed = true)

    private val mockEngine = MockEngine { request ->
        if (
            request.url.host == "www.ctabustracker.com"
            && request.url.encodedPath == "/bustime/api/v2/getvehicles"
            && request.url.parameters["key"] == fakeApiKey
            && request.url.parameters["tmres"] == "s"
            && request.url.parameters["format"] == "json"
            && request.url.parameters.contains("rt")
        ) {
            respond(
                content = ByteReadChannel(sampleResponse),
                status = HttpStatusCode.OK
            )
        } else {
            respondBadRequest()
        }
    }
    private val busDataFetcher = BusDataFetcher(mockEngine, storage, fakeApiKey)


    @Test
    fun shouldCallBusTrackerApiForAllKnownRoutesInBatches() {
        runBlocking { busDataFetcher.fetch(messageTime) }
        assertThat(mockEngine.requestHistory[0].url.encodedQuery).contains("rt=1,2,3,4,X4,5,6,7,8,8A")
        assertThat(mockEngine.requestHistory[1].url.encodedQuery).contains("rt=9,X9,10,11,12,J14,15,18,20,21")
        assertThat(mockEngine.requestHistory[2].url.encodedQuery).contains("rt=22,24,26,28,29,31,30,34,35,36")
        assertThat(mockEngine.requestHistory[3].url.encodedQuery).contains("rt=37,39,43,44,47,48,49,49B,X49,50")
        assertThat(mockEngine.requestHistory[4].url.encodedQuery).contains("rt=51,52,52A,53,53A,54,54A,54B,55,55A")
        assertThat(mockEngine.requestHistory[5].url.encodedQuery).contains("rt=55N,56,57,59,60,62,62H,63,63W,65")
        assertThat(mockEngine.requestHistory[6].url.encodedQuery).contains("rt=66,67,68,70,71,72,73,74,75,76")
        assertThat(mockEngine.requestHistory[7].url.encodedQuery).contains("rt=77,78,79,80,81,81W,82,84,95,85")
        assertThat(mockEngine.requestHistory[8].url.encodedQuery).contains("rt=85A,86,87,88,90,91,92,93,94,96")
        assertThat(mockEngine.requestHistory[9].url.encodedQuery).contains("rt=97,100,103,106,108,111,111A,112,115,119")
        assertThat(mockEngine.requestHistory[10].url.encodedQuery).contains("rt=120,121,124,125,126,130,134,135,136,143")
        assertThat(mockEngine.requestHistory[11].url.encodedQuery).contains("rt=146,147,148,151,152,155,156,157,165,169")
        assertThat(mockEngine.requestHistory[12].url.encodedQuery).contains("rt=171,172,192,201,206")
    }

    @Test
    fun shouldSaveCombinedResultsToStorageBasedOnTriggerTimestampInCentralTime() {
        runBlocking {
            busDataFetcher.fetch(ZonedDateTime.of(2022, 8, 22, 1, 2, 3, 0, ZoneOffset.UTC))
        }

        verify {
            storage.create(
                BlobInfo.newBuilder(Constants.BUCKET_ID, "realtime/raw/bus/2022/08/21/2022-08-21T20:02:03.json")
                    .build(),
                match<ByteArray> { it.size == sampleResponse.length * 13 + 12 * 2 + 2 }
            )
        }
    }

    companion object {
        private val messageTime = ZonedDateTime.of(2022, 8, 22, 1, 2, 3, 0, ZoneOffset.UTC)
        const val fakeApiKey = "fakeBusKey"
        const val sampleResponse = """{"bustime-response": {"vehicle": [
                {
                    "des": "Michigan/Chicago", 
                    "lon": "-87.61421203613281", 
                    "tablockid": "3 -712", 
                    "tatripid": "105797",
                    "hdg": "177",
                    "rt": "3",
                    "pid": 5342,
                    "tmstmp": "20120620 13:02:38", 
                    "vid": "6438",
                    "lat": "41.72489577073317", 
                    "pdist": 2950,
                    "zone": ""
                }, {
                    "des": "Michigan/Chicago", 
                    "lon": "-87.61560402664483", 
                    "tablockid": "3 -714", 
                    "tatripid": "105801",
                    "hdg": "179",
                    "rt": "3",
                    "pid": 5342,
                    "tmstmp": "20120620 13:02:52", 
                    "vid": "1295",
                    "lat": "41.779821508071",
                    "zone": "Bay 1" 
                }
            ]}}"""
    }
}