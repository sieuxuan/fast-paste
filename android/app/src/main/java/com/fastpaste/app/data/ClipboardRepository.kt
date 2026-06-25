package com.fastpaste.app.data

data class HistoryMergeResult(
    val inserted: Boolean,
    val changed: Boolean
)

class ClipboardRepository(private val dao: ClipboardDao) {

    suspend fun mergeEntry(
        content: String,
        source: String,
        timestamp: Long = System.currentTimeMillis(),
        pinned: Boolean = false,
        folder: String = "",
        promoteExisting: Boolean = false
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
                    timestamp = timestamp,
                    pinned = pinned,
                    folder = cleanFolder
                )
            )
            return HistoryMergeResult(inserted = true, changed = true)
        }

        val shouldUseIncomingTime = promoteExisting || timestamp > existing.timestamp
        val nextTimestamp = if (shouldUseIncomingTime) timestamp else existing.timestamp
        val nextSource = if (shouldUseIncomingTime) source else existing.source
        val nextPinned = existing.pinned || pinned
        val nextFolder = existing.folder.ifBlank { cleanFolder }
        val changed = existing.timestamp != nextTimestamp ||
            existing.source != nextSource ||
            existing.pinned != nextPinned ||
            existing.folder != nextFolder

        if (changed) {
            dao.updateEntryById(
                id = existing.id,
                source = nextSource,
                timestamp = nextTimestamp,
                pinned = nextPinned,
                folder = nextFolder
            )
        }
        val removedDuplicates = dao.deleteDuplicatesByContent(content, existing.id)

        return HistoryMergeResult(inserted = false, changed = changed || removedDuplicates > 0)
    }

    companion object {
        fun cleanFolderName(folder: String): String {
            return folder.trim().replace(Regex("\\s+"), " ").take(MAX_FOLDER_LENGTH)
        }

        private const val MAX_FOLDER_LENGTH = 48
    }
}
