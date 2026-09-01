package com.scg.videowallpaper

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

/**
 * Renders the user's chosen video as a looping live wallpaper.
 *
 * Android creates a new Engine instance per surface that needs the wallpaper
 * (home screen, lock screen, or both — depending on OEM/launcher behavior),
 * so each Engine owns its own MediaPlayer to avoid cross-talk between them.
 */
class VideoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = VideoEngine()

    inner class VideoEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private var mediaPlayer: MediaPlayer? = null
        private var prefs: SharedPreferences? = null
        private var visible = false

        // Counts consecutive prepare attempts that ended in a "transient"
        // error in a row, so a genuinely broken source (not just a one-off
        // state-machine hiccup) can't retry forever and spin the CPU.
        private var consecutiveErrorRetries = 0

        // Tracks whether *we* believe the player is currently started, kept
        // independently of MediaPlayer.isPlaying(). On some OEM MediaPlayer
        // implementations (observed here) isPlaying() can lag behind the
        // actual native state transition by a short window, so relying on it
        // alone to guard against a duplicate start() call isn't reliable —
        // two onVisibilityChanged(true) calls landing close together (a known
        // quirk during unlock animations on some launchers) could both see
        // isPlaying()==false and both call start(), producing error -38.
        private var weBelieveStarted = false

        // Kept so the pref listener can trigger a re-prepare with the
        // correct surface without waiting for the next onSurfaceCreated.
        private var currentHolder: SurfaceHolder? = null

        // While the keyguard is showing, the engine paints a still frame
        // (the user's chosen lock image, or black) instead of video frames.
        // This forces the lock/home split at render level: on ROMs whose
        // lock screen mirrors the live wallpaper, the lock simply shows the
        // still frame this engine paints, no matter what the wallpaper
        // settings claim.
        private var keyguardLocked = false
        private var lockFrameBitmap: Bitmap? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        private val keyguardManager
            get() = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

        private val keyguardReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                refreshKeyguardState()
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            prefs = PreferenceManager.getDefaultSharedPreferences(this@VideoWallpaperService)
            prefs?.registerOnSharedPreferenceChangeListener(this)
            keyguardLocked = keyguardManager?.isKeyguardLocked ?: false
            ContextCompat.registerReceiver(
                this@VideoWallpaperService,
                keyguardReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            loadLockFrameAsync()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            currentHolder = holder
            consecutiveErrorRetries = 0
            preparePlayer(holder)
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            syncPlayback()
        }

        private fun refreshKeyguardState() {
            val locked = keyguardManager?.isKeyguardLocked ?: false
            if (locked == keyguardLocked) return
            keyguardLocked = locked
            syncPlayback()
        }

        private fun syncPlayback() {
            val player = mediaPlayer
            if (player == null) {
                if (keyguardLocked) drawLockFrameIfLocked()
                return
            }
            // Route through applySpeed rather than calling start()/pause()
            // directly here. Having two independent call sites (this one and
            // the one inside preparePlayer's onPreparedListener) both touching
            // start()/pause() on the same MediaPlayer was racy — if a visibility
            // change and a prepare-completion landed close together, they could
            // stack an extra start() on top of one that was still resolving,
            // which some OEM MediaPlayer implementations (observed on
            // Adreno/TCL) turn into error -38. Funneling everything through
            // applySpeed keeps state transitions to one guarded path.
            val speed = prefs?.getFloat(PREF_PLAYBACK_SPEED, DEFAULT_SPEED) ?: DEFAULT_SPEED
            applySpeed(player, speed)
            if (keyguardLocked) drawLockFrameIfLocked()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            currentHolder = holder
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            currentHolder = null
            releasePlayer()
        }

        override fun onDestroy() {
            super.onDestroy()
            runCatching { this@VideoWallpaperService.unregisterReceiver(keyguardReceiver) }
            prefs?.unregisterOnSharedPreferenceChangeListener(this)
            releasePlayer()
        }

        /**
         * Called whenever any SharedPreference changes — including writes from
         * MainActivity when the user adjusts speed, crop mode, or picks a new video.
         * Speed is applied cheaply to the running player; everything else needs a
         * full re-prepare since MediaPlayer doesn't support hot-swapping video source
         * or scaling mode mid-playback.
         */
        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            val holder = currentHolder ?: return
            when (key) {
                PREF_PLAYBACK_SPEED -> {
                    // Speed can be applied to the running player without re-preparing.
                    val speed = sharedPreferences?.getFloat(PREF_PLAYBACK_SPEED, DEFAULT_SPEED)
                        ?: DEFAULT_SPEED
                    mediaPlayer?.let { applySpeed(it, speed) }
                }
                PREF_SCALING_MODE, PREF_VIDEO_URI -> {
                    // Scaling mode and video URI both require a full re-prepare.
                    preparePlayer(holder)
                }
                PREF_LOCK_IMAGE_URI -> {
                    loadLockFrameAsync()
                }
            }
        }

        private fun loadLockFrameAsync() {
            val uriString = prefs?.getString(PREF_LOCK_IMAGE_URI, null)
            if (uriString.isNullOrBlank()) {
                lockFrameBitmap = null
                drawLockFrameIfLocked()
                return
            }
            val uri = try {
                Uri.parse(uriString)
            } catch (e: Exception) {
                Log.w(TAG, "Stored lock image URI was malformed", e)
                lockFrameBitmap = null
                return
            }
            Thread {
                val metrics = Resources.getSystem().displayMetrics
                val bitmap = LockWallpaperManager.decodeScaled(
                    this@VideoWallpaperService,
                    uri,
                    metrics.widthPixels.coerceAtLeast(1),
                    metrics.heightPixels.coerceAtLeast(1)
                )
                mainHandler.post {
                    lockFrameBitmap = bitmap
                    drawLockFrameIfLocked()
                }
            }.start()
        }

        private fun drawLockFrameIfLocked() {
            if (!keyguardLocked || !visible) return
            val holder = currentHolder ?: return
            val canvas = try {
                holder.lockCanvas()
            } catch (e: Exception) {
                Log.w(TAG, "Could not lock canvas for lock frame", e)
                null
            } ?: return
            try {
                val bitmap = lockFrameBitmap
                if (bitmap != null) {
                    canvas.drawColor(Color.BLACK)
                    val scale = maxOf(
                        canvas.width.toFloat() / bitmap.width,
                        canvas.height.toFloat() / bitmap.height
                    )
                    val width = bitmap.width * scale
                    val height = bitmap.height * scale
                    val left = (canvas.width - width) / 2f
                    val top = (canvas.height - height) / 2f
                    canvas.drawBitmap(bitmap, null, RectF(left, top, left + width, top + height), null)
                } else {
                    canvas.drawColor(Color.BLACK)
                }
            } finally {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not post lock frame", e)
                }
            }
        }

        private fun preparePlayer(holder: SurfaceHolder) {
            releasePlayer()

            val uriString = prefs?.getString(PREF_VIDEO_URI, null)
            if (uriString.isNullOrBlank()) return
            val uri = try {
                Uri.parse(uriString)
            } catch (e: Exception) {
                Log.e(TAG, "Stored video URI was malformed", e)
                return
            }
            val scalingMode = prefs?.getInt(PREF_SCALING_MODE, DEFAULT_SCALING_MODE) ?: DEFAULT_SCALING_MODE

            // Confirm we still hold read permission before touching MediaPlayer.
            // Permission can be revoked out from under us at any time (user
            // action in system settings, storage change, etc.), and without
            // this check a revoked URI fails silently deep inside setDataSource
            // with no way for the user to tell why their wallpaper went blank.
            val stillGranted = try {
                applicationContext.contentResolver.persistedUriPermissions.any {
                    it.uri == uri && it.isReadPermission
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check persisted URI permissions", e)
                false
            }
            if (!stillGranted) {
                Log.w(TAG, "Lost read permission for stored video URI — clearing selection")
                prefs?.edit()?.remove(PREF_VIDEO_URI)?.apply()
                return
            }

            try {
                val player = MediaPlayer()
                player.setDataSource(applicationContext, uri)
                player.setSurface(holder.surface)
                player.isLooping = true
                player.setVolume(0f, 0f) // wallpapers should be silent
                player.setVideoScalingMode(scalingMode)
                player.setOnPreparedListener {
                    consecutiveErrorRetries = 0
                    syncPlayback()
                }
                player.setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    // The player is unusable after any error — it's stuck in
                    // MEDIA_PLAYER_STATE_ERROR and further start()/pause() calls
                    // against it (e.g. from a later onVisibilityChanged) will just
                    // keep re-triggering the same error in a loop. Release it and
                    // clear the reference immediately so nothing else can touch
                    // this broken instance; only preparePlayer() (triggered here
                    // for the source-error case, or by the next onSurfaceCreated/
                    // pref change) creates a new, working one.
                    releasePlayer()
                    // Not every MediaPlayer error means the file itself is bad —
                    // state-machine hiccups (e.g. what=-38, MEDIA_ERROR_UNSUPPORTED
                    // from an out-of-order start/pause call) are transient and a
                    // fresh preparePlayer() call will recover fine. Only clear the
                    // stored selection for errors that mean the source itself is
                    // unusable, so we don't wipe a perfectly good video over a
                    // one-off playback glitch.
                    if (what == MediaPlayer.MEDIA_ERROR_UNKNOWN || what == -38) {
                        consecutiveErrorRetries++
                        if (consecutiveErrorRetries <= MAX_CONSECUTIVE_ERROR_RETRIES) {
                            Log.w(TAG, "Transient MediaPlayer error — retrying prepare " +
                                "($consecutiveErrorRetries/$MAX_CONSECUTIVE_ERROR_RETRIES), selection kept")
                            currentHolder?.let { preparePlayer(it) }
                        } else {
                            Log.e(TAG, "Gave up after $MAX_CONSECUTIVE_ERROR_RETRIES consecutive " +
                                "errors — leaving selection intact but not retrying further")
                        }
                    } else {
                        prefs?.edit()?.remove(PREF_VIDEO_URI)?.apply()
                    }
                    true
                }
                player.prepareAsync()
                mediaPlayer = player
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare video wallpaper", e)
                prefs?.edit()?.remove(PREF_VIDEO_URI)?.apply()
            }
        }

        /**
         * Single guarded entry point for all player state transitions
         * (start/pause) and speed changes. Called from onPreparedListener,
         * onVisibilityChanged, and onSharedPreferenceChanged (speed changes)
         * — must be safe to call repeatedly and in any order, since any two
         * of these can land close together. Guards against duplicate start()
         * calls using our own weBelieveStarted flag rather than
         * MediaPlayer.isPlaying(), since isPlaying() was observed to lag
         * behind the actual native state transition on this device closely
         * enough for two back-to-back calls to both see it as false.
         */
        private fun applySpeed(player: MediaPlayer, speed: Float) {
            try {
                if (visible && !keyguardLocked) {
                    if (!weBelieveStarted) {
                        player.start()
                        weBelieveStarted = true
                    }
                    if (speed != 1.0f) {
                        player.playbackParams = PlaybackParams().setSpeed(speed)
                    }
                } else {
                    // PlaybackParams can only be set on a running player on some
                    // OEM implementations, so briefly start, apply, then pause
                    // if we're not actually meant to be playing yet (hidden or
                    // behind the keyguard).
                    if (speed != 1.0f && !weBelieveStarted) {
                        player.start()
                        weBelieveStarted = true
                        player.playbackParams = PlaybackParams().setSpeed(speed)
                    }
                    if (weBelieveStarted) {
                        player.pause()
                        weBelieveStarted = false
                    }
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Could not apply playback speed on this device", e)
            }
        }

        private fun releasePlayer() {
            mediaPlayer?.let {
                try {
                    if (it.isPlaying) it.stop()
                } catch (e: IllegalStateException) {
                    // already stopped/released — safe to ignore
                }
                it.release()
            }
            mediaPlayer = null
            weBelieveStarted = false
        }
    }

    companion object {
        private const val TAG = "VideoWallpaperService"
        private const val MAX_CONSECUTIVE_ERROR_RETRIES = 3
        const val PREF_VIDEO_URI = "selected_video_uri"
        const val PREF_PLAYBACK_SPEED = "playback_speed"
        const val PREF_SCALING_MODE = "scaling_mode"
        const val PREF_LOCK_IMAGE_URI = "lock_image_uri"
        const val DEFAULT_SPEED = 1.0f
        const val DEFAULT_SCALING_MODE = MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
    }
}
