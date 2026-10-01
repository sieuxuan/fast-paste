package com.fastpaste.app.cloud

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDriveCloudSyncTest {
    private fun client(responses: List<String>, requests: MutableList<Request>): OkHttpClient {
        val pending = ArrayDeque(responses)
        return OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(chain.request())
            check(pending.isNotEmpty()) { "Unexpected Drive request: ${chain.request().url}" }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(pending.removeFirst().toResponseBody()).build()
        }.build()
    }

    @Test
    fun legacyManifestChecksAllBlobPagesBeforeWritingCheckpoint() = runBlocking {
        val requests = mutableListOf<Request>()
        val name = "fastpaste-blob-${"a".repeat(64)}.json"
        val sync = GoogleDriveCloudSync(client(listOf(
            """{"files":[{"id":"manifest"}]}""",
            """{"schema":2,"entries":[]}""",
            """{"nextPageToken":"page / 2","files":[{"id":"blob1","name":"$name"}]}""",
            """{"files":[{"id":"blob2","name":"$name"}]}""",
            """{"kind":"image","data":"AAAA"}""",
            """{"kind":"image","data":"AAAA"}""",
            "{}"
        ), requests))
        sync.merge("test-token", emptyList())
        assertEquals("page / 2", requests[3].url.queryParameter("pageToken"))
        assertEquals("/drive/v3/files/blob1", requests[4].url.encodedPath)
        assertEquals("/drive/v3/files/blob2", requests[5].url.encodedPath)
        assertEquals("PATCH", requests.last().method)
        val body = okio.Buffer()
        requests.last().body!!.writeTo(body)
        val wire = body.readUtf8()
        assertTrue(wire.contains("\"schema\":3"))
        assertTrue(wire.contains("\"blobEncryption\":false"))
    }

    @Test
    fun verifiedUnchangedManifestDoesNotRewriteOrDownloadBodies() = runBlocking {
        val requests = mutableListOf<Request>()
        val sync = GoogleDriveCloudSync(client(listOf(
            """{"files":[{"id":"manifest"}]}""",
            """{"schema":3,"blobEncryption":false,"entries":[]}""",
            """{"files":[]}"""
        ), requests))
        sync.merge("test-token", emptyList())
        assertEquals(3, requests.size)
        assertTrue(requests.all { it.method == "GET" })
    }

    @Test
    fun repeatedPageTokenAbortsWithoutPublishingCheckpoint() = runBlocking {
        val requests = mutableListOf<Request>()
        val sync = GoogleDriveCloudSync(client(listOf(
            """{"files":[{"id":"manifest"}]}""",
            """{"schema":2,"entries":[]}""",
            """{"nextPageToken":"repeat","files":[]}""",
            """{"nextPageToken":"repeat","files":[]}"""
        ), requests))
        val failure = runCatching { sync.merge("test-token", emptyList()) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(4, requests.size)
        assertTrue(requests.all { it.method == "GET" })
    }
}
