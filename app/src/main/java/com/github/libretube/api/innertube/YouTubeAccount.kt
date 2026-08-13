package com.github.libretube.api.innertube

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Raised when an account action is attempted without a signed-in Google account.
 *
 * Callers are expected to catch this and point the user at the TV sign-in flow rather than
 * treating it as a generic network failure.
 */
class NotSignedInException : IOException("Not signed in to YouTube")

/**
 * Single entry point for the actions that operate on the signed-in Google account.
 *
 * Subscribing, rating and commenting all go through the user's real YouTube account rather than
 * the configured Piped instance, so they require a completed TV OAuth sign-in.
 */
object YouTubeAccount {

    enum class Rating { LIKE, DISLIKE, NONE }

    @Volatile
    private var authStore: TvAuthStore? = null

    /**
     * Building the store opens an encrypted preferences file backed by the keystore, which is
     * disk I/O, so it is created once and off the main thread.
     */
    private suspend fun authStore(context: Context): TvAuthStore = withContext(Dispatchers.IO) {
        authStore ?: synchronized(this@YouTubeAccount) {
            authStore ?: TvAuthStore(context.applicationContext).also { authStore = it }
        }
    }

    private suspend fun api(context: Context) = InnerTubeApi(authStore(context))

    suspend fun isSignedIn(context: Context): Boolean = authStore(context).isLoggedIn()

    /** Drops the cached store so a sign-in or sign-out is picked up immediately. */
    fun invalidate() {
        authStore = null
    }

    /** Personalized home feed for the signed-in account. */
    suspend fun getHomeFeed(context: Context) = api(context).getHomeFeed()

    suspend fun subscribe(context: Context, channelId: String) {
        api(context).subscribe(channelId)
    }

    suspend fun unsubscribe(context: Context, channelId: String) {
        api(context).unsubscribe(channelId)
    }

    suspend fun rate(context: Context, videoId: String, rating: Rating) {
        api(context).rateVideo(videoId, rating)
    }

    suspend fun postComment(context: Context, videoId: String, text: String) {
        api(context).postComment(videoId, text)
    }
}
