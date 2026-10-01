package com.fastpaste.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class ClipboardRepositoryTest {
    private class MemoryDao {
        val rows = mutableListOf<ClipboardEntry>()
        val dao = Proxy.newProxyInstance(
            ClipboardDao::class.java.classLoader, arrayOf(ClipboardDao::class.java)
        ) { _, method, args ->
            val values = args.orEmpty()
            when (method.name) {
                "getByContent" -> rows.firstOrNull {
                    it.content == values[0] && it.payloadType == ClipboardPayload.KIND_TEXT
                }
                "getByBlobId" -> rows.firstOrNull { it.blobId == values[0] }
                "insert" -> {
                    rows += (values[0] as ClipboardEntry).copy(id = (rows.size + 1).toLong())
                    Unit
                }
                "updateEntryById" -> {
                    val index = rows.indexOfFirst { it.id == values[0] }
                    rows[index] = rows[index].copy(
                        source = values[1] as String, sourceApp = values[2] as String,
                        sourceTitle = values[3] as String, sourceIcon = values[4] as String,
                        timestamp = values[5] as Long, pinned = values[6] as Boolean,
                        folder = values[7] as String, payloadType = values[8] as String,
                        mimeType = values[9] as String, htmlContent = values[10] as String,
                        payloadData = values[11] as String, thumbnail = values[12] as String,
                        filesJson = values[13] as String, blobId = values[14] as String,
                        blobSize = values[15] as Long, blobReady = values[16] as Boolean
                    )
                    Unit
                }
                "deleteDuplicatesByContent", "deleteDuplicatesByBlobId" -> 0
                else -> error("Unexpected DAO call: ${method.name}")
            }
        } as ClipboardDao
    }

    @Test
    fun differentImagesWithTheSameLabelStaySeparate() = runBlocking {
        val store = MemoryDao()
        val repository = ClipboardRepository(store.dao)
        val first = ClipboardPayload(kind = "image", text = "same label", data = "QUJDRA==")
        val second = first.copy(data = "RUZHSA==")
        repository.mergeEntry(first.text, "REMOTE", timestamp = 1000, payload = first, blobId = "first")
        repository.mergeEntry(second.text, "REMOTE", timestamp = 2000, payload = second, blobId = "second")
        assertEquals(2, store.rows.size)
        assertEquals(setOf("QUJDRA==", "RUZHSA=="), store.rows.map { it.payloadData }.toSet())
    }

    @Test
    fun completedBlobHydratesMetadataWithoutChangingTimestamp() = runBlocking {
        val store = MemoryDao()
        val repository = ClipboardRepository(store.dao)
        val metadata = ClipboardPayload(kind = "image", text = "image")
        repository.mergeEntry(metadata.text, "REMOTE", timestamp = 1000,
            payload = metadata, blobId = "image-id", blobReady = false)
        val result = repository.mergeEntry(metadata.text, "REMOTE", timestamp = 1000,
            payload = metadata.copy(data = "QUJDRA=="), blobId = "image-id", blobReady = true)
        assertTrue(result.changed)
        assertFalse(result.inserted)
        assertEquals("QUJDRA==", store.rows.single().payloadData)
        assertTrue(store.rows.single().blobReady)
        assertEquals(1000L, store.rows.single().timestamp)
    }
}
