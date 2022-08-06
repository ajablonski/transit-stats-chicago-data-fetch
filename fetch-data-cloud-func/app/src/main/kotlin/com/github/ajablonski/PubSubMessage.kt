package com.github.ajablonski

data class PubSubMessage(
    val data: String,
    val messageID: String,
    val publishTime: String,
    val attributes: Map<String, String>
)
