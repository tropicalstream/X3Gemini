package com.x3gemini.app.core.tools

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gemini-callable camera tool. `save_photo` captures the most recent
 * camera frame (base64 JPEG via [frameProvider], populated by the
 * Gemini Live multimodal pipeline) and writes it to shared storage
 * under `DCIM/X3Gemini/` via MediaStore — photos appear in the RayNeo
 * native gallery and over USB/MTP, no write permission needed on
 * Android 10+ (minSdk is 29/Q, so the MediaStore path always applies).
 */
class CameraTool(
    private val context: Context,
    private val frameProvider: () -> String? = { null }
) : AiTapTool {

    override val name = "camera_action"

    companion object {
        private const val TAG = "CameraTool"
        private val FILENAME_TIMESTAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        const val DCIM_SUBFOLDER = "X3Gemini"
    }

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val action = args["action"] ?: "save_photo"
        val title = args["title"]?.trim()?.takeIf { it.isNotBlank() } ?: "X3Gemini Photo"
        Log.d(TAG, "action=$action title=$title")
        return when (action) {
            "save_photo" -> savePhoto(title)
            else -> Result.success("Unknown camera action: $action")
        }
    }

    private fun savePhoto(title: String): Result<String> {
        val base64 = frameProvider()?.trim().orEmpty()
        if (base64.isEmpty()) {
            Log.w(TAG, "save_photo: no camera frame available")
            return Result.failure(
                IllegalStateException(
                    "Camera frame not available. The camera must be on — " +
                        "the user can double-tap the left temple arm to start it."
                )
            )
        }

        val jpegBytes = try {
            Base64.decode(base64, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "save_photo: base64 decode failed", e)
            return Result.failure(IOException("Failed to decode camera frame: ${e.localizedMessage}"))
        }
        if (jpegBytes.isEmpty()) {
            return Result.failure(IOException("Camera frame decoded to 0 bytes."))
        }

        val timestamp = FILENAME_TIMESTAMP.format(Date())
        val safeTitle = title.replace(Regex("[^A-Za-z0-9 _.-]"), "").trim()
            .replace(Regex("\\s+"), "_")
            .ifBlank { "photo" }
        val filename = "$timestamp-$safeTitle.jpg"

        val saved = saveToDcimViaMediaStore(jpegBytes, filename)
            ?: return Result.failure(IOException("Could not write the photo to storage."))
        Log.i(TAG, "save_photo: wrote ${jpegBytes.size}B to DCIM/$DCIM_SUBFOLDER/$filename (id=${saved.id})")
        return Result.success(
            "Photo saved as $filename in the glasses gallery (DCIM/$DCIM_SUBFOLDER)."
        )
    }

    private data class DcimSave(val id: Long, val uri: Uri)

    /**
     * Insert the JPEG into MediaStore under `DCIM/X3Gemini/`. IS_PENDING
     * gates the half-written file from other gallery apps.
     */
    private fun saveToDcimViaMediaStore(jpegBytes: ByteArray, filename: String): DcimSave? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/$DCIM_SUBFOLDER/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = try {
            resolver.insert(collection, values)
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore insert failed: ${e.message}")
            null
        } ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { it.write(jpegBytes) }
                ?: throw IOException("openOutputStream returned null")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            DcimSave(ContentUris.parseId(uri), uri)
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore write failed; rolling back: ${e.message}")
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            null
        }
    }
}
