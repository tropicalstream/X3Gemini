package com.x3gemini.app.core.bridge

/**
 * Binder API surface for the voice foreground Service. Single-module
 * in X3Gemini, but the binder-interface shape is kept from TapInsight:
 * MainActivity binds the Service and casts the returned IBinder to
 * this interface. Same process — plain in-process calls, no AIDL.
 */
interface VoiceServiceApi {

    /** Begin a Gemini Live voice session. Idempotent. */
    fun activateVoice()

    /** End the current voice session. Idempotent. */
    fun shutdownVoice()

    /** Snapshot of the HUD state the Service has published. */
    fun currentState(): HudStateBridge.State

    /**
     * Toggle CameraX streaming. When ON, frames stream into the active
     * Gemini Live session (and the preview frame lights up). Idempotent;
     * state mirrored via [CameraStateBridge].
     */
    fun toggleCamera()

    /** True when CameraX is currently streaming. */
    fun isCameraOn(): Boolean

    /**
     * Install a Preview.SurfaceProvider (PreviewView.surfaceProvider) so
     * the next camera activation binds a Preview use case. Pass null to
     * clear. Safe to call before [toggleCamera].
     */
    fun setCameraPreviewSurfaceProvider(provider: androidx.camera.core.Preview.SurfaceProvider?)

    companion object {
        /** FQN used with Intent.setClassName for the bindService call. */
        const val SERVICE_FQN: String =
            "com.x3gemini.app.core.session.GeminiSessionForegroundService"
    }
}
