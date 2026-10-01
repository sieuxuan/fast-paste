package com.fastpaste.app.sync

import com.fastpaste.app.data.ClipboardPayload
import org.json.JSONObject

internal fun restoreClipboardImage(metadata: String, data: String, identity: String): ClipboardPayload? =
    ClipboardPayload.fromJson(JSONObject(metadata)).copy(data = data)
        .takeIf { it.kind == ClipboardPayload.KIND_IMAGE && it.matchesFingerprint(identity) }
