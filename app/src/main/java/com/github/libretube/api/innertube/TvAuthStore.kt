package com.github.libretube.api.innertube

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted on-disk storage for the Google account tokens obtained via TvOAuth.
 * Requires the androidx.security:security-crypto dependency (add to build.gradle.kts):
 *   implementation("androidx.security:security-crypto:1.1.0-alpha06")
 */
class TvAuthStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "innertube_tv_auth",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun save(token: TvOAuth.StoredToken) {
        prefs.edit()
            .putString("access_token", token.accessToken)
            .putString("refresh_token", token.refreshToken)
            .putLong("expires_at", token.expiresAtEpochMs)
            .apply()
    }

    fun load(): TvOAuth.StoredToken? {
        val access = prefs.getString("access_token", null) ?: return null
        val refresh = prefs.getString("refresh_token", null) ?: return null
        val expiresAt = prefs.getLong("expires_at", 0L)
        return TvOAuth.StoredToken(access, refresh, expiresAt)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun isLoggedIn(): Boolean = load() != null

    /** Returns a valid (non-expired) access token, refreshing it first if needed. */
    suspend fun getValidAccessToken(): String? {
        val stored = load() ?: return null
        return if (System.currentTimeMillis() < stored.expiresAtEpochMs - 60_000) {
            stored.accessToken
        } else {
            val refreshed = TvOAuth.refresh(stored.refreshToken)
            save(refreshed)
            refreshed.accessToken
        }
    }
}
