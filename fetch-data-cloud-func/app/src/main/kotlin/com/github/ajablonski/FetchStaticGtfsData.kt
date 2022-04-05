package com.github.ajablonski

import java.time.Instant
import java.util.logging.Logger

class FetchStaticGtfsData {
    fun accept(payload: PubSubMessage) {
        logger.info("Attempting to fetch data at ${Instant.now()}")
    }

    companion object {
        private val logger = Logger.getLogger(FetchStaticGtfsData::class.java.name)
    }
}

data class PubSubMessage(
    val data: String,
    val messageId: String,
    val publishTime: String,
    val attributes: Map<String, String>
)
