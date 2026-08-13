package com.github.libretube.api.innertube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Implements Google's OAuth 2.0 Device Authorization Grant (RFC 8628) using the
 * public "TV" client, which is the same mechanism the YouTube TV app / other
 * smart-TV YouTube clients (and tools like yt-dlp, SmartTube) use to let a user
 * sign in with their real Google account by visiting a URL on another device and
 * entering a short code, instead of embedding a WebView login.
 *
 * Flow:
 *   1. requestDeviceCode()  -> shows the user a URL + short code
 *   2. user opens the URL on their phone/PC and approves
 *   3. pollForToken()       -> call repeatedly at `interval` until it returns a token
 *   4. store the resulting AccessToken (access + refresh) securely
 *   5. refresh() when the access token expires
 */
object TvOAuth {

    // Public client id/secret used by the "TV and Limited Input device" OAuth
    // client. This is not a secret in the traditional sense -- it's baked into
    // every TV/limited-input YouTube client and is the standard way third-party
    // FOSS YouTube apps (yt-dlp, SmartTube, etc.) implement device-code sign-in.
    private const val CLIENT_ID = "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com"
    private const val CLIENT_SECRET = "SboVhoG9s0rNafixCSGGKXAT"

    private const val DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"

    // Scopes needed for subscriptions, likes, comments, personalized feed.
    private const val SCOPES = "https://www.googleapis.com/auth/youtube " +
        "https://www.googleapis.com/auth/youtube.force-ssl"

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class DeviceCodeResponse(
        @SerialName("device_code") val deviceCode: String,
        @SerialName("user_code") val userCode: String,
        @SerialName("verification_url") val verificationUrl: String,
        @SerialName("expires_in") val expiresIn: Int,
        @SerialName("interval") val interval: Int
    )

    @Serializable
    data class TokenResponse(
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Int? = null,
        @SerialName("token_type") val tokenType: String? = null,
        // present while still pending / on error
        val error: String? = null
    )

    data class StoredToken(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtEpochMs: Long
    )

    class AuthPendingException(val response: DeviceCodeResponse) : IOException()

    /** Step 1: kick off the flow. Show response.verificationUrl + response.userCode to the user. */
    suspend fun requestDeviceCode(): DeviceCodeResponse = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("scope", SCOPES)
            .build()

        val request = Request.Builder()
            .url(DEVICE_CODE_URL)
            .post(body)
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Device code request failed: ${resp.code}")
            json.decodeFromString(resp.body!!.string())
        }
    }

    /**
     * Step 2: poll this on a loop every `interval` seconds (from the device code
     * response) until it returns a StoredToken or throws. Returns null while the
     * user hasn't approved yet ("authorization_pending") -- keep polling.
     */
    suspend fun pollForToken(deviceCode: String): StoredToken? = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("client_secret", CLIENT_SECRET)
            .add("device_code", deviceCode)
            .add("grant_type", "http://oauth.net/grant_type/device/1.0")
            .build()

        val request = Request.Builder().url(TOKEN_URL).post(body).build()

        client.newCall(request).execute().use { resp ->
            val text = resp.body!!.string()
            val parsed = json.decodeFromString<TokenResponse>(text)

            when {
                parsed.accessToken != null && parsed.refreshToken != null -> StoredToken(
                    accessToken = parsed.accessToken,
                    refreshToken = parsed.refreshToken,
                    expiresAtEpochMs = System.currentTimeMillis() + (parsed.expiresIn ?: 3600) * 1000L
                )
                parsed.error == "authorization_pending" -> null
                parsed.error == "slow_down" -> null // caller should increase polling interval
                else -> throw IOException("OAuth error: ${parsed.error ?: "unknown (code ${resp.code})"}")
            }
        }
    }

    /** Refresh an expired access token using the stored refresh token. */
    suspend fun refresh(refreshToken: String): StoredToken = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("client_secret", CLIENT_SECRET)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()

        val request = Request.Builder().url(TOKEN_URL).post(body).build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Refresh failed: ${resp.code}")
            val parsed = json.decodeFromString<TokenResponse>(resp.body!!.string())
            StoredToken(
                accessToken = parsed.accessToken ?: throw IOException("No access_token in refresh response"),
                refreshToken = refreshToken, // Google doesn't rotate this for this grant type
                expiresAtEpochMs = System.currentTimeMillis() + (parsed.expiresIn ?: 3600) * 1000L
            )
        }
    }
}
