package com.phonoteka.ui.permissions
import org.junit.Assert.assertEquals
import org.junit.Test
class PermissionFlowPolicyTest {
    @Test fun firstRequestAndRetryExplainBeforeRequest() {
        assertEquals(PermissionNext.RATIONALE, PermissionFlowPolicy.next(false, false, false))
        assertEquals(PermissionNext.RATIONALE, PermissionFlowPolicy.next(false, true, true))
    }
    @Test fun repeatedDenialOffersSettingsAndGrantNeverReRequests() {
        assertEquals(PermissionNext.SETTINGS, PermissionFlowPolicy.next(false, true, false))
        assertEquals(PermissionNext.GRANTED, PermissionFlowPolicy.next(true, true, false))
    }
}
