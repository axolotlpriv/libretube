package com.github.libretube.api.innertube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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

    private suspend fun post(endpoint: String, extraBody: JsonObject): JsonObject {
        val token = authStore.getValidAccessToken()
            ?: throw IOException("Not signed in")

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
            if (!resp.isSuccessful) {
                throw IOException("InnerTube $endpoint failed: ${resp.code} ${resp.body?.string()}")
            }
            return kotlinx.serialization.json.Json.parseToJsonElement(resp.body!!.string()).let {
                it as JsonObject
            }
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

    /** Like a video. Send "like" again to remove, or use rate("LIKE"/"DISLIKE"/"INDIFFERENT"). */
    suspend fun rateVideo(videoId: String, rating: String): JsonObject =
        post("like/${rating.lowercase()}", buildJsonObject {
            put("target", buildJsonObject { put("videoId", videoId) })
        })

    /**
     * Post a top-level comment on a video.
     *
     * UNVERIFIED: real InnerTube comment creation requires an opaque
     * "createCommentParams" protobuf token that's normally scraped out of the
     * page/response that preceded it (e.g. the comments panel's continuation
     * token), not just the raw video ID. Passing the video ID directly here
     * will very likely fail against the real API. This compiles and won't crash
     * the app, but treat it as a placeholder to fix once you've captured a real
     * comment-creation request (e.g. via mitmproxy against the YouTube TV app).
     */
    suspend fun postComment(videoId: String, createCommentParams: String, text: String): JsonObject =
        post("comment/create_comment", buildJsonObject {
            put("commentText", text)
            put("createCommentParams", createCommentParams)
        })
}
