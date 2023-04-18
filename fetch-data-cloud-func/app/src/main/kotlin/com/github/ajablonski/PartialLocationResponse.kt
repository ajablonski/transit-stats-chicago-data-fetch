package com.github.ajablonski

import kotlinx.serialization.Serializable

@Serializable
data class PartialLocationResponse(val ctatt: PartialCtaTt)

@Serializable
data class PartialCtaTt(val tmst: String)
