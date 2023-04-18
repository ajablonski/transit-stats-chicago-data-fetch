package com.github.ajablonski

import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

internal class BusDataFetcherTest {

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
                    request.method() == "GET"
                            && request.uri().host == "www.ctabustracker.com"
                            && request.uri().path == "/bustime/api/v2/getvehicles"
                            && queryParamMap["Key"] == fakeApiKey
                            && queryParamMap["tmres"] == "s"
                            && queryParamMap["format"] == "json"
                            && queryParamMap.containsKey("Rt")

                },
                any<HttpResponse.BodyHandler<String>>()
            )
        }.returns(mockk<HttpResponse<String>> {
            every { body() }.returns(sampleResponse)
        })
    }
    private val storage = mockk<Storage>(relaxed = true)
    private val busDataFetcher = BusDataFetcher(httpClient, storage, fakeApiKey)

    @Test
    fun shouldCallBusTrackerApiForAllKnownRoutesInBatches() {
        busDataFetcher.fetch(messageTime)

        verify {
            httpClient.send(
                match { it.uri().query.contains("Rt=1,2,3,4,X4,5,6,7,8,8A") },
                any<HttpResponse.BodyHandler<String>>()
            )
        }
        verify {
            httpClient.send(
                match { it.uri().query.contains("Rt=9,X9,10,11,12,J14,15,18,20,21") },
                any<HttpResponse.BodyHandler<String>>()
            )
        }
        verify {
            httpClient.send(
                match { it.uri().query.contains("Rt=22,24,26,28,29,31,30,34,35,36") },
                any<HttpResponse.BodyHandler<String>>()
            )
        }
        verify {
            httpClient.send(
                match { it.uri().query.contains("Rt=37,39,43,44,47,48,49,49B,X49,50") },
                any<HttpResponse.BodyHandler<String>>()
            )
        }
        verify {
            httpClient.send(
                match { it.uri().query.contains("Rt=51,52,52A,53,53A,54,54A,54B,55,55A") },
                any<HttpResponse.BodyHandler<String>>()
            )
        }
        verify {
            httpClient.send(
                match { it.uri().query.contains("Rt=55N,56,57,59,60,62,62H,63,63W,65") },
                any<HttpResponse.BodyHandler<String>>()
            )
        }
        verify {
            httpClient.send(
                match { it.uri().query.contains("Rt=66,67,68,70,71,72,73,74,75,76") },
                any<HttpResponse.BodyHandler<String>>()
            )
        }
        verify {
            httpClient.send(
                match { it.uri().query.contains("Rt=77,78,79,80,81,81W,82,84,95,85") },
                any<HttpResponse.BodyHandler<String>>()
            )
        }
        verify {
            httpClient.send(
                match { it.uri().query.contains("Rt=85A,86,87,88,90,91,92,93,94,96") },
                any<HttpResponse.BodyHandler<String>>()
            )
        }
        verify {
            httpClient.send(
                match { it.uri().query.contains("Rt=97,X98,100,103,106,108,111,111A,112,115") },
                any<HttpResponse.BodyHandler<String>>()
            )
        }
        verify {
            httpClient.send(
                match { it.uri().query.contains("Rt=119,120,121,124,125,126,130,134,135,136") },
                any<HttpResponse.BodyHandler<String>>()
            )
        }
        verify {
            httpClient.send(
                match { it.uri().query.contains("Rt=143,146,147,148,151,152,155,156,157,165") },
                any<HttpResponse.BodyHandler<String>>()
            )
        }
        verify {
            httpClient.send(
                match { it.uri().query.contains("Rt=169,171,172,192,201,206") },
                any<HttpResponse.BodyHandler<String>>()
            )
        }
    }

    @Test
    fun shouldSaveCombinedResultsToStorageBasedOnTriggerTimestampInCentralTime() {
        busDataFetcher.fetch(ZonedDateTime.of(2022, 8, 22, 1, 2, 3, 0, ZoneOffset.UTC))

        verify {
            storage.create(
                BlobInfo.newBuilder(Constants.bucketId, "realtime/raw/bus/2022/08/21/2022-08-21T20:02:03.json").build(),
                match<ByteArray> { it.size == sampleResponse.length * 13 + 12 }
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