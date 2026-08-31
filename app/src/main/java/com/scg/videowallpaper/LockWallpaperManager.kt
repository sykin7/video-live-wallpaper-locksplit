package com.scg.videowallpaper

import android.app.WallpaperManager
import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.IOException

/**
 * Applies a user-picked static image to the lock screen only, leaving the
 * video live wallpaper on the home screen. Android lets third-party apps set
 * the lock-screen wallpaper separately (`FLAG_LOCK`), which is exactly the
 * split the main activity needs to support.
 *
 * This is intentionally a small, synchronous helper. Callers run it off the
 * main thread because `setBitmap` can do a fair amount of file/scale work on
 * some OEMs, and decode + set should not jank the UI.
 */
object LockWallpaperManager {

    private const val TAG = "LockWallpaperManager"

    /**
     * Sets [uri] as the lock-screen wallpaper. The image is decoded and
     * scaled to the display size first so it can be handed to
     * [WallpaperManager.setBitmap] without blowing up on large photos.
     */
    fun apply(context: Context, uri: Uri): Result<Unit> {
        return runCatching {
            val manager = WallpaperManager.getInstance(context)
            if (!manager.isWallpaperSupported) {
                error("Wallpaper is not supported on this device")
            }

            val bitmap = ImageLoader.decodeScaled(
                context,
                uri,
                targetWidth = manager.desiredMinimumWidth.coerceAtLeast(1),
                targetHeight = manager.desiredMinimumHeight.coerceAtLeast(1)
            ) ?: error("Failed to load lock screen image")

            // FLAG_LOCK targets the lock screen only; FLAG_SYSTEM is omitted so
            // the video live wallpaper keeps rendering on the home screen.
            try {
                manager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
            } catch (e: IOException) {
                Log.e(TAG, "Device does not support separate lock wallpaper", e)
                error("This device does not support a separate lock screen wallpaper")
            } finally {
                bitmap.recycle()
            }
        }
    }

    /**
     * Clears the lock-screen wallpaper. On most devices this falls back to
     * the home-screen wallpaper, which is the desired "native static" state.
     */
    fun clear(context: Context): Result<Unit> {
        return runCatching {
            val manager = WallpaperManager.getInstance(context)
            if (manager.isWallpaperSupported) {
                try {
                    manager.clear(WallpaperManager.FLAG_LOCK)
                } catch (e: IOException) {
                    Log.w(TAG, "Could not clear lock wallpaper", e)
                }
            }
        }
    }
}
