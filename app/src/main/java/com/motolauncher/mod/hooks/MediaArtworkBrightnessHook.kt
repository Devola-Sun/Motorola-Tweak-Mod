package com.motolauncher.mod.hooks

import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.motolauncher.mod.ErrorLogger
import com.motolauncher.mod.HookConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Update actual view state before display-list recording, not boot-class onDraw hooks. */
object MediaArtworkBrightnessHook : YukiBaseHooker() {
    private const val SHADE_STATE = "motorolaTweak.artworkShadeBackground"
    private val images = WeakHashMap<ImageView, State>()
    private val observed = WeakHashMap<ViewGroup, Boolean>()
    private val sources = WeakHashMap<View, Source>()
    private val bindings = WeakHashMap<Any, List<WeakReference<View>>>()
    private val vendorSources = WeakHashMap<View, VendorSource>()
    private val reported = mutableSetOf<String>()
    private val diagnostics = mutableSetOf<String>()
    private val artworkIds = mutableMapOf<Int, Boolean>()
    private val inspectedPanels = WeakHashMap<ViewGroup, Boolean>()
    private var writing = false

    private class Source(val icon: Icon)
    private class ShadeState(var background: Drawable)
    private class VendorSource(val checkedAt: Long, val metadata: MediaMetadata?, val source: Source?)
    private class State(view: ImageView) {
        var original = view.drawable
        var applied: Drawable? = null
        var source: Source? = null
        var tint = view.imageTintList
        var filter = view.colorFilter
        var foreground: Drawable? = null
        var foregroundRemoved = false
        val forceDark = view.isForceDarkAllowed
    }

