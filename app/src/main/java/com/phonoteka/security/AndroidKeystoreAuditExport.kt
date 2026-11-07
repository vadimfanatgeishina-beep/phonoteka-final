package com.phonoteka.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** Versioned SAF export whose event list is authenticated with a non-exportable Android Keystore key. */
class AndroidKeystoreAuditExport(fixtureNamespace: String? = null) {
    init { require(fixtureNamespace == null || fixtureNamespace.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) }
    private val keyAlias = KEY_ALIAS + (fixtureNamespace?.let { ".fixture.$it" } ?: "")
    fun encode(events: List<String>): ByteArray {
        require(events.size <= MAX_EVENTS && events.all { it.length <= MAX_EVENT_CHARS })
        val canonical = canonical(events)
        val root = JSONObject()
            .put("version", FORMAT_VERSION)
            .put("events", JSONArray(events))
            .put("hmacSha256", hmac(canonical))
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    fun verify(document: ByteArray): Boolean = runCatching {
        require(document.size <= MAX_DOCUMENT_BYTES)
        val root = JSONObject(String(document, Charsets.UTF_8))
        require(root.getInt("version") == FORMAT_VERSION)
        val array = root.getJSONArray("events")
        require(array.length() <= MAX_EVENTS)
        val events = List(array.length()) { index -> array.getString(index).also { require(it.length <= MAX_EVENT_CHARS) } }
        val expected = hmac(canonical(events))
        val provided = root.getString("hmacSha256")
        MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), provided.toByteArray(Charsets.US_ASCII))
    }.getOrDefault(false)

    private fun canonical(events: List<String>) = buildString {
        append("phonoteka-audit-export-v1\n")
        events.forEach { append(it.length).append(':').append(it).append('\n') }
    }

    private fun hmac(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(getOrCreateKey())
        return mac.doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setDigests(KeyProperties.DIGEST_SHA256).build())
        return generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "phonoteka.lab14.audit.hmac.v1"
        private const val FORMAT_VERSION = 1
        private const val MAX_EVENTS = 10_000
        private const val MAX_EVENT_CHARS = 1_024
        private const val MAX_DOCUMENT_BYTES = 8 * 1024 * 1024
    }
}
