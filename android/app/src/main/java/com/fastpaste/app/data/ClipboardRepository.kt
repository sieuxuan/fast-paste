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
        timestamp: Long = System.currentTimeMillis(),
        pinned: Boolean = false,
        folder: String = "",
        promoteExisting: Boolean = false,
        payload: ClipboardPayload? = null
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
                    timestamp = timestamp,
                    pinned = pinned,
                    folder = cleanFolder,
                    payloadType = payload?.kind ?: ClipboardPayload.KIND_TEXT,
                    mimeType = payload?.mimeType ?: "text/plain",
                    htmlContent = payload?.html.orEmpty(),
                    payloadData = payload?.data.orEmpty(),
                    filesJson = payload?.filesJson().orEmpty().ifBlank { "[]" }
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
        val nextPinned = existing.pinned || pinned
        val nextFolder = existing.folder.ifBlank { cleanFolder }
        val nextPayload = if (shouldUseIncomingTime && payload != null) {
            payload
        } else {
            ClipboardPayload.fromEntry(existing)
        }
        val changed = existing.timestamp != nextTimestamp ||
            existing.source != nextSource ||
            existing.sourceApp != nextSourceApp ||
            existing.sourceTitle != nextSourceTitle ||
            existing.pinned != nextPinned ||
            existing.folder != nextFolder ||
            existing.payloadType != nextPayload.kind ||
            existing.mimeType != nextPayload.mimeType ||
            existing.htmlContent != nextPayload.html ||
            existing.payloadData != nextPayload.data ||
            existing.filesJson != nextPayload.filesJson()

        if (changed) {
            dao.updateEntryById(
                id = existing.id,
                source = nextSource,
                sourceApp = nextSourceApp,
                sourceTitle = nextSourceTitle,
                timestamp = nextTimestamp,
                pinned = nextPinned,
                folder = nextFolder,
                payloadType = nextPayload.kind,
                mimeType = nextPayload.mimeType,
                htmlContent = nextPayload.html,
                payloadData = nextPayload.data,
                filesJson = nextPayload.filesJson()
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

        private const val MAX_FOLDER_LENGTH = 48
    }
}