    override fun onHook() {
        // Explicitly select the same file that SettingsStore writes.
        val preferences = prefs(HookConfig.PREFS_NAME)
        diagnostic("installed", "Artwork state hook revision=7; enabled=${HookConfig.safeGetBoolean(preferences,
            HookConfig.KEY_MEDIA_ARTWORK_LIGHT_BRIGHTNESS, false)}")
        installBindings()
        runCatching {
            XposedBridge.hookAllMethods(LayoutInflater::class.java, "inflate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val inflater = param.thisObject as LayoutInflater
                        val id = param.args.firstOrNull() as? Int ?: return@runCatching
                        if (!inflater.context.resources.getResourceEntryName(id).contains("media", true)) return@runCatching
                        val root = param.result as? ViewGroup ?: return@runCatching
                        observe(root)
                    }.onFailure { report("observe inflated artwork", it) }
                }
            })
        }.onFailure { report("install artwork inflation observer", it) }
        val loader = appClassLoader ?: return
        for (name in listOf(
            "com.android.systemui.util.animation.TransitionLayout",
            "com.motorola.systemui.prc.media.MediaPanelModernQS",
            "com.motorola.systemui.prc.media.MediaPanelKeyguard"
        )) runCatching {
            val clazz = XposedHelpers.findClassIfExists(name, loader) ?: return@runCatching
            XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { (param.thisObject as? ViewGroup)?.let(::observe) }
                        .onFailure { report("observe artwork panel", it) }
                }
            })
            clazz.declaredMethods.filter { it.name in setOf("onLayout", "onFinishInflate",
                "applyCurrentState", "applyCurrentStateToView") }.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            if (param.hasThrowable()) return@runCatching
                            (param.thisObject as? ViewGroup)?.let(::updateArtwork)
                        }.onFailure { report("artwork native state", it) }
                    }
                })
            }
        }.onFailure { report("install artwork panel: $name", it) }

        // A track change can replace only the image without laying out its parent.
        runCatching {
            XposedHelpers.findAndHookMethod(ImageView::class.java, "setImageDrawable", Drawable::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            if (writing || param.hasThrowable()) return@runCatching
                            val view = param.thisObject as? ImageView ?: return@runCatching
                            if (!images.containsKey(view)) return@runCatching
                            updateImage(view, enabled())
                        }.onFailure { report("artwork replacement", it) }
                    }
                })
        }.onFailure { report("install artwork replacement observer", it) }
    }

    private fun enabled(): Boolean = HookConfig.safeGetBoolean(prefs(HookConfig.PREFS_NAME),
        HookConfig.KEY_MEDIA_ARTWORK_LIGHT_BRIGHTNESS, HookConfig.DEFAULT_MEDIA_ARTWORK_LIGHT_BRIGHTNESS)

    private fun observe(root: ViewGroup) {
        if (observed.put(root, true) != null) return
        val attachment = object : View.OnAttachStateChangeListener {
            private var observer: ViewTreeObserver? = null
            private var listener: ViewTreeObserver.OnPreDrawListener? = null
            override fun onViewAttachedToWindow(v: View) {
                runCatching {
                    if (listener != null) return@runCatching
                    listener = ViewTreeObserver.OnPreDrawListener {
                        updateArtwork(root)
                        true
                    }
                    observer = root.viewTreeObserver
                    observer?.addOnPreDrawListener(listener)
                    updateArtwork(root)
                }.onFailure { report("attach artwork observer", it) }
            }
            override fun onViewDetachedFromWindow(v: View) {
                runCatching {
                    listener?.let { if (observer?.isAlive == true) observer?.removeOnPreDrawListener(it) }
                    observer = null
                    listener = null
                    visit(root) { restore(it) }
                }.onFailure { report("detach artwork observer", it) }
            }
        }
        root.addOnAttachStateChangeListener(attachment)
        if (root.isAttachedToWindow) attachment.onViewAttachedToWindow(root)
    }

    /** Also called after the compact/glass renderer, so its changes cannot undo artwork. */
    internal fun updateArtwork(root: ViewGroup) {
        if (writing) return
        runCatching {
            val active = enabled()
            // Capture the actual vendor composition, not just ImageView IDs. A
            // sibling shade/custom paint will never appear in the image-only log.
            val inspect = active && root.isShown && root.width > 0 && root.height > 0 &&
                root.javaClass.name == "com.motorola.systemui.prc.media.MediaPanelModernQS" &&
                !inspectedPanels.containsKey(root)
            if (inspect) {
                inspectedPanels[root] = true
                inspectPanel(root, "before")
            }
            val diagnosticKey = "panel:${root.javaClass.name}:${root.id}:$active"
            if (diagnosticKey !in diagnostics && root.isAttachedToWindow &&
                (MediaArtworkScope.isMediaHost(name(root), root.javaClass.name) ||
                    name(root) == "root_view")) {
                val children = buildList {
                    visit(root) { add("${name(it)}:${it.javaClass.simpleName}") }
                }
                diagnostic(diagnosticKey,
                    "Artwork panel revision=7: ${root.javaClass.name} id=${name(root)} enabled=$active images=$children")
            }
            visit(root) { view ->
                if (images.containsKey(view) || isArtwork(view) && inMediaHost(view)) {
                    runCatching { updateImage(view, active) }.onFailure {
                        runCatching { restore(view) }.onFailure { failure -> report("artwork rollback", failure) }
                        report("update artwork", it)
                    }
                }
            }
            if (inspect) inspectPanel(root, "after")
        }.onFailure { report("visit artwork", it) }
    }

    private fun updateImage(view: ImageView, active: Boolean) {
        if (!active) {
            restore(view)
            return
        }
        val state = images.getOrPut(view) { State(view) }
        updateControlCenterShade(view, active = true)
        val current = view.drawable
        if (current !== state.applied) state.original = current
        // Native tint writers can run after a track bind. Retain their latest values.
        if (view.imageTintList != null) state.tint = view.imageTintList
        if (view.colorFilter != null) state.filter = view.colorFilter
        val source = sourceFor(view)
        if (state.applied == null || current !== state.applied || source !== state.source) {
            val replacement = source?.icon?.loadDrawable(view.context)?.mutate()
                ?: state.original?.constantState?.newDrawable(view.resources)?.mutate()
                ?: return
            state.source = source
            state.applied = clean(replacement)
        }
        writing = true
        try {
            if (view.imageTintList != null) view.imageTintList = null
            if (view.colorFilter != null) view.clearColorFilter()
            if (view.drawable !== state.applied) view.setImageDrawable(state.applied)
            // A foreground is drawn after the image; clearing the image's own
            // filter cannot remove this separate shade. The vendor sibling shade
            // is handled independently; card backgrounds and ripple drawing stay native.
            val foreground = view.foreground
            if (foreground is GradientDrawable || foreground is ColorDrawable) {
                state.foreground = foreground
                state.foregroundRemoved = true
                view.foreground = null
            }
            if (view.isForceDarkAllowed) view.isForceDarkAllowed = false
        } finally {
            writing = false
        }
        diagnostic("applied:${view.parent?.javaClass?.name}:${name(view)}:${source != null}",
            "Artwork state applied: parent=${view.parent?.javaClass?.name}, id=${name(view)}, " +
                "original=${state.original?.javaClass?.name}, rawSource=${source != null}")
    }

    private fun clean(drawable: Drawable, composite: Boolean = false): Drawable {
        // Only private copies are edited. The original (including nested native
        // filters/gradients) remains intact for disabling and future track binds.
        val scrim = drawable is GradientDrawable || drawable is ColorDrawable
        if (MediaArtworkLayerPolicy.replaceScrim(composite, scrim)) {
            // Transparent pixel data, rather than alpha=0 on an opaque gradient,
            // stays transparent even if the enclosing LayerDrawable sets alpha=255.
            // Replacing in-place retains layer insets, IDs, gravity and image fades.
            return ColorDrawable(Color.TRANSPARENT)
        }
        drawable.setTintList(null)
        drawable.clearColorFilter()
        drawable.alpha = MediaArtworkLayerPolicy.alpha(drawable.alpha, composite, scrim)
        when (drawable) {
            is LayerDrawable -> for (i in 0 until drawable.numberOfLayers) {
                val child = drawable.getDrawable(i)
                val cleaned = clean(child, true)
                if (cleaned !== child) drawable.setDrawable(i, cleaned)
            }
            is DrawableWrapper -> drawable.drawable?.let { child ->
                val cleaned = clean(child, composite)
                if (cleaned !== child) drawable.drawable = cleaned
            }
            else -> drawable.current.takeIf { it !== drawable }?.let { clean(it, composite) }
        }
        return drawable
    }

    private fun restore(view: ImageView) {
        updateControlCenterShade(view, active = false)
        val state = images.remove(view) ?: return
        writing = true
        try {
            if (view.drawable === state.applied) view.setImageDrawable(state.original)
            if (view.imageTintList == null) view.imageTintList = state.tint
            if (view.colorFilter == null) view.colorFilter = state.filter
            if (state.foregroundRemoved && view.foreground == null) view.foreground = state.foreground
            view.isForceDarkAllowed = state.forceDark
        } finally {
            writing = false
        }
    }

    private fun updateControlCenterShade(artwork: ImageView, active: Boolean) {
        // The live hierarchy is MotoMediaViewForPrcQS > CardView > album_art,
        // with touch_ripple_view as a sibling of the CardView, NOT its foreground.
        var parent = artwork.parent as? ViewGroup
        while (parent != null) {
            if (parent.javaClass.name == "com.motorola.systemui.prc.media.MotoMediaViewForPrcQS") {
                for (i in 0 until parent.childCount) {
                    val shade = parent.getChildAt(i)
                    if (!MediaArtworkScope.isControlCenterShade(name(shade), shade.javaClass.name,
                            parent.javaClass.name)) continue
                    val previous = XposedHelpers.getAdditionalInstanceField(shade, SHADE_STATE) as? ShadeState
                    if (!active) {
                        XposedHelpers.removeAdditionalInstanceField(shade, SHADE_STATE)
                        if (shade.background == null && previous != null) shade.background = previous.background
                        continue
                    }
                    // Remove only the observed persistent gradient. Do not hide
                    // the view: MultiRippleView.onDraw still owns touch feedback.
                    val background = shade.background as? GradientDrawable ?: continue
                    if (previous != null) previous.background = background
                    else XposedHelpers.setAdditionalInstanceField(shade, SHADE_STATE, ShadeState(background))
                    shade.background = null
                    diagnostic("shade:${shade.javaClass.name}",
                        "Artwork shade removed revision=7: id=${name(shade)} parent=${parent.javaClass.name}; " +
                            "ripple drawing and view alpha preserved")
                }
                return
            }
            if (parent.javaClass.name == "com.motorola.systemui.prc.media.MediaPanelModernQS") return
            parent = parent.parent as? ViewGroup
        }
    }

    private fun installBindings() {
        val loader = appClassLoader ?: return
        for (className in listOf(
            "com.android.systemui.media.controls.ui.controller.MediaControlPanel",
            "com.android.systemui.media.controls.ui.MediaControlPanel",
            "com.motorola.systemui.prc.media.MediaPanel",
            "com.motorola.systemui.prc.media.MediaPanelModernQS",
            "com.motorola.systemui.prc.media.MediaPanelKeyguard"
        )) runCatching {
            val clazz = XposedHelpers.findClassIfExists(className, loader) ?: return@runCatching
            val methods = clazz.declaredMethods.filter { method ->
                method.parameterTypes.any { it.simpleName == "MediaData" } &&
                    (method.name.startsWith("bind") || method.name.startsWith("set") || method.name.startsWith("update"))
            }
            methods.forEach { method ->
                val index = method.parameterTypes.indexOfFirst { it.simpleName == "MediaData" }
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            if (param.hasThrowable()) return@runCatching
                            bindings.remove(param.thisObject)?.mapNotNull { it.get() }?.forEach { sources.remove(it) }
                            val data = param.args.getOrNull(index) ?: return@runCatching
                            val icon = readMember(data, "artwork", "getArtwork") as? Icon
                            val roots = playerRoots(param.thisObject)
                            bindings[param.thisObject] = roots.map(::WeakReference)
                            val source = icon?.let(::Source)
                            roots.forEach { root ->
                                if (source != null) sources[root] = source
                                if (root is ViewGroup) observe(root)
                            }
                        }.onFailure { report("bind artwork: $className", it) }
                    }
                })
            }
            diagnostic("binding:$className", "Artwork binding: $className methods=${methods.size}")
        }.onFailure { report("install artwork binding: $className", it) }
    }

    private fun readMember(owner: Any, field: String, getter: String): Any? {
        owner.javaClass.methods.firstOrNull { it.name == getter && it.parameterCount == 0 }?.let { return it.invoke(owner) }
        var type: Class<*>? = owner.javaClass
        while (type != null && type != Any::class.java) {
            type.declaredFields.firstOrNull { it.name == field }?.let {
                it.isAccessible = true
                return it.get(owner)
            }
            type = type.superclass
        }
        return null
    }

    private fun playerRoots(owner: Any): List<View> {
        if (owner is View) return listOf(owner)
        val holder = readMember(owner, "mMediaViewHolder", "getMediaViewHolder")
            ?: readMember(owner, "mediaViewHolder", "getMediaViewHolder") ?: return emptyList()
        return listOfNotNull(readMember(holder, "player", "getPlayer") as? View,
            readMember(holder, "albumView", "getAlbumView") as? View).distinct()
    }

    private fun sourceFor(view: View): Source? {
        var current: View? = view
        while (current != null) {
            sources[current]?.let { return it }
            if (current.javaClass.name.startsWith("com.motorola.systemui.prc.media.MediaPanel")) vendorSource(current)?.let { return it }
            current = current.parent as? View
        }
        return null
    }

    private fun vendorSource(panel: View): Source? {
        val now = SystemClock.uptimeMillis()
        val previous = vendorSources[panel]
        if (previous != null && now - previous.checkedAt in 0 until 750L) return previous.source
        var type: Class<*>? = panel.javaClass
        var controller: MediaController? = null
        while (type != null && type != ViewGroup::class.java && type != View::class.java) {
            val field = type.declaredFields.firstOrNull {
                !java.lang.reflect.Modifier.isStatic(it.modifiers) && MediaController::class.java.isAssignableFrom(it.type)
            }
            if (field != null) {
                field.isAccessible = true
                controller = field.get(panel) as? MediaController
                if (controller != null) break
            }
            type = type.superclass
        }
        val metadata = controller?.metadata
        val source = if (metadata != null && metadata === previous?.metadata) previous.source else {
            val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            bitmap?.let { Source(Icon.createWithBitmap(it)) }
        }
        vendorSources[panel] = VendorSource(now, metadata, source)
        return source
    }

    private fun visit(root: ViewGroup, action: (ImageView) -> Unit) {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is ImageView) action(child)
            if (child is ViewGroup) visit(child, action)
        }
    }

    private fun isArtwork(view: ImageView): Boolean {
        if (view.id <= 0 || view.id ushr 24 == 0) return false
        return artworkIds.getOrPut(view.id) { MediaArtworkScope.isArtwork(name(view)) }
    }

    private fun inMediaHost(view: View): Boolean {
        var parent = view.parent as? View
        while (parent != null) {
            if (MediaArtworkScope.isMediaHost(name(parent), parent.javaClass.name)) return true
            if (name(parent) == "root_view" && parent is ViewGroup) {
                val progress = parent.resources.getIdentifier("media_progress_bar", "id", HookConfig.SYSTEM_UI_PACKAGE)
                if (progress != 0 && parent.findViewById<View>(progress) != null) return true
            }
            parent = parent.parent as? View
        }
        return false
    }

    private fun name(view: View): String = if (view.id <= 0 || view.id ushr 24 == 0) ""
        else view.resources.getResourceEntryName(view.id)

    /** Bounded, once per visible vendor panel; never logs track titles or artwork pixels. */
    private fun inspectPanel(root: ViewGroup, stage: String) {
        runCatching {
            val lines = mutableListOf<String>()
            fun describe(view: View, depth: Int) {
                if (depth > 6 || lines.size >= 64) return
                lines += "${" ".repeat(depth)}${name(view)}:${view.javaClass.name} " +
                    "bounds=${view.left},${view.top},${view.right},${view.bottom} " +
                    "visibility=${view.visibility} alpha=${view.alpha} z=${view.z} " +
                    "layer=${view.layerType} forceDark=${view.isForceDarkAllowed} " +
                    "background=${describeDrawable(view.background)} " +
                    "foreground=${describeDrawable(view.foreground)}" +
                    if (view is ImageView) " image=${describeDrawable(view.drawable)} " +
                        "imageAlpha=${view.imageAlpha} filter=${view.colorFilter} tint=${view.imageTintList}" else ""
                // Vendor onDraw/dispatchDraw can draw a shade directly using a
                // private Paint/Drawable without exposing a foreground View.
                var type: Class<*>? = view.javaClass
                while (type != null && !type.name.startsWith("android.") && lines.size < 64) {
                    for (field in type.declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                        if (!Drawable::class.java.isAssignableFrom(field.type) &&
                            !Paint::class.java.isAssignableFrom(field.type)) continue
                        runCatching {
                            field.isAccessible = true
                            val value = field.get(view)
                            val detail = when (value) {
                                is Drawable -> describeDrawable(value)
                                is Paint -> "Paint(color=${Integer.toHexString(value.color)} alpha=${value.alpha} " +
                                    "filter=${value.colorFilter} shader=${value.shader?.javaClass?.name} blend=${value.blendMode})"
                                else -> "null"
                            }
                            if (lines.size < 64) lines += " field ${type.name}.${field.name}=$detail"
                        }.onFailure { report("inspect vendor shade field: ${field.name}", it) }
                    }
                    type = type.superclass
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) describe(view.getChildAt(i), depth + 1)
            }
            describe(root, 0)
            var parent = root.parent as? View
            repeat(3) {
                val ancestor = parent ?: return@repeat
                lines += "ancestor ${name(ancestor)}:${ancestor.javaClass.name} alpha=${ancestor.alpha} " +
                    "foreground=${describeDrawable(ancestor.foreground)}"
                parent = ancestor.parent as? View
            }
            ErrorLogger.log("MotoMediaArtwork", "Artwork composition revision=7 stage=$stage " +
                "uiMode=${root.resources.configuration.uiMode}\n${lines.joinToString("\n")}")
        }.onFailure { report("inspect artwork composition", it) }
    }

    private fun describeDrawable(drawable: Drawable?, depth: Int = 0): String {
        if (drawable == null) return "null"
        if (depth >= 4) return drawable.javaClass.name
        val detail = when (drawable) {
            is LayerDrawable -> (0 until minOf(drawable.numberOfLayers, 8)).joinToString(prefix = "[", postfix = "]") {
                "$it/id=${drawable.getId(it)}:${describeDrawable(drawable.getDrawable(it), depth + 1)}"
            }
            is DrawableWrapper -> "child=${describeDrawable(drawable.drawable, depth + 1)}"
            is GradientDrawable -> "orientation=${drawable.orientation} colors=" +
                drawable.colors?.joinToString { Integer.toHexString(it) }
            is ColorDrawable -> "color=${Integer.toHexString(drawable.color)}"
            is BitmapDrawable -> "bitmap=${drawable.bitmap.width}x${drawable.bitmap.height} " +
                "paintAlpha=${drawable.paint.alpha} paintFilter=${drawable.paint.colorFilter}"
            else -> drawable.current.takeIf { it !== drawable }?.let { describeDrawable(it, depth + 1) }.orEmpty()
        }
        return "${drawable.javaClass.name}(alpha=${drawable.alpha} filter=${drawable.colorFilter} $detail)"
    }

    private fun diagnostic(key: String, message: String) {
        // Persist a bounded number of markers: logcat may already have rotated on reboot.
        if (diagnostics.add(key)) ErrorLogger.log("MotoMediaArtwork", message)
    }

    private fun report(operation: String, error: Throwable) {
        if (reported.add(operation)) ErrorLogger.log("MotoMediaArtwork", operation, error)
    }
}
