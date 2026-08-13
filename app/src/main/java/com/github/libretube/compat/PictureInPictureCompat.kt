package com.github.libretube.compat

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

object PictureInPictureCompat {

    private const val TAG = "PictureInPictureCompat"

    /**
     * Value of the framework's `ActivityInfo.FLAG_SUPPORTS_PICTURE_IN_PICTURE`, which backs
     * `android:supportsPictureInPicture` in the manifest. The constant itself is hidden from the
     * public SDK, but `ActivityInfo.flags` is public API and the bit has been stable since the
     * flag was introduced in API 24.
     */
    private const val FLAG_SUPPORTS_PICTURE_IN_PICTURE = 0x400000

    /**
     * Cache of per-activity PiP support, since resolving it requires a binder call to the
     * package manager and the player queries PiP support on every playback state change.
     */
    private val activitySupportCache = mutableMapOf<ComponentName, Boolean>()

    /**
     * Whether the device as a whole exposes picture-in-picture.
     */
    fun isPictureInPictureAvailable(context: Context) =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /**
     * Whether the *activity* is actually allowed to enter picture-in-picture.
     *
     * The device-wide system feature is not sufficient: the framework additionally rejects any
     * PiP call coming from an activity that does not declare `android:supportsPictureInPicture`,
     * throwing an [IllegalStateException] rather than failing gracefully. Activity aliases carry
     * their own copy of the flag, so this deliberately resolves the component the activity was
     * actually launched as.
     */
    private fun isPictureInPictureSupportedBy(activity: Activity): Boolean {
        if (!isPictureInPictureAvailable(activity)) return false

        val component = activity.componentName
        activitySupportCache[component]?.let { return it }

        val supported = runCatching {
            val info = activity.packageManager.getActivityInfo(component, 0)
            info.flags and FLAG_SUPPORTS_PICTURE_IN_PICTURE != 0
        }.getOrDefault(false)

        activitySupportCache[component] = supported
        return supported
    }

    fun isInPictureInPictureMode(activity: Activity) = activity.isInPictureInPictureMode

    fun setPictureInPictureParams(activity: Activity, params: PictureInPictureParamsCompat) {
        if (!isPictureInPictureSupportedBy(activity)) return

        // Some vendor and AOSP-derived ROMs advertise the PiP system feature but still reject the
        // call (e.g. while the activity is not resumed). PiP is a convenience, so degrade to
        // no-PiP instead of taking down playback.
        runCatching {
            activity.setPictureInPictureParams(params.toPictureInPictureParams())
        }.onFailure {
            Log.e(TAG, "Failed to set picture-in-picture params", it)
        }
    }

    fun enterPictureInPictureMode(activity: Activity, params: PictureInPictureParamsCompat) {
        if (!isPictureInPictureSupportedBy(activity)) return

        runCatching {
            activity.enterPictureInPictureMode(params.toPictureInPictureParams())
        }.onFailure {
            Log.e(TAG, "Failed to enter picture-in-picture mode", it)
        }
    }
}
