package com.fastpaste.app.service

internal class ClipboardFingerprintGate {
    private var lastSent = ""
    private var lastApplied = ""
    private var pendingAppliedEcho = ""

    @Synchronized
    fun shouldSend(fingerprint: String): Boolean {
        if (fingerprint == pendingAppliedEcho) {
            pendingAppliedEcho = ""
            return false
        }
        if (fingerprint == lastSent) return false
        lastSent = fingerprint
        return true
    }

    @Synchronized
    fun shouldApply(fingerprint: String): Boolean {
        if (fingerprint == lastApplied) return false
        markApplied(fingerprint)
        return true
    }

    @Synchronized
    fun markApplied(fingerprint: String) {
        lastApplied = fingerprint
        pendingAppliedEcho = fingerprint
    }

    @Synchronized
    fun markAppliedAndSent(fingerprint: String) {
        markApplied(fingerprint)
        lastSent = fingerprint
    }
}
