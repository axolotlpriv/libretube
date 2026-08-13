package com.github.libretube.api.innertube

import com.github.libretube.api.obj.StreamItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns an InnerTube "browse" (home feed) response into the app's StreamItem
 * model so it can be dropped straight into the existing VideoCardsAdapter used
 * throughout LibreTube.
 *
 * CAVEAT (see README): the TVHTML5 client's home feed nests content under
 * tvBrowseRenderer / sectionListRenderer / shelfRenderer / tileRenderer, which
 * differs from the web/mobile "richGridRenderer" shape. I have NOT been able
 * to verify the exact key names against a live response from this sandbox (no
 * network path to youtubei.googleapis.com here). This function tries both the
 * TV shape and the more commonly-documented web shape as a fallback, and
 * returns an empty list (never throws) if neither matches -- so a schema
 * mismatch just means an empty "Recommended" row instead of a crash. If it
 * comes back empty on your device, capture one real response and adjust the
 * key names below.
 */
object InnerTubeFeedParser {

    fun parseHomeFeed(response: JsonObject): List<StreamItem> {
        val renderers = findAllVideoRenderers(response)
        return renderers.mapNotNull { toStreamItem(it) }
    }

    /** Recursively walk the JSON tree collecting any videoRenderer/tileRenderer-like objects. */
    private fun findAllVideoRenderers(element: JsonObject): List<JsonObject> {
        val found = mutableListOf<JsonObject>()

        fun walk(obj: JsonObject) {
            for ((key, value) in obj) {
                when {
                    key in VIDEO_RENDERER_KEYS && value is JsonObject -> found.add(value)
                    value is JsonObject -> walk(value)
                    value is JsonArray -> value.forEach { child ->
                        if (child is JsonObject) walk(child)
                    }
                }
            }
        }
        walk(element)
        return found
    }

    private val VIDEO_RENDERER_KEYS = setOf(
        "videoRenderer",       // web/mobile shape
        "compactVideoRenderer",
        "tileRenderer",        // TV shape (wraps a videoInfo/metadata block)
        "gridVideoRenderer"
    )

    private fun toStreamItem(renderer: JsonObject): StreamItem? {
        val videoId = extractVideoId(renderer) ?: return null
        val title = extractText(renderer["title"]) ?: extractText(renderer["headline"])
        val thumbnail = extractThumbnail(renderer)
        val channelName = extractText(
            renderer["longBylineText"] ?: renderer["shortBylineText"] ?: renderer["ownerText"]
        )

        return StreamItem(
            url = "/watch?v=$videoId",
            title = title,
            thumbnail = thumbnail,
            uploaderName = channelName
        )
    }

    private fun extractVideoId(renderer: JsonObject): String? {
        renderer["videoId"]?.jsonPrimitive?.contentOrNull?.let { return it }
        // TV tileRenderer nests the command deeper:
        renderer["onSelectCommand"]?.jsonObject
            ?.get("watchEndpoint")?.jsonObject
            ?.get("videoId")?.jsonPrimitive?.contentOrNull?.let { return it }
        renderer["contentId"]?.jsonPrimitive?.contentOrNull?.let { return it }
        return null
    }

    private fun extractThumbnail(renderer: JsonObject): String? {
        val thumbs = (renderer["thumbnail"] ?: renderer["thumbnailRenderer"])
            ?.jsonObject?.get("thumbnails")?.jsonArray
            ?: return null
        return thumbs.lastOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
    }

    private fun extractText(element: kotlinx.serialization.json.JsonElement?): String? {
        if (element == null) return null
        val obj = element as? JsonObject ?: return null
        obj["simpleText"]?.jsonPrimitive?.contentOrNull?.let { return it }
        obj["runs"]?.jsonArray?.joinToString("") {
            it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }
}
