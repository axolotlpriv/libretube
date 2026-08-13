package com.github.libretube.ui.views

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.text.util.Linkify
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.text.method.LinkMovementMethodCompat
import androidx.core.text.parseAsHtml
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.github.libretube.R
import com.github.libretube.api.SponsorBlockLabelHelper
import com.github.libretube.api.innertube.NotSignedInException
import com.github.libretube.api.innertube.YouTubeAccount
import com.github.libretube.api.obj.Segment
import com.github.libretube.api.obj.Streams
import com.github.libretube.databinding.DescriptionLayoutBinding
import com.github.libretube.enums.SbSkipOptions
import com.github.libretube.extensions.formatShort
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.helpers.ThemeHelper
import kotlinx.coroutines.launch
import com.github.libretube.helpers.ClipboardHelper
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.ui.activities.VideoTagsAdapter
import com.github.libretube.util.HtmlParser
import com.github.libretube.util.LinkHandler
import com.github.libretube.util.TextUtils
import java.util.Locale

class DescriptionLayout(
    context: Context,
    attributeSet: AttributeSet?
) : LinearLayout(context, attributeSet) {
    val binding = DescriptionLayoutBinding.inflate(LayoutInflater.from(context), this, true)
    private var streams: Streams? = null
    var handleLink: (link: String) -> Unit = {}

    private val videoTagsAdapter = VideoTagsAdapter()

    private var videoId: String? = null
    private var currentRating = YouTubeAccount.Rating.NONE

    /** Tint of an inactive rating icon, captured before any rating is applied. */
    private val inactiveRatingTint = binding.textLike.currentTextColor
    private val activeRatingTint by lazy {
        ThemeHelper.getThemeColor(context, androidx.appcompat.R.attr.colorPrimary)
    }

    init {
        binding.playerTitleLayout.setOnClickListener {
            toggleDescription()
        }
        binding.playerTitleLayout.setOnLongClickListener {
            streams?.title?.let { ClipboardHelper.save(context, text = it) }
            true
        }

        binding.textLike.setOnClickListener {
            applyRating(
                if (currentRating == YouTubeAccount.Rating.LIKE) YouTubeAccount.Rating.NONE
                else YouTubeAccount.Rating.LIKE
            )
        }
        binding.textDislike.setOnClickListener {
            applyRating(
                if (currentRating == YouTubeAccount.Rating.DISLIKE) YouTubeAccount.Rating.NONE
                else YouTubeAccount.Rating.DISLIKE
            )
        }

        binding.tagsRecycler.adapter = videoTagsAdapter
    }

    /**
     * Sends a rating for the current video to the signed-in YouTube account.
     *
     * The icon updates immediately and rolls back if the request fails, so a rejected rating never
     * leaves the UI claiming something the account does not actually reflect.
     */
    private fun applyRating(rating: YouTubeAccount.Rating) {
        val videoId = videoId ?: return
        val scope = findViewTreeLifecycleOwner()?.lifecycleScope ?: return

        val previousRating = currentRating
        currentRating = rating
        updateRatingIcons()

        scope.launch {
            val error = runCatching { YouTubeAccount.rate(context, videoId, rating) }
                .exceptionOrNull() ?: return@launch

            currentRating = previousRating
            updateRatingIcons()
            context.toastFromMainDispatcher(
                if (error is NotSignedInException) R.string.youtube_sign_in_required
                else R.string.youtube_action_failed
            )
        }
    }

    private fun updateRatingIcons() {
        binding.textLike.compoundDrawableTintList = ColorStateList.valueOf(
            if (currentRating == YouTubeAccount.Rating.LIKE) activeRatingTint
            else inactiveRatingTint
        )
        binding.textDislike.compoundDrawableTintList = ColorStateList.valueOf(
            if (currentRating == YouTubeAccount.Rating.DISLIKE) activeRatingTint
            else inactiveRatingTint
        )
    }

    fun setSegments(segments: List<Segment>) {
        if (PlayerHelper.getSponsorBlockCategories()[SB_SPONSOR_CATEGORY] == SbSkipOptions.OFF) {
            // only show the badge if the user requested to show sponsors
           return
        }

        val category = segments.filter { it.actionType == Segment.TYPE_FULL }.firstNotNullOfOrNull { it.category }
        binding.playerSponsorBadge.isVisible = category != null
        binding.playerSponsorBadge.chipIcon = SponsorBlockLabelHelper.categoryIcon(category)?.let { context.getDrawable(it) }
        binding.playerSponsorBadge.text = SponsorBlockLabelHelper.categoryLabel(category)?.let { context.getString(it) }
    }

    @SuppressLint("SetTextI18n")
    fun setStreams(streams: Streams, videoId: String) {
        this.streams = streams
        this.videoId = videoId

        // a rating belongs to a single video, so it must not carry over to the next one
        currentRating = YouTubeAccount.Rating.NONE
        updateRatingIcons()

        val views = streams.views.formatShort()
        val date = TextUtils.formatRelativeDate(streams.uploaded ?: -1L)
        binding.run {
            playerViewsInfo.text = context.getString(R.string.normal_views, views, TextUtils.SEPARATOR + date)

            textLike.text = streams.likes.formatShort()
            textDislike.isVisible = streams.dislikes >= 0
            textDislike.text = streams.dislikes.formatShort()

            playerTitle.text = streams.title
            playerDescription.text = streams.description

            metaInfo.isVisible = streams.metaInfo.isNotEmpty()
            // generate a meta info text with clickable links using html
            val metaInfoText = streams.metaInfo.joinToString("\n\n") { info ->
                val text = info.description.takeIf { it.isNotBlank() } ?: info.title
                val links = info.urls.mapIndexed { index, url ->
                    "<a href=\"$url\">${info.urlTexts.getOrNull(index).orEmpty()}</a>"
                }.joinToString(", ")
                "$text $links"
            }
            metaInfo.text = metaInfoText.parseAsHtml()

            val visibility = when (streams.visibility) {
                "public" -> context?.getString(R.string.visibility_public)
                "unlisted" -> context?.getString(R.string.visibility_unlisted)
                // currently no other visibility could be returned, might change in the future however
                else -> streams.visibility.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                }
            }.orEmpty()
            additionalVideoInfo.text =
                "${context?.getString(R.string.category)}: ${streams.category}\n" +
                "${context?.getString(R.string.license)}: ${streams.license}\n" +
                "${context?.getString(R.string.visibility)}: $visibility"

            if (streams.tags.isNotEmpty()) {
                videoTagsAdapter.submitList(streams.tags)
            }
            binding.tagsRecycler.isVisible = streams.tags.isNotEmpty()

            setupDescription(streams.description)
        }
    }

    /**
     * Collapses the description, if it is currently expanded.
     */
    fun collapseDescription() {
        val isCollapsed = binding.descLinLayout.isGone
        if (!isCollapsed) {
            toggleDescription()
        }
    }

    /**
     * Set up the description text with video links and timestamps
     */
    private fun setupDescription(description: String) {
        val descTextView = binding.playerDescription
        // detect whether the description is html formatted
        if (description.contains("<") && description.contains(">")) {
            descTextView.movementMethod = LinkMovementMethodCompat.getInstance()
            descTextView.text = description.replace("</a>", "</a> ")
                .parseAsHtml(tagHandler = HtmlParser(LinkHandler(handleLink)))
        } else {
            // Links can be present as plain text
            descTextView.autoLinkMask = Linkify.WEB_URLS
            descTextView.text = description
        }
    }

    private fun toggleDescription() {
        val streams = streams ?: return

        val isNewStateExpanded = binding.descLinLayout.isGone
        if (!isNewStateExpanded) {
            // show a short version of the view count and date
            val formattedDate = TextUtils.formatRelativeDate(streams.uploaded ?: -1L)
            binding.playerViewsInfo.text = context.getString(R.string.normal_views, streams.views.formatShort(),  TextUtils.SEPARATOR + formattedDate)

            // limit the title height to two lines
            binding.playerTitle.maxLines = 2
        } else {
            // show the full view count and upload date
            val formattedDate = streams.uploadTimestamp?.let { TextUtils.localizeInstant(it) }.orEmpty()
            binding.playerViewsInfo.text = context.getString(R.string.normal_views, "%,d".format(streams.views),  TextUtils.SEPARATOR + formattedDate)

            // show the whole title
            binding.playerTitle.maxLines = Int.MAX_VALUE
        }

        binding.playerDescriptionArrow.animate()
            .rotation(if (isNewStateExpanded) 180F else 0F)
            .setDuration(ANIMATION_DURATION)
            .start()

        binding.playerDescription.isVisible = isNewStateExpanded
        binding.descLinLayout.isVisible = isNewStateExpanded
    }

    companion object {
        private const val ANIMATION_DURATION = 250L
        private const val SB_SPONSOR_CATEGORY = "sponsor_category"
    }
}
