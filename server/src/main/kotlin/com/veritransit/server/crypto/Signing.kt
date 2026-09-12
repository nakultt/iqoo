package com.veritransit.server.crypto

import com.veritransit.core.LabelToken
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Ed25519 label and certificate signing (§4.2, §5.2, §5.4).
 *
 * The JDK has had native Ed25519 since 15, so the server needs no crypto
 * library at all. The Android side is the exception — `minSdk 31` predates
 * platform Ed25519 (API 33), which is why §6.1 puts Tink on the device but not
 * here.
 *
 * The private key never leaves this process: it is read from the PEM that the
 * key ceremony produced (§10) and is not exposed through any route. Only the
 * public half is published, via `signing_keys` and the bootstrap response.
 */
class Signer(private val privateKey: PrivateKey, val keyId: String) {

    fun sign(bytes: ByteArray): ByteArray =
        Signature.getInstance("Ed25519").run {
            initSign(privateKey)
            update(bytes)
            sign()
        }

    fun signToB64(bytes: ByteArray): String = b64u(sign(bytes))

    /**
     * Issues the §4.2 token for a package. Callers pass the issue date so a
     * batch of labels printed in one motion all carry the same `T`, which makes
     * a reprint visibly different from an original.
     */
    fun issueLabel(packageCode: String, shipmentRef: String, copyNo: Int, issuedEpochDay: Long): LabelToken {
        val body = LabelToken.bodyToSign(packageCode, shipmentRef, copyNo, issuedEpochDay)
        return LabelToken(packageCode, shipmentRef, copyNo, issuedEpochDay, signToB64(body.toByteArray()))
    }

    companion object {
        fun fromPem(pem: File, keyId: String): Signer {
            val der = Base64.getMimeDecoder().decode(
                pem.readText()
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .trim()
            )
            val key = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(der))
            return Signer(key, keyId)
        }
    }
}

/** Verifies signatures against published public keys — the same check a device runs offline. */
class Verifier(private val keys: Map<String, PublicKey>) {

    fun verify(keyId: String, bytes: ByteArray, signature: ByteArray): Boolean {
        val key = keys[keyId] ?: return false
        return runCatching {
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(bytes)
                verify(signature)
            }
        }.getOrDefault(false)
    }

    /** Verifies a scanned token against any active key, as the device does. */
    fun verifyToken(token: LabelToken): Boolean {
        val body = token.signedBody.toByteArray()
        val sig = runCatching { token.signatureBytes() }.getOrNull() ?: return false
        return keys.keys.any { verify(it, body, sig) }
    }

    companion object {
        /**
         * Wraps a raw 32-byte Ed25519 public key in the SubjectPublicKeyInfo
         * envelope `KeyFactory` expects. Storing the raw key is what lets the
         * Android side hold it as 44 characters of app config rather than a
         * certificate blob.
         */
        fun publicKeyFromRaw(raw: ByteArray): PublicKey {
            require(raw.size == 32) { "Ed25519 public key must be 32 bytes, got ${raw.size}" }
            val spki = byteArrayOf(
                0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
            ) + raw
            return KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spki))
        }

        fun of(entries: Map<String, String>): Verifier = Verifier(
            entries.mapNotNull { (id, b64) ->
                runCatching { id to publicKeyFromRaw(b64uDecode(b64)) }.getOrNull()
            }.toMap()
        )
    }
}

// ------------------------------------------------------------ utilities

fun b64u(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

fun b64uDecode(s: String): ByteArray =
    Base64.getUrlDecoder().decode(if (s.length % 4 == 0) s else s + "=".repeat(4 - s.length % 4))

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun sha256Hex(text: String): String = sha256Hex(text.toByteArray())

/** Device API keys: random, shown once, stored only as a digest (§8.1 `api_key_hash`). */
object ApiKeys {
    private val random = SecureRandom()
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    fun generate(): String = "vt_" + (1..32).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    fun hash(key: String): String = "sha256:" + sha256Hex(key)

    /** Constant-time comparison; an API key check should not leak its prefix by timing. */
    fun matches(key: String, storedHash: String): Boolean {
        val a = hash(key).toByteArray()
        val b = storedHash.toByteArray()
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
