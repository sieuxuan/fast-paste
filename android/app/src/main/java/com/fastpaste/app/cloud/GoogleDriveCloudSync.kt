package com.fastpaste.app.cloud

import com.fastpaste.app.data.ClipboardEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class CloudSyncResult(
    val entriesToInsert: List<ClipboardEntry>,
    val mergedCount: Int
)

class GoogleDriveCloudSync(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
) {
    suspend fun merge(
        accessToken: String,
        localEntries: List<ClipboardEntry>,
        isDeleted: (String, Long) -> Boolean = { _, _ -> false }
    ): CloudSyncResult =
        withContext(Dispatchers.IO) {
            val remoteFile = findCloudFile(accessToken)
            val remoteEntries = remoteFile
                ?.let { downloadEntries(accessToken, it.id) }
                .orEmpty()
                .filterNot { isDeleted(it.text, it.timestamp) }

            val activeLocalEntries = localEntries
                .filterNot { isDeleted(it.content, it.timestamp) }
            val localCloudEntries = activeLocalEntries.map { it.toCloudEntry() }
            val merged = mergeEntries(remoteEntries + localCloudEntries)
            val localTexts = activeLocalEntries.mapTo(mutableSetOf()) { it.content }
            val toInsert = remoteEntries
                .filterNot { localTexts.contains(it.text) }
                .map {
                    ClipboardEntry(
                        content = it.text,
                        source = if (it.source == SOURCE_ANDROID) "LOCAL" else "REMOTE",
                        timestamp = it.timestamp,
                        pinned = it.pinned,
                        folder = it.folder
                    )
                }

            uploadEntries(accessToken, remoteFile?.id, merged)
            CloudSyncResult(entriesToInsert = toInsert, mergedCount = merged.size)
        }

    private fun findCloudFile(accessToken: String): CloudFile? {
        val request = Request.Builder()
            .url(LIST_URL)
            .addHeader("Authorization", "Bearer $accessToken")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Drive list failed: HTTP ${response.code}")
            val files = JSONObject(response.body?.string().orEmpty()).optJSONArray("files") ?: JSONArray()
            if (files.length() == 0) return null
            val file = files.getJSONObject(0)
            return CloudFile(id = file.getString("id"))
        }
    }

    private fun downloadEntries(accessToken: String, fileId: String): List<CloudEntry> {
        val encodedFileId = encodePathSegment(fileId)
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$encodedFileId?alt=media")
            .addHeader("Authorization", "Bearer $accessToken")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Drive download failed: HTTP ${response.code}")
            val json = JSONObject(response.body?.string().orEmpty())
            val entries = json.optJSONArray("entries") ?: JSONArray()
            return parseEntries(entries)
        }
    }

    private suspend fun uploadEntries(accessToken: String, fileId: String?, entries: List<CloudEntry>) {
        val payload = JSONObject()
            .put("schema", 1)
            .put("updatedAt", System.currentTimeMillis())
            .put("entries", JSONArray().also { array ->
                entries.forEach { entry ->
                    array.put(
                        JSONObject()
                            .put("text", entry.text)
                            .put("timestamp", entry.timestamp)
                            .put("source", entry.source)
                            .put("pinned", entry.pinned)
                            .put("folder", entry.folder)
                    )
                }
            })
            .toString()

        if (fileId == null) {
            executeUploadWithRetry({ createRequest(accessToken, payload) }, "Drive create")
        } else {
            val updateResult = runCatching {
                executeUploadWithRetry({ updateRequest(accessToken, fileId, payload) }, "Drive update")
            }
            if (updateResult.isFailure) {
                val updateError = updateResult.exceptionOrNull()?.message ?: "Drive update lỗi"
                runCatching {
                    executeUploadWithRetry({ createRequest(accessToken, payload) }, "Drive create")
                }.getOrElse { createError ->
                    error(
                        "$updateError; đã thử tạo file Google Drive mới nhưng cũng lỗi: " +
                            (createError.message ?: "không rõ")
                    )
                }
            }
        }
    }

    private fun createRequest(accessToken: String, payload: String): Request {
        val boundary = "fastpaste_${System.currentTimeMillis()}"
        val metadata = JSONObject()
            .put("name", FILE_NAME)
            .put("parents", JSONArray().put("appDataFolder"))
            .toString()
        val body = multipartBody(boundary, metadata, payload)

        return Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Content-Type", "multipart/related; boundary=$boundary")
            .post(body.toRequestBody())
            .build()
    }

    private fun updateRequest(accessToken: String, fileId: String, payload: String): Request {
        val boundary = "fastpaste_${System.currentTimeMillis()}"
        val metadata = JSONObject()
            .put("name", FILE_NAME)
            .toString()
        val body = multipartBody(boundary, metadata, payload)
        val encodedFileId = encodePathSegment(fileId)

        return Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files/$encodedFileId?uploadType=multipart&fields=id")
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Content-Type", "multipart/related; boundary=$boundary")
            .patch(body.toRequestBody())
            .build()
    }

    private suspend fun executeUploadWithRetry(buildRequest: () -> Request, label: String) {
        var lastError = "$label lỗi"
        for (attempt in 1..UPLOAD_RETRY_ATTEMPTS) {
            try {
                client.newCall(buildRequest()).execute().use { response ->
                    if (response.isSuccessful) return

                    val body = response.body?.string().orEmpty()
                    lastError = "$label HTTP ${response.code}: $body"
                    if (!shouldRetryUpload(response.code) || attempt == UPLOAD_RETRY_ATTEMPTS) {
                        error(lastError)
                    }
                }
            } catch (error: IOException) {
                lastError = "$label lỗi: ${error.message ?: error.javaClass.simpleName}"
                if (attempt == UPLOAD_RETRY_ATTEMPTS) error(lastError)
            }

            delay(700L * attempt)
        }

        error(lastError)
    }

    private fun multipartBody(boundary: String, metadata: String, payload: String): String {
        return buildString {
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata)
            append("\r\n--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(payload)
            append("\r\n--$boundary--\r\n")
        }
    }

    private fun parseEntries(entries: JSONArray): List<CloudEntry> {
        val parsed = mutableListOf<CloudEntry>()
        for (index in 0 until entries.length()) {
            val item = entries.optJSONObject(index) ?: continue
            val text = item.optString("text")
            if (text.isBlank()) continue
            parsed += CloudEntry(
                text = text,
                timestamp = item.optLong("timestamp", System.currentTimeMillis()),
                source = item.optString("source", SOURCE_PC),
                pinned = item.optBoolean("pinned", false),
                folder = cleanFolderName(item.optString("folder", ""))
            )
        }
        return parsed
    }

    private fun mergeEntries(entries: List<CloudEntry>): List<CloudEntry> {
        return entries
            .groupBy { it.text }
            .map { (_, duplicates) ->
                val newest = duplicates.maxBy { it.timestamp }
                val pinned = duplicates.any { it.pinned }
                val folder = duplicates.firstOrNull { it.folder.isNotBlank() }?.folder.orEmpty()
                newest.copy(pinned = pinned, folder = newest.folder.ifBlank { folder })
            }
            .sortedByDescending { it.timestamp }
            .take(MAX_CLOUD_ITEMS)
    }

    private fun ClipboardEntry.toCloudEntry(): CloudEntry {
        return CloudEntry(
            text = content,
            timestamp = timestamp,
            source = if (source == "LOCAL") SOURCE_ANDROID else SOURCE_PC,
            pinned = pinned,
            folder = folder
        )
    }

    private fun cleanFolderName(folder: String): String {
        return folder.trim().replace(Regex("\\s+"), " ").take(48)
    }

    private fun shouldRetryUpload(code: Int): Boolean {
        return code == 429 || code in 500..599
    }

    private fun encodePathSegment(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    private data class CloudFile(val id: String)

    private data class CloudEntry(
        val text: String,
        val timestamp: Long,
        val source: String,
        val pinned: Boolean = false,
        val folder: String = ""
    )

    companion object {
        private const val FILE_NAME = "fastpaste-cloud-history.json"
        private const val MAX_CLOUD_ITEMS = 500
        private const val UPLOAD_RETRY_ATTEMPTS = 3
        private const val SOURCE_ANDROID = "ANDROID"
        private const val SOURCE_PC = "PC"
        private const val LIST_URL =
            "https://www.googleapis.com/drive/v3/files" +
                "?spaces=appDataFolder" +
                "&q=name%3D%27$FILE_NAME%27%20and%20trashed%3Dfalse" +
                "&orderBy=modifiedTime%20desc" +
                "&fields=files(id%2Cname%2CmodifiedTime)"
    }
}
