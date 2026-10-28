package com.motolauncher.mod.hooks

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import java.util.WeakHashMap

/** White foreground for light-mode QS-style dark glass; custom content/buttons stay native. */
internal object LightGlassContentStyle {
    private val tint = ColorStateList.valueOf(SystemUiGlassPalette.primaryContentColor(false, true))
    private val track = ColorStateList.valueOf(SystemUiGlassPalette.progressTrackColor(false, true))
    private val roots = WeakHashMap<ViewGroup, State>()
    private class State {
        val texts = WeakHashMap<TextView, ColorStateList>()
        val images = WeakHashMap<ImageView, ColorStateList?>()
        val progress = WeakHashMap<SeekBar, Progress>()
    }
    private data class Progress(val fill: ColorStateList?, val track: ColorStateList?, val thumb: ColorStateList?)
    private val notificationLabels = setOf("title", "text", "text2", "big_text", "info", "summary_text",
        "app_name_text", "header_text", "header_text_secondary", "time", "chronometer",
        "conversation_text", "conversation_title", "message_text", "message_sender", "sender_text")
    private val mediaLabels = setOf("header_title", "header_artist", "media_elapsed_time", "media_total_time",
        "media_scrubbing_elapsed_time", "media_scrubbing_total_time", "media_seamless_text")
    private val mediaImages = setOf("actionPlayPause", "actionPrev", "actionNext", "action0", "action1",
        "action2", "action3", "action4", "media_seamless_image")

    fun apply(root: ViewGroup, media: Boolean) {
        val state = roots.getOrPut(root) { State() }
        fun visit(parent: ViewGroup) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                val name = name(child)
                // For notifications require framework IDs as well as known names.
                // An app's custom layout must not accidentally match a standard label.
                val standardLabel = if (media) name in mediaLabels else
                    child.id ushr 24 == 1 && name in notificationLabels
                if (child is TextView && child !is android.widget.Button &&
                    child !is android.widget.EditText && standardLabel) {
                    if (child.textColors !== tint) {
                        state.texts[child] = child.textColors
                        child.setTextColor(tint)
                    }
                }
                if (media && child is ImageView && name in mediaImages && child.imageTintList !== tint) {
                    state.images[child] = child.imageTintList
                    child.imageTintList = tint
                }
                if (media && child is SeekBar && name == "media_progress_bar") {
                    val old = state.progress[child]
                    if (old == null || child.progressTintList !== tint ||
                        child.progressBackgroundTintList !== track || child.thumbTintList !== tint) {
                        state.progress[child] = Progress(
                            if (child.progressTintList === tint) old?.fill else child.progressTintList,
                            if (child.progressBackgroundTintList === track) old?.track else child.progressBackgroundTintList,
                            if (child.thumbTintList === tint) old?.thumb else child.thumbTintList)
                        child.progressTintList = tint
                        child.progressBackgroundTintList = track
                        child.thumbTintList = tint
                    }
                }
                if (child is ViewGroup) visit(child)
            }
        }
        try {
            visit(root)
        } catch (error: Throwable) {
            runCatching { restore(root) }.onFailure { error.addSuppressed(it) }
            throw error
        }
    }

    fun restore(root: ViewGroup) {
        val state = roots.remove(root) ?: return
        state.texts.forEach { (view, original) -> if (view.textColors === tint) view.setTextColor(original) }
        state.images.forEach { (view, original) -> if (view.imageTintList === tint) view.imageTintList = original }
        state.progress.forEach { (view, original) ->
            if (view.progressTintList === tint) view.progressTintList = original.fill
            if (view.progressBackgroundTintList === track) view.progressBackgroundTintList = original.track
            if (view.thumbTintList === tint) view.thumbTintList = original.thumb
        }
    }

    private fun name(view: View): String = if (view.id <= 0 || view.id ushr 24 == 0) ""
        else view.resources.getResourceEntryName(view.id)
}
