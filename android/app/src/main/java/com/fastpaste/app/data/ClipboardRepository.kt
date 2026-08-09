package com.fastpaste.app.data

data class HistoryMergeResult(
    val inserted: Boolean,
    val changed: Boolean
)

class ClipboardRepository(private val dao: ClipboardDao) {

    suspend fun mergeEntry(
        content: String,
        source: String,
        sourceApp: String = "",
        sourceTitle: String = "",
        sourceIcon: String = "",
        timestamp: Long = System.currentTimeMillis(),
        pinned: Boolean = false,
        folder: String = "",
        promoteExisting: Boolean = false,
        payload: ClipboardPayload? = null,
        blobId: String = "",
        blobSize: Long = 0L,
        blobReady: Boolean = true
    ): HistoryMergeResult {
        if (content.isEmpty()) {
            return HistoryMergeResult(inserted = false, changed = false)
        }

        val cleanFolder = cleanFolderName(folder)
        val existing = dao.getByContent(content)
        if (existing == null) {
            dao.insert(
                ClipboardEntry(
                    content = content,
                    source = source,
                    sourceApp = sourceApp.cleanSourceMeta(96),
                    sourceTitle = sourceTitle.cleanSourceMeta(160),
                    sourceIcon = sourceIcon.cleanIcon(),
                    timestamp = timestamp,
                    pinned = pinned,
                    folder = cleanFolder,
                    payloadType = payload?.kind ?: ClipboardPayload.KIND_TEXT,
                    mimeType = payload?.mimeType ?: "text/plain",
                    htmlContent = payload?.html.orEmpty(),
                    payloadData = payload?.data.orEmpty(),
                    thumbnail = payload?.thumbnail.orEmpty(),
                    filesJson = "[]",
                    blobId = blobId.ifBlank {
                        payload?.takeIf { it.kind != ClipboardPayload.KIND_TEXT }?.fingerprint().orEmpty()
                    },
                    blobSize = blobSize.takeIf { it > 0 } ?: payload?.encodedSize() ?: 0L,
                    blobReady = blobReady
                )
            )
            return HistoryMergeResult(inserted = true, changed = true)
        }

        val shouldUseIncomingTime = promoteExisting || timestamp > existing.timestamp
        val nextTimestamp = if (shouldUseIncomingTime) timestamp else existing.timestamp
        val nextSource = if (shouldUseIncomingTime) source else existing.source
        val nextSourceApp = if (shouldUseIncomingTime && sourceApp.isNotBlank()) {
            sourceApp.cleanSourceMeta(96)
        } else {
            existing.sourceApp
        }
        val nextSourceTitle = if (shouldUseIncomingTime && sourceTitle.isNotBlank()) {
            sourceTitle.cleanSourceMeta(160)
        } else {
            existing.sourceTitle
        }
        val nextSourceIcon = if (shouldUseIncomingTime && sourceIcon.isNotBlank()) {
            sourceIcon.cleanIcon()
        } else {
            existing.sourceIcon
        }
        val nextPinned = existing.pinned || pinned
        val nextFolder = existing.folder.ifBlank { cleanFolder }
        val nextPayload = if (shouldUseIncomingTime && payload != null &&
            (blobReady || !existing.blobReady)
        ) {
            payload
        } else {
            ClipboardPayload.fromEntry(existing)
        }
        val nextBlobId = blobId.ifBlank { existing.blobId }
        val nextBlobSize = blobSize.takeIf { it > 0 } ?: existing.blobSize
        val nextBlobReady = existing.blobReady || blobReady
        val changed = existing.timestamp != nextTimestamp ||
            existing.source != nextSource ||
            existing.sourceApp != nextSourceApp ||
            existing.sourceTitle != nextSourceTitle ||
            existing.sourceIcon != nextSourceIcon ||
            existing.pinned != nextPinned ||
            existing.folder != nextFolder ||
            existing.payloadType != nextPayload.kind ||
            existing.mimeType != nextPayload.mimeType ||
            existing.htmlContent != nextPayload.html ||
            existing.payloadData != nextPayload.data ||
            existing.thumbnail != nextPayload.thumbnail ||
            existing.filesJson != "[]" ||
            existing.blobId != nextBlobId ||
            existing.blobSize != nextBlobSize ||
            existing.blobReady != nextBlobReady

        if (changed) {
            dao.updateEntryById(
                id = existing.id,
                source = nextSource,
                sourceApp = nextSourceApp,
                sourceTitle = nextSourceTitle,
                sourceIcon = nextSourceIcon,
                timestamp = nextTimestamp,
                pinned = nextPinned,
                folder = nextFolder,
                payloadType = nextPayload.kind,
                mimeType = nextPayload.mimeType,
                htmlContent = nextPayload.html,
                payloadData = nextPayload.data,
                thumbnail = nextPayload.thumbnail,
                filesJson = "[]",
                blobId = nextBlobId,
                blobSize = nextBlobSize,
                blobReady = nextBlobReady
            )
        }
        val removedDuplicates = dao.deleteDuplicatesByContent(content, existing.id)

        return HistoryMergeResult(inserted = false, changed = changed || removedDuplicates > 0)
    }

    companion object {
        fun cleanFolderName(folder: String): String {
            return folder.trim().replace(Regex("\\s+"), " ").take(MAX_FOLDER_LENGTH)
        }

        private fun String.cleanSourceMeta(maxLength: Int): String {
            return trim().replace(Regex("\\s+"), " ").take(maxLength)
        }

        private fun String.cleanIcon(): String {
            val allowed = startsWith("data:image/png;base64,", ignoreCase = true) ||
                startsWith("data:image/jpeg;base64,", ignoreCase = true) ||
                startsWith("data:image/webp;base64,", ignoreCase = true)
            return takeIf { allowed }?.take(256 * 1024).orEmpty()
        }

        private const val MAX_FOLDER_LENGTH = 48
    }
}
