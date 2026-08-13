package com.github.libretube.api.innertube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Minimal client for YouTube's internal "InnerTube" API (the same private API
 * the real YouTube apps/website use), using the TVHTML5 client context so
 * requests look like they're coming from a TV app -- consistent with signing
 * in via the TV OAuth device flow above.
 *
 * This talks to real Google/YouTube servers with the user's own OAuth token,
 * i.e. every call acts on the signed-in user's real account. It is not scraping
 * or bypassing anything -- it's the same request shape YouTube's own TV client
 * sends, authenticated the same way (Bearer token from Google's own OAuth
 * server), for a user operating their own account.
 *
 * NOTE: this is a starting scaffold. InnerTube's exact JSON shapes shift over
 * time; if a call starts failing, compare against a current capture (e.g. via a
 * proxy on the official YouTube TV app) and adjust the request/response models.
 */
class InnerTubeApi(private val authStore: TvAuthStore) {

    private val client = OkHttpClient()
    private val mediaType = "application/json".toMediaType()

    // Public InnerTube API key used by the TVHTML5 web client. Like the OAuth
    // client id, this isn't a user secret -- it's shipped in every YouTube TV
    // page's client-side JS.
    private val apiKey = "AIzaSyDCU8hByM-4DrUqRUYnGC-YlIQ97bqZ9Bo"
    private val baseUrl = "https://youtubei.googleapis.com/youtubei/v1"

    private fun context() = buildJsonObject {
        putJsonObject("client") {
            put("clientName", "TVHTML5")
            put("clientVersion", "7.20250101.00.00")
            put("hl", "en")
            put("gl", "US")
        }
    }

    private suspend fun post(endpoint: String, extraBody: JsonObject): JsonObject =
        withContext(Dispatchers.IO) {
            // token loading hits encrypted prefs and may perform a refresh round-trip, so it has
            // to stay on the IO dispatcher along with the request itself
            val token = authStore.getValidAccessToken()
                ?: throw NotSignedInException()

            val bodyJson = buildJsonObject {
                put("context", context())
                extraBody.forEach { (k, v) -> put(k, v) }
            }

            val request = Request.Builder()
                .url("$baseUrl/$endpoint?key=$apiKey")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("X-Goog-Api-Format-Version", "2")
                .post(bodyJson.toString().toRequestBody(mediaType))
                .build()

            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IOException("InnerTube $endpoint failed: ${resp.code} $body")
                }
                Json.parseToJsonElement(body) as JsonObject
            }
        }

    /** Personalized home feed ("recommended videos") for the signed-in account. */
    suspend fun getHomeFeed(): JsonObject = post("browse", buildJsonObject {
        put("browseId", "FEwhat_to_watch")
    })

    /** "Up next" / related videos for a given video, personalized when signed in. */
    suspend fun getNext(videoId: String): JsonObject = post("next", buildJsonObject {
        put("videoId", videoId)
    })

    suspend fun subscribe(channelId: String): JsonObject = post("subscription/subscribe", buildJsonObject {
        put("channelIds", JsonArray(listOf(JsonPrimitive(channelId))))
    })

    suspend fun unsubscribe(channelId: String): JsonObject = post("subscription/unsubscribe", buildJsonObject {
        put("channelIds", JsonArray(listOf(JsonPrimitive(channelId))))
    })

    /**
     * Sets the signed-in user's rating for a video.
     *
     * Each rating is a distinct endpoint rather than a parameter; clearing a rating uses
     * `like/removelike` for both a previous like and a previous dislike.
     */
    suspend fun rateVideo(videoId: String, rating: YouTubeAccount.Rating): JsonObject {
        val endpoint = when (rating) {
            YouTubeAccount.Rating.LIKE -> "like/like"
            YouTubeAccount.Rating.DISLIKE -> "like/dislike"
            YouTubeAccount.Rating.NONE -> "like/removelike"
        }
        return post(endpoint, buildJsonObject {
            put("target", buildJsonObject { put("videoId", videoId) })
        })
    }

    /**
     * Posts a top-level comment on a video.
     *
     * Comment creation is not addressed by video ID: it needs an opaque `createCommentParams`
     * token that YouTube mints per video and per session. The token is carried by the comments
     * section of the video's `next` response, so it is resolved here immediately before posting
     * rather than being cached.
     */
    suspend fun postComment(videoId: String, text: String): JsonObject {
        val params = fetchCreateCommentParams(videoId)
            ?: throw IOException("Commenting is not available for this video")

        return post("comment/create_comment", buildJsonObject {
            put("commentText", text)
            put("createCommentParams", params)
        })
    }

    /**
     * Digs the `createCommentParams` token out of a video's `next` response.
     *
     * The token's exact position in the response has moved between InnerTube revisions, so rather
     * than pinning a fragile path this walks the tree for the first occurrence of the key. Returns
     * null when the video has comments disabled or the account cannot comment on it.
     */
    private suspend fun fetchCreateCommentParams(videoId: String): String? {
        val next = runCatching { getNext(videoId) }.getOrNull() ?: return null
        return findStringByKey(next, "createCommentParams")
    }

    private fun findStringByKey(element: JsonElement, key: String): String? = when (element) {
        is JsonObject -> element[key]?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: element.values.firstNotNullOfOrNull { findStringByKey(it, key) }

        is JsonArray -> element.firstNotNullOfOrNull { findStringByKey(it, key) }
        else -> null
    }
}
