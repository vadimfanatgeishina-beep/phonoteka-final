package com.phonoteka.security

import com.phonoteka.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeSecurityPolicyTest {
    @Test fun runtimeControlDefaultsMatchTheSelectedBuildFlavor() {
        val expected = BuildConfig.SECURITY_MODE == "HARDENED"
        assertEquals(expected, SecurityProfile.enforceOtpSingleUse)
        assertEquals(expected, SecurityProfile.enforceQrTicketSingleUse)
        assertEquals(expected, SecurityProfile.enforceStepUp)
        assertEquals(expected, SecurityProfile.requireFreshAuthForLibrary)
    }

    @Test fun hardeningControlsAreAvailableOnlyInVulnerableDebugBuild() {
        assertEquals(BuildConfig.DEBUG && BuildConfig.SECURITY_MODE == "VULNERABLE",
            SecurityProfile.canEditRuntimeControls())
    }
}
