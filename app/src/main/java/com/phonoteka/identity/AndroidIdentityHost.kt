package com.phonoteka.identity

import android.content.Context

/** Owns storage and transport together. Create/start/close on IO; invoke the local-owner gate on every control action. */
class AndroidIdentityHost private constructor(
    private val store: AndroidIdentitySnapshotStore,
    private val authority: PhoneIdentityAuthority,
    initiallyProvisioned: Boolean,
    private val requireLocalOwner: () -> Unit
) : AutoCloseable {
    private var provisioned = initiallyProvisioned
    private var closed = false
    private var server: IdentityHostTls? = null
    @get:Synchronized val needsOwner: Boolean get() { check(!closed); return !provisioned }

    @Synchronized fun provisionOwner(login: String, password: String) {
        check(!closed)
        requireLocalOwner()
        check(!provisioned) { "Identity owner already exists" }
        authority.bootstrapOwner(login, password)
        provisioned = true
    }

    @Synchronized fun start(host: String): IdentityTlsEndpoint {
        check(!closed && provisioned) { "Provision the local owner before starting identity service" }
        requireLocalOwner()
        check(server == null) { "Identity server is already running" }
        return IdentityHostTls.start(authority, host, allowRequest = { runCatching { requireLocalOwner() }.isSuccess })
            .also { server = it }.endpoint
    }

    @Synchronized fun stop() {
        check(!closed)
        requireLocalOwner()
        server?.close()
        server = null
    }

    /** Lifecycle teardown must work even after the owner's UI session has expired. */
    @Synchronized override fun close() {
        if (closed) return
        server?.close()
        server = null
        store.close()
        closed = true
    }

    companion object {
        fun open(context: Context, requireLocalOwner: () -> Unit,
                 namespace: String = "host", audit: (IdentityEvent) -> Unit = {}): AndroidIdentityHost {
            requireLocalOwner()
            val store = AndroidIdentitySnapshotStore(context, namespace)
            try {
                val provisioned = store.load() != null
                val authority = PhoneIdentityAuthority(store, audit = audit)
                return AndroidIdentityHost(store, authority, provisioned, requireLocalOwner)
            } catch (failure: Exception) { store.close(); throw failure }
        }
    }
}
