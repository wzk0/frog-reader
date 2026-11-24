package com.example.frogreader.data.model

/** One row of the storage screen: a book and what its source file costs on disk. */
data class BookStorage(
    val id: String,
    val title: String,
    val author: String?,
    val format: BookFormat,
    /** Source bytes; 0 for a data-only record whose file is gone. */
    val bytes: Long,
)

/** What the regenerable reader caches cost on disk. */
data class CacheStorage(
    val imagesBytes: Long = 0L,
    val paginationBytes: Long = 0L,
    val parsedBytes: Long = 0L,
) {
    val totalBytes: Long get() = imagesBytes + paginationBytes + parsedBytes
}
