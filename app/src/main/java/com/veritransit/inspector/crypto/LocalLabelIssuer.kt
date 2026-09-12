package com.veritransit.inspector.crypto

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.subtle.Ed25519Sign
import com.veritransit.core.LabelToken

/**
 * The demo issuer — signs labels on this phone when no server is reachable, so
 * the sender→receiver walkthrough runs fully offline.
 *
 * The same Ed25519 scheme the server uses, just with the private key living in
 * app-private prefs and its public half pinned into the key table: a label it
 * issues verifies on the very phone that issued it, which is what lets one
 * device play sender and receiver in a demo.
 */
class LocalLabelIssuer(context: Context) {

    private val prefs = context.getSharedPreferences("veritransit", Context.MODE_PRIVATE)

    data class Keypair(val keyId: String, val privateKey: ByteArray, val publicKey: ByteArray)

    val keypair: Keypair by lazy {
        val priv = prefs.getString("local_priv", null)
        val pub = prefs.getString("local_pub", null)
        if (priv != null && pub != null) {
            Keypair(LOCAL_KEY_ID, Base64.decode(priv, Base64.NO_WRAP), Base64.decode(pub, Base64.NO_WRAP))
        } else {
            val kp = Ed25519Sign.KeyPair.newKeyPair()
            val key = Keypair(LOCAL_KEY_ID, kp.privateKey, kp.publicKey)
            prefs.edit()
                .putString("local_priv", Base64.encodeToString(key.privateKey, Base64.NO_WRAP))
                .putString("local_pub", Base64.encodeToString(key.publicKey, Base64.NO_WRAP))
                .apply()
            key
        }
    }

    /** Signs the exact bytes [LabelToken.bodyToSign] builds — same format the server prints. */
    fun issue(packageCode: String, shipmentRef: String, copyNo: Int = 1): LabelToken {
        val epochDay = java.time.LocalDate.now().toEpochDay()
        val signer = Ed25519Sign(keypair.privateKey)
        val body = LabelToken.bodyToSign(packageCode, shipmentRef, copyNo, epochDay)
        val sig = signer.sign(body.toByteArray())
        val sigB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(sig)
        return LabelToken(packageCode, shipmentRef, copyNo, epochDay, sigB64)
    }

    companion object {
        /** Stable id so the verifier can pin this key alongside server keys. */
        const val LOCAL_KEY_ID = "device-local"
    }
}
