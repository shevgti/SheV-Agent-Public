package com.shev.agent.model

/** Parses RFC 7233 Content-Range for a byte-range response. */
data class ModelContentRange(val start: Long, val endInclusive: Long, val totalBytes: Long?)

internal fun parseModelContentRange(value: String?): ModelContentRange? {
    val match = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)").matchEntire(value?.trim().orEmpty())
        ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
    if (start < 0L || end < start || (total != null && end >= total)) return null
    return ModelContentRange(start, end, total)
}


/** Upper bound for the pinned model artifact, independent of server-supplied headers. */
internal object ModelDownloadLimits {
    const val MAX_BYTES = 3_000_000_000L

    fun allows(bytes: Long): Boolean = bytes > 0L && bytes <= MAX_BYTES
}
