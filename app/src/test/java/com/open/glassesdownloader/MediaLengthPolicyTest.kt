package com.open.glassesdownloader

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class MediaLengthPolicyTest {
    @Test fun acceptsRoundedTSeriesIndexOnlyWithExactHttpLength() {
        MediaLengthPolicy.requireComplete(indexSize = 249L, contentLength = 255028L,
            received = 255028L, indexUnit = MediaIndex.SizeUnit.KIB_FLOOR)
    }

    @Test fun rejectsRoundedIndexWithoutExactHttpLength() {
        try {
            MediaLengthPolicy.requireComplete(indexSize = 249L, contentLength = -1L,
                received = 255028L, indexUnit = MediaIndex.SizeUnit.KIB_FLOOR)
            fail("Rounded KiB listing cannot certify the final byte")
        } catch (e: IOException) { assertTrue(e.message.orEmpty().contains("Content-Length")) }
    }

    @Test fun rejectsIndexBucketConflictingWithHttpBytes() {
        try {
            MediaLengthPolicy.requireComplete(indexSize = 249L, contentLength = 256200L,
                received = 256200L, indexUnit = MediaIndex.SizeUnit.KIB_FLOOR)
            fail("HTTP length is not in the index's KiB bucket")
        } catch (e: IOException) { assertTrue(e.message.orEmpty().contains("disagree")) }
    }

    @Test fun rejectsTruncatedBytesEvenWithinRoundedIndexBucket() {
        try {
            MediaLengthPolicy.requireComplete(indexSize = 249L, contentLength = 255028L,
                received = 254976L, indexUnit = MediaIndex.SizeUnit.KIB_FLOOR)
            fail("HTTP exact length must still be received")
        } catch (e: IOException) { assertTrue(e.message.orEmpty().contains("Incomplete")) }
    }

    @Test fun acceptsExactIndexSizeWithoutHttpContentLength() {
        MediaLengthPolicy.requireComplete(indexSize = 422L, contentLength = -1L, received = 422L)
    }

    @Test fun acceptsHttpLengthWhenIndexHasNoSize() {
        MediaLengthPolicy.requireComplete(indexSize = null, contentLength = 422L, received = 422L)
    }

    @Test fun rejectsEarlyEofEvenWhenHttpContentLengthIsMissing() {
        try {
            MediaLengthPolicy.requireComplete(indexSize = 422L, contentLength = -1L, received = 200L)
            fail("A local digest cannot prove a truncated remote file is complete")
        } catch (e: IOException) { assertTrue(e.message.orEmpty().contains("Incomplete")) }
    }

    @Test fun noRemoteSizeMeansNoVerifiedCompletion() {
        try {
            MediaLengthPolicy.requireComplete(indexSize = null, contentLength = -1L, received = 200L)
            fail("No independent completeness evidence")
        } catch (e: IOException) { assertTrue(e.message.orEmpty().contains("size")) }
    }

    @Test fun mismatchedIndexAndHttpSizesFailClosed() {
        try {
            MediaLengthPolicy.requireComplete(indexSize = 422L, contentLength = 200L, received = 200L)
            fail("Inconsistent remote lengths")
        } catch (_: IOException) { }
    }
}
