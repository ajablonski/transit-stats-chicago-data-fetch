package com.github.ajablonski

import kotlinx.serialization.Serializable

@Serializable
data class Secrets(val trainTrackerApiKey: String, val busTrackerApiKey: String)