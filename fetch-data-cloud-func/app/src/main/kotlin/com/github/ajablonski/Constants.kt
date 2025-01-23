package com.github.ajablonski

object Constants {
    const val ETAG_HEADER = "ETag"
    private const val DEFAULT_BUCKET_ID = "tsc-gtfs-data"
    val BUCKET_ID: String = System.getenv("BUCKET_ID") ?: DEFAULT_BUCKET_ID
}