package com.phonoteka.security
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
/** Private key never exported; a local mock authority, not a remote enterprise pass server. */
class AndroidInviteMac : InviteMac {
    @Synchronized override fun sign(canonical: ByteArray): ByteArray {
        val alias = "phonoteka.owned-invite.hmac.v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance("HmacSHA256", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256).build())
            generateKey()
        }
        return Mac.getInstance("HmacSHA256").run { init(key); doFinal(canonical) }
    }
}
