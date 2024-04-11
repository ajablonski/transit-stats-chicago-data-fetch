package com.github.ajablonski.serdes

import kotlinx.serialization.Serializable

@Serializable
data class PartialLocationResponse(val ctatt: PartialCtaTt)

@Serializable
data class PartialCtaTt(val tmst: String)
