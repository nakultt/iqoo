package com.veritransit.inspector.crypto

import com.google.crypto.tink.subtle.Ed25519Verify
import com.veritransit.core.LabelToken
import java.security.MessageDigest
import java.util.Base64

/**
 * §4.3 layer 1 — offline signature verification.
 *
 * `minSdk` here is 31 and platform Ed25519 only arrives at API 33, so this uses
 * Tink's implementation rather than `java.security.Signature`. That is the whole
 * reason §6.1 puts Tink on the device and nothing equivalent on the server.
 *
 * Verification takes no network by design: a dock or check post with dead Wi-Fi
 * must still be able to reject a forged label, which is the single check that
 * cannot wait for sync.
 */
class LabelVerifier(publicKeys: Map<String, String>) {

    private val verifiers: List<Pair<String, Ed25519Verify>> =
        publicKeys.mapNotNull { (keyId, b64) ->
            runCatching { keyId to Ed25519Verify(decode(b64)) }.getOrNull()
        }

    val keyIds: List<String> get() = verifiers.map { it.first }
    val isConfigured: Boolean get() = verifiers.isNotEmpty()

    /**
     * True when the token's signature verifies under any pinned key. Multiple
     * keys are accepted so a rotation can overlap — old labels in the field stay
     * valid while new ones are issued under the new key.
     */
    fun verify(token: LabelToken): Boolean {
        val body = token.signedBody.toByteArray()
        val signature = runCatching { token.signatureBytes() }.getOrNull() ?: return false
        return verifiers.any { (_, v) ->
            runCatching { v.verify(signature, body); true }.getOrDefault(false)
        }
    }

    /** Parses and verifies in one step; null means "not a valid VeriTransit label". */
    fun parseAndVerify(raw: String): Pair<LabelToken, Boolean>? {
        val token = LabelToken.parse(raw) ?: return null
        return token to verify(token)
    }

    private fun decode(b64: String): ByteArray =
        Base64.getUrlDecoder().decode(b64.padEnd((b64.length + 3) / 4 * 4, '='))
}

/** §5.4 — evidence is hashed on the device before upload, so provenance survives
 *  an untrusted network. */
object EvidenceHash {
    fun sha256(bytes: ByteArray): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
