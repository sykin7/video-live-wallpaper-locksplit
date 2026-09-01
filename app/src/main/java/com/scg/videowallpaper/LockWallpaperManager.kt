package com.scg.videowallpaper

import android.app.WallpaperManager
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
                error(context.getString(R.string.lock_error_unsupported))
            }

            // Some OEM ROMs accept the FLAG_LOCK call but silently ignore
            // it; comparing the lock wallpaper id before and after the call
            // detects that instead of reporting a false success.
            val lockIdBefore = manager.getWallpaperId(WallpaperManager.FLAG_LOCK)

            // desiredMinimumWidth/Height report 0 on many devices; using
            // them alone collapsed the decode target to 1x1 and produced a
            // near-invisible lock wallpaper. Always prefer the real screen
            // size as the decode target.
            val metrics = Resources.getSystem().displayMetrics
            val bitmap = decodeScaled(
                context,
                uri,
                targetWidth = maxOf(manager.desiredMinimumWidth, metrics.widthPixels).coerceAtLeast(1),
                targetHeight = maxOf(manager.desiredMinimumHeight, metrics.heightPixels).coerceAtLeast(1)
            ) ?: error(context.getString(R.string.lock_error_load))

            Log.i(TAG, "Lock bitmap decoded at ${bitmap.width}x${bitmap.height}")

            // FLAG_LOCK targets the lock screen only; FLAG_SYSTEM is omitted so
            // the video live wallpaper keeps rendering on the home screen.
            try {
                manager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
            } catch (e: IOException) {
                Log.e(TAG, "Device does not support separate lock wallpaper", e)
                error(context.getString(R.string.lock_error_unsupported))
            } finally {
                recycle(bitmap)
            }

            val lockIdAfter = manager.getWallpaperId(WallpaperManager.FLAG_LOCK)
            if (lockIdAfter == lockIdBefore) {
                Log.w(TAG, "System ignored FLAG_LOCK change, id stayed $lockIdBefore")
                error(context.getString(R.string.lock_error_ignored))
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

    fun decodeScaled(
        context: Context,
        uri: Uri,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateSampleSize(bounds, targetWidth, targetHeight)
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to decode bitmap from uri $uri", e)
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "No read permission for uri $uri", e)
            null
        }
    }

    private fun calculateSampleSize(
        bounds: BitmapFactory.Options,
        targetWidth: Int,
        targetHeight: Int
    ): Int {
        var sample = 1
        var width = bounds.outWidth
        var height = bounds.outHeight
        while (width / 2 >= targetWidth && height / 2 >= targetHeight) {
            sample *= 2
            width /= 2
            height /= 2
        }
        return sample
    }

    private fun recycle(bitmap: Bitmap) {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}
