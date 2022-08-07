package com.github.ajablonski

import com.google.cloud.functions.BackgroundFunction
import com.google.cloud.functions.Context
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jetbrains.annotations.TestOnly
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.logging.Logger
import kotlin.io.path.readText

class FetchRealtimeGtfsData : BackgroundFunction<PubSubMessage> {
    var storage: Storage = StorageOptions.getDefaultInstance().service
        @TestOnly set
    var httpClient: HttpClient = HttpClient.newHttpClient()
        @TestOnly set
    var secretPath: Path = defaultSecretPath
        @TestOnly set

    override fun accept(payload: PubSubMessage?, context: Context?) {
        val uri = generateUri()
        logger.info("Fetching data")
        val responseBody = httpClient.send(HttpRequest.newBuilder(uri).GET().build(), BodyHandlers.ofString()).body()

        val timestamp = json.decodeFromString<PartialLocationResponse>(responseBody).ctatt.tmst

        val timestampLocalDateTime = LocalDateTime.parse(timestamp)
        val prefix = timestampLocalDateTime.format(DateTimeFormatter.ofPattern("'realtime'/YYYY/MM/dd"))
        logger.info("Storing data at gs://tsc-gtfs-data/realtime/2022/08/06/2022-08-06T18:54:12.json")
        storage.create(
            BlobInfo
                .newBuilder(
                    Constants.bucketId,
                    "$prefix/$timestamp.json"
                )
                .setContentType("application/json")
                .setCustomTime(timestampLocalDateTime.atZone(timeZone).toEpochSecond() * secondsToMilliseconds)
                .build(),
            responseBody.toByteArray()
        )
        logger.info("Data successfully stored")
    }

    private fun generateUri(): URI {
        val trainTrackerApiKey = json.decodeFromString<Secrets>(secretPath.readText()).trainTrackerApiKey

        val queryParams = mapOf(
            routeParam to routesValue,
            outputTypeParam to outputTypeValue,
            keyParam to trainTrackerApiKey
        )

        val queryString = queryParams.map { "${it.key}=${it.value}" }.joinToString("&")
        return URI("$baseUrl?$queryString")
    }

    companion object {
        private const val secondsToMilliseconds = 1000
        private const val baseUrl = "https://lapi.transitchicago.com/api/1.0/ttpositions.aspx"
        private const val routeParam = "rt"
        private const val outputTypeParam = "outputType"
        private const val outputTypeValue = "JSON"
        private const val keyParam = "key"
        private val timeZone = ZoneId.of("America/Chicago")
        private val routesValue = listOf("Red", "Blue", "Brn", "G", "Org", "P", "Pink", "Y").joinToString(",")
        private val defaultSecretPath = Path.of("/etc/secrets/gtfs_secrets.json")
        private val json = Json { ignoreUnknownKeys = true }
        private val logger = Logger.getLogger(FetchRealtimeGtfsData::class.java.name)
    }
}

@Serializable
data class Secrets(val trainTrackerApiKey: String)

@Serializable
data class PartialLocationResponse(val ctatt: PartialCtaTt)

@Serializable
data class PartialCtaTt(val tmst: String)
