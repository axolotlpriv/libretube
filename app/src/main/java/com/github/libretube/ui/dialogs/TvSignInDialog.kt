package com.github.libretube.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.github.libretube.api.innertube.TvAuthStore
import com.github.libretube.api.innertube.TvOAuth
import com.github.libretube.api.innertube.YouTubeAccount
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shows "Go to youtube.com/activate and enter XXX-XXX-XXX", then polls in the
 * background until the user approves on another device, mirroring how
 * SmartTube / the real YouTube TV app present sign-in.
 */
class TvSignInDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(requireContext())
            .setTitle("Sign in")
            .setMessage("Requesting code…")
            .setNegativeButton("Cancel", null)

        val dialog = builder.create()

        lifecycleScope.launch {
            try {
                val deviceCode = TvOAuth.requestDeviceCode()
                dialog.setMessage(
                    "Go to ${deviceCode.verificationUrl} on your phone or " +
                        "computer and enter this code:\n\n${deviceCode.userCode}"
                )

                var intervalSec = deviceCode.interval.coerceAtLeast(5)
                val deadline = System.currentTimeMillis() + deviceCode.expiresIn * 1000L

                while (System.currentTimeMillis() < deadline) {
                    delay(intervalSec * 1000L)
                    val token = try {
                        TvOAuth.pollForToken(deviceCode.deviceCode)
                    } catch (e: Exception) {
                        dialog.setMessage("Sign-in failed: ${e.message}")
                        return@launch
                    }
                    if (token != null) {
                        TvAuthStore(requireContext()).save(token)
                        // drop the cached store so account actions see the new token at once
                        YouTubeAccount.invalidate()
                        dialog.setMessage("Signed in!")
                        delay(1000)
                        dismiss()
                        return@launch
                    }
                }
                dialog.setMessage("Code expired, try again.")
            } catch (e: Exception) {
                dialog.setMessage("Failed to start sign-in: ${e.message}")
            }
        }

        return dialog
    }
}
