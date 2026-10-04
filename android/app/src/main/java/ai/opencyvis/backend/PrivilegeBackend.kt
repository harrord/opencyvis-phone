package ai.opencyvis.backend

import android.os.Bundle
import android.view.InputEvent
import android.view.Surface

interface PrivilegeBackend {
    val capabilities: BackendCapabilities

    fun injectInputEvent(event: InputEvent, displayId: Int, mode: Int): Boolean
    fun injectKeyEvent(action: Int, keyCode: Int, repeat: Int, metaState: Int, displayId: Int, mode: Int): Boolean
    fun captureScreen(displayId: Int, maxWidth: Int = 540, quality: Int = 85): ByteArray?
    fun createVirtualDisplay(name: String, width: Int, height: Int, dpi: Int, flags: Int): Int
    fun releaseVirtualDisplay()
    fun setVirtualDisplaySurface(surface: Surface?)
    fun setDisplayImePolicy(displayId: Int, policy: Int)
    fun startActivityOnDisplay(intentUri: String, displayId: Int): Boolean
    fun ensureVdHasContent(displayId: Int)
    fun getTopTaskIdOnDisplay(displayId: Int, callerPackage: String): Int
    fun moveTaskToDisplay(taskId: Int, targetDisplayId: Int): Boolean

    /**
     * Read a plain-text file from allowlisted shared storage at this backend's uid
     * (shell/system), exempt from Scoped Storage media-only restrictions.
     * Never throws; failures are reported via the returned Bundle's "error" key.
     */
    fun readTextFile(path: String, maxBytes: Int): Bundle

    fun destroy()
}

data class BackendCapabilities(
    val name: String,
    val canInjectInput: Boolean,
    val canCaptureScreen: Boolean,
    val canCaptureSecure: Boolean,
    val canCreateVirtualDisplay: Boolean,
) {
    companion object {
        const val CAP_INJECT_INPUT = 1
        const val CAP_CAPTURE_SCREEN = 2
        const val CAP_CAPTURE_SECURE = 4
        const val CAP_CREATE_VD = 8

        fun fromProbeMask(mask: Int, name: String) = BackendCapabilities(
            name = name,
            canInjectInput = mask and CAP_INJECT_INPUT != 0,
            canCaptureScreen = mask and CAP_CAPTURE_SCREEN != 0,
            canCaptureSecure = mask and CAP_CAPTURE_SECURE != 0,
            canCreateVirtualDisplay = mask and CAP_CREATE_VD != 0,
        )

        val SYSTEM = BackendCapabilities("system", true, true, true, true)
        val NONE = BackendCapabilities("none", false, false, false, false)
    }
}
