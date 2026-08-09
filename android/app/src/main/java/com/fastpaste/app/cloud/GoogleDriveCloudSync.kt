package com.fastpaste.app.cloud

import com.fastpaste.app.data.ClipboardEntry
import com.fastpaste.app.data.ClipboardPayload
import com.fastpaste.app.sync.EncryptionStore
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
    val entriesToMerge: List<ClipboardEntry>,
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
        encryptionStore: EncryptionStore? = null,
        isDeleted: (String, Long, Boolean) -> Boolean = { _, _, _ -> false }
    ): CloudSyncResult =
        withContext(Dispatchers.IO) {
            val remoteFile = findCloudFile(accessToken)
            val downloaded = remoteFile
                ?.let { downloadEntries(accessToken, it.id, encryptionStore) }
                ?: DownloadResult(emptyList(), false)
            val downloadedRemoteEntries = downloaded.entries
            val normalizedRemote = manifestEntries(mergeEntries(downloadedRemoteEntries))
            val remoteEntries = downloadedRemoteEntries
                .filterNot { isDeleted(it.text, it.timestamp, it.pinned) }

            val activeLocalEntries = localEntries
                .filterNot { isDeleted(it.content, it.timestamp, it.pinned) }
            val localCloudEntries = activeLocalEntries.map { it.toCloudEntry() }
            val merged = mergeEntries(remoteEntries + localCloudEntries)
            uploadMissingBlobs(accessToken, merged, encryptionStore)
            val mergedManifest = manifestEntries(merged)
            val toMerge = remoteEntries.map {
                ClipboardEntry(
                    content = it.text,
                    source = if (it.source == SOURCE_ANDROID) "LOCAL" else "REMOTE",
                    sourceApp = it.sourceApp,
                    sourceTitle = it.sourceTitle,
                    sourceIcon = it.sourceIcon,
                    timestamp = it.timestamp,
                    pinned = it.pinned,
                    folder = it.folder,
                    payloadType = it.payload?.kind ?: ClipboardPayload.KIND_TEXT,
                    mimeType = it.payload?.mimeType ?: "text/plain",
                    htmlContent = it.payload?.html.orEmpty(),
                    payloadData = it.payload?.data.orEmpty(),
                    thumbnail = it.payload?.thumbnail.orEmpty(),
                    filesJson = it.payload?.filesJson().orEmpty().ifBlank { "[]" },
                    blobId = it.blobId,
                    blobSize = it.blobSize,
                    blobReady = it.blobReady
                )
            }

            if (remoteFile == null ||
                mergedManifest != normalizedRemote ||
                downloaded.encrypted != (encryptionStore?.isEnabled == true)
            ) {
                uploadEntries(accessToken, remoteFile?.id, mergedManifest, encryptionStore)
            }
            CloudSyncResult(entriesToMerge = toMerge, mergedCount = merged.size)
        }

    private fun findCloudFile(accessToken: String): CloudFile? = findNamedFile(accessToken, FILE_NAME)

    private fun findNamedFile(accessToken: String, fileName: String): CloudFile? {
        val query = URLEncoder.encode("name='$fileName' and trashed=false", "UTF-8")
        val request = Request.Builder()
            .url("$DRIVE_FILES_URL?spaces=appDataFolder&q=$query&orderBy=modifiedTime%20desc&fields=files(id%2Cname%2CmodifiedTime)")
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

    private fun downloadEntries(
        accessToken: String,
        fileId: String,
        encryptionStore: EncryptionStore?
    ): DownloadResult {
        val encodedFileId = encodePathSegment(fileId)
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$encodedFileId?alt=media")
            .addHeader("Authorization", "Bearer $accessToken")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Drive download failed: HTTP ${response.code}")
            val wirePayload = response.body?.string().orEmpty()
            val decrypted = encryptionStore?.decryptIfEncrypted(wirePayload)
            val payload = decrypted ?: wirePayload
            val json = JSONObject(payload)
            val entries = json.optJSONArray("entries") ?: JSONArray()
            return DownloadResult(parseEntries(entries), decrypted != null)
        }
    }

    private suspend fun uploadEntries(
        accessToken: String,
        fileId: String?,
        entries: List<CloudEntry>,
        encryptionStore: EncryptionStore?
    ) {
        val plainPayload = JSONObject()
             .put("schema", 2)
            .put("updatedAt", System.currentTimeMillis())
            .put("entries", JSONArray().also { array ->
                entries.forEach { entry ->
                    array.put(
                        JSONObject()
                            .put("text", entry.text)
                            .put("timestamp", entry.timestamp)
                            .put("source", entry.source)
                            .put("sourceApp", entry.sourceApp)
                            .put("sourceTitle", entry.sourceTitle)
                            .put("sourceIcon", entry.sourceIcon)
                            .put("pinned", entry.pinned)
                            .put("folder", entry.folder)
                            .put("blobId", entry.blobId)
                            .put("blobSize", entry.blobSize)
                            .put("blobReady", entry.blobReady)
                            .also { item ->
                                entry.payload?.let { item.put("payload", it.toJson()) }
                            }
                    )
                }
            })
            .toString()
        val payload = if (encryptionStore?.isEnabled == true) {
            encryptionStore.protect(plainPayload, EncryptionStore.TYPE_ENCRYPTED_DRIVE)
        } else {
            plainPayload
        }

        if (fileId == null) {
            executeUploadWithRetry({ createRequest(accessToken, payload, FILE_NAME) }, "Drive create")
        } else {
            val updateResult = runCatching {
                executeUploadWithRetry({ updateRequest(accessToken, fileId, payload) }, "Drive update")
            }
            if (updateResult.isFailure) {
                val updateError = updateResult.exceptionOrNull()?.message ?: "Drive update lỗi"
                runCatching {
                    executeUploadWithRetry({ createRequest(accessToken, payload, FILE_NAME) }, "Drive create")
                }.getOrElse { createError ->
                    error(
                        "$updateError; đã thử tạo file Google Drive mới nhưng cũng lỗi: " +
                            (createError.message ?: "không rõ")
                    )
                }
                runCatching { trashCloudFile(accessToken, fileId) }
            }
        }
    }

    private fun trashCloudFile(accessToken: String, fileId: String) {
        val encodedFileId = encodePathSegment(fileId)
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$encodedFileId")
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Content-Type", "application/json")
            .patch(JSONObject().put("trashed", true).toString().toRequestBody())
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Drive cleanup failed: HTTP ${response.code}")
        }
    }

    private fun createRequest(accessToken: String, payload: String, fileName: String): Request {
        val boundary = "fastpaste_${System.currentTimeMillis()}"
        val metadata = JSONObject()
            .put("name", fileName)
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
            val payload = item.optJSONObject("payload")?.let(ClipboardPayload::fromJson)
            if (payload?.isWithinLimit() == false) continue
            parsed += CloudEntry(
                text = text,
                timestamp = item.optLong("timestamp", System.currentTimeMillis()),
                source = item.optString("source", SOURCE_PC),
                sourceApp = item.optString("sourceApp", item.optString("source_app", "")),
                sourceTitle = item.optString("sourceTitle", item.optString("source_title", "")),
                sourceIcon = item.optString("sourceIcon", item.optString("source_icon", "")),
                pinned = item.optBoolean("pinned", false),
                folder = cleanFolderName(item.optString("folder", "")),
                payload = payload,
                blobId = item.optString("blobId").ifBlank {
                    payload?.takeIf { it.kind != ClipboardPayload.KIND_TEXT }?.fingerprint().orEmpty()
                },
                blobSize = item.optLong("blobSize", payload?.encodedSize() ?: 0L),
                blobReady = if (payload != null && payload.kind != ClipboardPayload.KIND_TEXT) {
                    payload.data.isNotBlank() || payload.files.any { it.data.isNotBlank() }
                } else {
                    item.optBoolean("blobReady", true)
                }
            )
        }
        return parsed
    }

    private fun mergeEntries(entries: List<CloudEntry>): List<CloudEntry> {
        return entries
            .groupBy { it.blobId.ifBlank { it.payload?.fingerprint() ?: it.text } }
            .map { (_, duplicates) ->
                val newest = duplicates.maxBy { it.timestamp }
                val pinned = duplicates.any { it.pinned }
                val folder = duplicates.firstOrNull { it.folder.isNotBlank() }?.folder.orEmpty()
                val ready = duplicates.firstOrNull { it.hasPayloadBody() }
                newest.copy(
                    pinned = pinned,
                    folder = newest.folder.ifBlank { folder },
                    payload = ready?.payload ?: newest.payload,
                    blobReady = ready != null || newest.blobReady
                )
            }
            .sortedWith(compareByDescending<CloudEntry> { it.timestamp }.thenBy { it.text })
            .let { sorted ->
                val pinned = sorted.filter { it.pinned }
                val nonPinned = sorted
                    .filterNot { it.pinned }
                    .take((MAX_CLOUD_ITEMS - pinned.size).coerceAtLeast(0))
                (pinned + nonPinned)
                    .sortedWith(compareByDescending<CloudEntry> { it.timestamp }.thenBy { it.text })
            }
    }

    private fun ClipboardEntry.toCloudEntry(): CloudEntry {
        return CloudEntry(
            text = content,
            timestamp = timestamp,
            source = if (source == "LOCAL") SOURCE_ANDROID else SOURCE_PC,
            sourceApp = sourceApp,
            sourceTitle = sourceTitle,
            sourceIcon = sourceIcon,
            pinned = pinned,
            folder = folder,
            payload = ClipboardPayload.fromEntry(this).takeIf {
                it.kind != ClipboardPayload.KIND_TEXT
            },
            blobId = blobId,
            blobSize = blobSize,
            blobReady = blobReady
        )
    }

    private fun CloudEntry.hasPayloadBody(): Boolean = payload
        ?.takeIf { it.kind != ClipboardPayload.KIND_TEXT }
        ?.let { it.data.isNotBlank() || it.files.any { file -> file.data.isNotBlank() } }
        ?: false

    private fun manifestEntries(entries: List<CloudEntry>): List<CloudEntry> = entries.map { entry ->
        val payload = entry.payload
        if (payload == null || payload.kind == ClipboardPayload.KIND_TEXT) {
            entry
        } else {
            entry.copy(
                payload = ClipboardPayload.fromJson(payload.metadataJson()),
                blobId = entry.blobId.ifBlank { payload.fingerprint() },
                blobSize = entry.blobSize.takeIf { it > 0 } ?: payload.encodedSize(),
                blobReady = false
            )
        }
    }

    private suspend fun uploadMissingBlobs(
        accessToken: String,
        entries: List<CloudEntry>,
        encryptionStore: EncryptionStore?
    ) {
        val remoteNames = listBlobNames(accessToken)
        val uploaded = mutableSetOf<String>()
        entries.forEach { entry ->
            val payload = entry.payload?.takeIf { entry.hasPayloadBody() } ?: return@forEach
            val blobId = entry.blobId.ifBlank { payload.fingerprint() }
            val fileName = blobFileName(blobId)
            if (fileName in remoteNames || !uploaded.add(fileName)) return@forEach
            val plain = payload.toJson().toString()
            val wire = if (encryptionStore?.isEnabled == true) {
                encryptionStore.protect(plain, EncryptionStore.TYPE_ENCRYPTED_DRIVE_BLOB)
            } else {
                plain
            }
            executeUploadWithRetry(
                { createRequest(accessToken, wire, fileName) },
                "Drive upload blob"
            )
        }
    }

    private fun listBlobNames(accessToken: String): Set<String> {
        val query = URLEncoder.encode("name contains '$BLOB_PREFIX' and trashed=false", "UTF-8")
        val request = Request.Builder()
            .url("$DRIVE_FILES_URL?spaces=appDataFolder&q=$query&pageSize=1000&fields=files(id%2Cname)")
            .addHeader("Authorization", "Bearer $accessToken")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Drive list blob failed: HTTP ${response.code}")
            val files = JSONObject(response.body?.string().orEmpty()).optJSONArray("files") ?: JSONArray()
            return buildSet {
                for (index in 0 until files.length()) {
                    files.optJSONObject(index)?.optString("name")?.takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }
    }

    suspend fun downloadBlob(
        accessToken: String,
        blobId: String,
        encryptionStore: EncryptionStore?
    ): ClipboardPayload? = withContext(Dispatchers.IO) {
        require(blobId.length >= 16 && blobId.all(Char::isLetterOrDigit)) { "Mã blob Drive không hợp lệ" }
        val file = findNamedFile(accessToken, blobFileName(blobId)) ?: return@withContext null
        val request = Request.Builder()
            .url("$DRIVE_FILES_URL/${encodePathSegment(file.id)}?alt=media")
            .addHeader("Authorization", "Bearer $accessToken")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Drive download blob failed: HTTP ${response.code}")
            val wire = response.body?.string().orEmpty()
            val plain = encryptionStore?.decryptIfEncrypted(wire) ?: wire
            val payload = ClipboardPayload.fromJson(JSONObject(plain))
            check(payload.isWithinLimit() && payload.fingerprint() == blobId) {
                "Blob Drive không qua được kiểm tra toàn vẹn"
            }
            payload
        }
    }

    private fun blobFileName(blobId: String) = "$BLOB_PREFIX$blobId.json"

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

    private data class DownloadResult(
        val entries: List<CloudEntry>,
        val encrypted: Boolean
    )

    private data class CloudEntry(
        val text: String,
        val timestamp: Long,
        val source: String,
        val sourceApp: String = "",
        val sourceTitle: String = "",
        val sourceIcon: String = "",
        val pinned: Boolean = false,
        val folder: String = "",
        val payload: ClipboardPayload? = null,
        val blobId: String = "",
        val blobSize: Long = 0L,
        val blobReady: Boolean = true
    )

    companion object {
        private const val FILE_NAME = "fastpaste-cloud-history.json"
        private const val BLOB_PREFIX = "fastpaste-blob-"
        private const val MAX_CLOUD_ITEMS = 1_000
        private const val UPLOAD_RETRY_ATTEMPTS = 3
        private const val SOURCE_ANDROID = "ANDROID"
        private const val SOURCE_PC = "PC"
        private const val DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"
    }
}
