package com.phonoteka.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** Locks instances/threads and Android processes before touching the journal transaction. */
class AndroidAuditJournalFiles(val directory: File) : AuditJournalFiles {
    init { check(directory.mkdirs() || directory.isDirectory) }
    private val logFile = File(directory, "audit.log")
    private val headFile = AtomicFile(File(directory, "audit.head"))
    private val pendingFile = AtomicFile(File(directory, "audit.pending"))
    private val monitor = monitors.computeIfAbsent(directory.canonicalPath) { Any() }
    fun hasData(): Boolean = logFile.exists() || exists(headFile) || exists(pendingFile)
    private fun exists(file: AtomicFile) = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists() || File(file.baseFile.path + ".new").exists()
    override fun <T> exclusive(action: () -> T): T = synchronized(monitor) {
        FileOutputStream(File(directory, "audit.lock"), true).use { out -> out.channel.lock().use { action() } }
    }
    private fun read(file: AtomicFile, cap: Int): ByteArray? {
        // An isolated .new was never published by AtomicFile. Ignore it; the CORE only
        // permits a new genesis when no committed log bytes/pending intent exist.
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().use { bounded(it, cap) }
    }
    private fun bounded(input: java.io.InputStream, cap: Int): ByteArray {
        val result = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
        while (true) { val n = input.read(buffer); if (n < 0) break; check(result.size() + n <= cap) { "Audit file too large" }; result.write(buffer, 0, n) }
        return result.toByteArray()
    }
    override fun log() = if (!logFile.exists()) byteArrayOf() else logFile.inputStream().use { bounded(it, 8 * 1024 * 1024) }
    override fun head() = read(headFile, 512)
    override fun pending() = read(pendingFile, 2048)
    override fun append(bytes: ByteArray) {
        FileOutputStream(logFile, true).use { out -> out.write(bytes); out.fd.sync() }
        syncDirectory()
    }
    private fun write(file: AtomicFile, bytes: ByteArray) {
        var out: FileOutputStream? = null
        try { out = file.startWrite(); out.write(bytes); out.fd.sync(); file.finishWrite(out); out = null; syncDirectory() }
        catch (error: Exception) { out?.let { file.failWrite(it) }; throw error }
    }
    override fun putHead(bytes: ByteArray) = write(headFile, bytes)
    override fun putPending(bytes: ByteArray) = write(pendingFile, bytes)
    override fun removePending() { pendingFile.delete(); check(!exists(pendingFile)) { "Audit pending cleanup failed" }; syncDirectory() }
    private fun syncDirectory() {
        val fd = android.system.Os.open(directory.absolutePath, android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
    }
    companion object { private val monitors = ConcurrentHashMap<String, Any>() }
}

class AndroidAuditChainMac(private val hasData: () -> Boolean, fixtureNamespace: String? = null) : AuditChainMac {
    init { require(fixtureNamespace == null || fixtureNamespace.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) }
    private val alias = "phonoteka.lab14.audit-chain.hmac.v1" + (fixtureNamespace?.let { ".fixture.$it" } ?: "")
    override fun sign(message: ByteArray): ByteArray {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (store.getKey(alias, null) as? SecretKey) ?: run {
            check(!hasData()) { "Audit key missing; existing journal must not be reset" }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(256).setDigests(KeyProperties.DIGEST_SHA256).build()); generator.generateKey()
        }
        return Mac.getInstance("HmacSHA256").run { init(key); doFinal(message) }
    }
}
