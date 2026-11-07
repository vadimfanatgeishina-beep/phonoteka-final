package com.phonoteka.ui.permissions
enum class PermissionNext { GRANTED, RATIONALE, SETTINGS }
object PermissionFlowPolicy {
    fun next(granted: Boolean, askedBefore: Boolean, shouldShowRationale: Boolean): PermissionNext =
        if (granted) PermissionNext.GRANTED
        else if (!askedBefore || shouldShowRationale) PermissionNext.RATIONALE else PermissionNext.SETTINGS
}
