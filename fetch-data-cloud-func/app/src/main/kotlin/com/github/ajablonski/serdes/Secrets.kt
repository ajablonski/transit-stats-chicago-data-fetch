package com.github.ajablonski.serdes

import kotlinx.serialization.Serializable

@Serializable
data class Secrets(val trainTrackerApiKey: String, val busTrackerApiKey: String)