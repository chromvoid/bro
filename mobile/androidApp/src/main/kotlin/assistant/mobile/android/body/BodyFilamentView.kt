package assistant.mobile.android.body

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import android.view.ViewConfiguration
import android.widget.FrameLayout
import assistant.mobile.core.body.BODY_CORRECTION_NAMES
import assistant.mobile.core.body.BODY_LOOKAROUND_CLIP
import assistant.mobile.core.body.BODY_SHAPE_NAMES
import assistant.mobile.core.body.BodyLayer
import assistant.mobile.core.body.BodyQuaternion
import assistant.mobile.core.body.BodyPoint
import assistant.mobile.core.body.BodyRendererFrame
import assistant.mobile.core.body.bodyComposition
import assistant.mobile.core.body.bodyOrbitPosition
import assistant.mobile.core.body.compositionWeights
import assistant.mobile.core.body.lookaroundSample
import assistant.mobile.core.body.normalizeBodyFrame
import assistant.mobile.core.body.reliefWeights
import assistant.mobile.design.GeneratedColors
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Renderer
import com.google.android.filament.SwapChain
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.TextureHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Main-thread Filament adapter. All clocks and choices enter through the common immutable frame. */
class BodyFilamentView(context: Context) : FrameLayout(context), AutoCloseable {
    var onFrameTime: ((Long) -> Unit)? = null
    var onFailure: ((Throwable) -> Unit)? = null
    var onReady: (() -> Unit)? = null
    var onYaw: ((Double) -> Unit)? = null
    val diagnostics = BodyRendererDiagnostics()
    private val surface = SurfaceView(context).apply {
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(false)
    }
    private var backend: BodyFilamentBackend? = null
    private var frame = BodyRendererFrame()
    private var active = false
    private var disposed = false
    private var failed = false
    private var callbackScheduled = false
    private var drawing = false
    private var previousRenderedNanos: Long? = null
    private val choreographer = Choreographer.getInstance()
    private val callback = Choreographer.FrameCallback { nanos ->
        removePendingCount()
        if (!canDraw()) return@FrameCallback
        drawing = true
        try {
            onFrameTime?.invoke(nanos)
            val start = System.nanoTime()
            if (backend?.render(frame, nanos) == true) {
                diagnostics.renderedFrames++
                previousRenderedNanos?.let { previous ->
                    if (diagnostics.frameIntervalsNanos.size < 60 && nanos > previous) {
                        diagnostics.frameIntervalsNanos += nanos - previous
                    }
                }
                previousRenderedNanos = nanos
                if (diagnostics.submittedFrameCpuNanos.size < 60) {
                    diagnostics.submittedFrameCpuNanos += System.nanoTime() - start
                }
            }
        } catch (error: Throwable) { fail(error) }
        finally { drawing = false }
        if (moving()) requestFrame()
    }
    private var downX = 0f
    private var downY = 0f
    private var previousX = 0f
    private var dragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init { addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }

    fun loadFiles(model: File, lookaround: File) {
        checkMainThread()
        if (disposed || failed) return
        previousRenderedNanos = null
        try {
            backend?.close()
            backend = null
            val created = BodyFilamentBackend(context, surface, diagnostics, ::requestFrame)
            backend = created
            created.load(model, lookaround)
            diagnostics.ready = true
            onReady?.invoke()
            requestFrame()
        } catch (error: Throwable) { fail(error) }
    }

    fun setFrame(value: BodyRendererFrame) {
        checkMainThread()
        if (disposed || failed) return
        frame = value
        if (!moving()) cancelFrame()
        requestFrame()
    }

    fun setActive(value: Boolean) {
        checkMainThread()
        active = value
        if (!value) previousRenderedNanos = null
        if (value) requestFrame() else cancelFrame()
    }

    fun jointTransformSnapshot(): Map<String, List<Float>> {
        checkMainThread()
        return backend?.jointTransformSnapshot().orEmpty()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) requestFrame() else cancelFrame()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); requestFrame() }
    override fun onDetachedFromWindow() { close(); super.onDetachedFromWindow() }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        backend?.resizeBuffer(width, height)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; previousX = event.x; dragging = false }
            MotionEvent.ACTION_MOVE -> if (abs(event.x - downX) > touchSlop && abs(event.x - downX) > abs(event.y - downY)) {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; previousX = event.x; return true }
            MotionEvent.ACTION_MOVE -> if (dragging || abs(event.x - downX) > touchSlop && abs(event.x - downX) > abs(event.y - downY)) {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                val yaw = frame.yaw - 2 * PI * (event.x - previousX) / max(1, height)
                previousX = event.x
                frame = frame.copy(yaw = yaw)
                onYaw?.invoke(yaw)
                requestFrame()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                dragging = false
                return true
            }
        }
        return true
    }

    private fun moving() = frame.animate && !frame.reducedMotion && !frame.comparison
    private fun canDraw() = !disposed && !failed && active && isAttachedToWindow && windowVisibility == VISIBLE &&
        diagnostics.ready && backend?.readyToRender == true
    private fun requestFrame() {
        if (drawing || callbackScheduled || !canDraw()) return
        callbackScheduled = true
        diagnostics.frameCallbackScheduled = true
        pendingCallbacks.incrementAndGet()
        choreographer.postFrameCallback(callback)
    }
    private fun removePendingCount() {
        if (!callbackScheduled) return
        callbackScheduled = false
        diagnostics.frameCallbackScheduled = false
        pendingCallbacks.decrementAndGet()
    }
    private fun cancelFrame() { choreographer.removeFrameCallback(callback); removePendingCount() }
    private fun fail(error: Throwable) {
        if (failed || disposed) return
        failed = true
        diagnostics.failure = error.toString()
        cancelFrame()
        backend?.let { runCatching { it.close() } }
        backend = null
        onFailure?.invoke(error)
    }
    override fun close() {
        if (disposed) return
        checkMainThread()
        disposed = true
        cancelFrame()
        backend?.close()
        backend = null
        diagnostics.closed = true
        onFrameTime = null
        onReady = null
        onFailure = null
        onYaw = null
    }
    companion object {
        /** Instrumentation observes lifecycle ownership without enabling any runtime debug path. */
        val pendingCallbacks = AtomicInteger()
        val liveRenderers = AtomicInteger()
    }
}

class BodyRendererDiagnostics {
    var ready = false; internal set
    var closed = false; internal set
    var failure: String? = null; internal set
    var frameCallbackScheduled = false; internal set
    var renderedFrames = 0L; internal set
    var loadedMorphTargetCount = 0; internal set
    var skinningJointCount = 0; internal set
    var animationNames: List<String> = emptyList(); internal set
    var sampledClip: String? = null; internal set
    var sampledSeconds = 0.0; internal set
    var lastMorphWeights: List<Float> = emptyList(); internal set
    var layer = BodyLayer.Composition; internal set
    var orbitTarget: BodyPoint? = null; internal set
    var orbitPosition: BodyPoint? = null; internal set
    var normalizationTranslation: BodyPoint? = null; internal set
    var normalizedRestAxis: BodyPoint? = null; internal set
    val submittedFrameCpuNanos = mutableListOf<Long>()
    val frameIntervalsNanos = mutableListOf<Long>()
}

private class BodyFilamentBackend(
    private val context: Context,
    private val surface: SurfaceView,
    private val diagnostics: BodyRendererDiagnostics,
    private val invalidate: () -> Unit,
) : AutoCloseable {
    private val engine: Engine
    private val renderer: Renderer
    private val scene: com.google.android.filament.Scene
    private val view: View
    private val cameraEntity: Int
    private val camera: Camera
    private val materialProvider: UbershaderProvider
    private val assetLoader: AssetLoader
    private val resourceLoader: ResourceLoader
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private var swapChain: SwapChain? = null
    private var asset: FilamentAsset? = null
    private var motion: FilamentAsset? = null
    private var glb: BodyGlb? = null
    private var skinMaterial: Material? = null
    private var surfaceMaterial: Material? = null
    private val skinInstances = mutableListOf<MaterialInstance>()
    private val authoredInstances = mutableListOf<MaterialInstance>()
    private val textures = mutableListOf<Texture>()
    private val lightEntities = mutableListOf<Int>()
    // Keep stable Entity IDs. Animator commits and endFrame maintenance reorder component handles.
    private val jointBridge = mutableListOf<Pair<Int, Int>>()
    private val morphEntities = mutableListOf<Int>()
    private var indirectLight: IndirectLight? = null
    private var closed = false
    private var viewportWidth = 1
    private var viewportHeight = 1
    val readyToRender get() = !closed && uiHelper.isReadyToRender

    init {
        Gltfio.init()
        engine = Engine.create(Engine.Backend.OPENGL)
        BodyFilamentView.liveRenderers.incrementAndGet()
        try {
        renderer = engine.createRenderer()
        scene = engine.createScene()
        cameraEntity = EntityManager.get().create()
        camera = engine.createCamera(cameraEntity).apply { setExposure(16f, 1f / 125f, 100f) }
        view = engine.createView().apply {
            this.scene = this@BodyFilamentBackend.scene
            this.camera = this@BodyFilamentBackend.camera
            blendMode = View.BlendMode.TRANSLUCENT
            antiAliasing = View.AntiAliasing.FXAA
        }
        renderer.clearOptions = Renderer.ClearOptions().apply { clear = true; clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0) }
        materialProvider = UbershaderProvider(engine)
        assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
        resourceLoader = ResourceLoader(engine, true)
        uiHelper.isOpaque = false
        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(nativeSurface: Surface) {
                swapChain?.let(engine::destroySwapChain)
                swapChain = engine.createSwapChain(nativeSurface, SwapChain.CONFIG_TRANSPARENT)
                invalidate()
            }
            override fun onDetachedFromSurface() {
                swapChain?.let(engine::destroySwapChain)
                swapChain = null
                engine.flushAndWait()
            }
            override fun onResized(width: Int, height: Int) {
                viewportWidth = max(1, width); viewportHeight = max(1, height)
                view.viewport = Viewport(0, 0, viewportWidth, viewportHeight)
                val aspect = viewportWidth.toDouble() / viewportHeight
                camera.setProjection(2 * atan(tan(33 * PI / 360) / minOf(1.0, aspect)) * 180 / PI,
                    aspect, 0.05, 40.0, Camera.Fov.VERTICAL)
                engine.flushAndWait()
                invalidate()
            }
        }
        uiHelper.attachTo(surface)
        resizeBuffer(surface.width, surface.height)
        setupLighting()
        } catch (error: Throwable) {
            runCatching { uiHelper.detach() }
            engine.destroy()
            BodyFilamentView.liveRenderers.decrementAndGet()
            throw error
        }
    }

    fun resizeBuffer(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || closed) return
        val scale = minOf(1.0, 900.0 / max(width, height))
        uiHelper.setDesiredSize(max(1, (width * scale).toInt()), max(1, (height * scale).toInt()))
    }

    fun load(model: File, lookaround: File) {
        val source = BodyGlb(model.readBytes())
        glb = source
        val loaded = requireNotNull(assetLoader.createAsset(directBuffer(source.bytes))) { "Filament rejected BodyAtlas GLB" }
        asset = loaded
        resourceLoader.loadResources(loaded)
        val animation = requireNotNull(assetLoader.createAsset(directBuffer(lookaround.readBytes()))) { "Filament rejected lookaround GLB" }
        motion = animation
        resourceLoader.loadResources(animation)
        val transform = engine.transformManager
        // The contract's anatomical surfaces exclude child hair, cloth and accessories.
        val minimum = DoubleArray(3) { Double.POSITIVE_INFINITY }
        val maximum = DoubleArray(3) { Double.NEGATIVE_INFINITY }
        source.framingNodes.forEach { name ->
            val entity = loaded.getFirstEntityByName(name)
            require(entity != 0) { "BodyAtlas framing surface is missing" }
            val box = engine.renderableManager.getAxisAlignedBoundingBox(engine.renderableManager.getInstance(entity), null)
            val world = transform.getWorldTransform(transform.getInstance(entity), null)
            for (x in listOf(-1f, 1f)) for (y in listOf(-1f, 1f)) for (z in listOf(-1f, 1f)) {
                val corner = floatArrayOf(box.center[0] + x * box.halfExtent[0],
                    box.center[1] + y * box.halfExtent[1], box.center[2] + z * box.halfExtent[2], 1f)
                val point = FloatArray(4)
                android.opengl.Matrix.multiplyMV(point, 0, world, 0, corner, 0)
                for (coordinate in 0..2) {
                    minimum[coordinate] = minOf(minimum[coordinate], point[coordinate].toDouble())
                    maximum[coordinate] = maxOf(maximum[coordinate], point[coordinate].toDouble())
                }
            }
        }
        val height = maximum[1] - minimum[1]
        val axis = source.rootJoints.singleOrNull()?.let { name ->
            val entity = loaded.getFirstEntityByName(name)
            require(entity != 0) { "BodyAtlas skeleton root is missing" }
            val world = transform.getWorldTransform(transform.getInstance(entity), null)
            BodyPoint(world[12].toDouble(), world[13].toDouble(), world[14].toDouble())
        } ?: BodyPoint((minimum[0] + maximum[0]) / 2, (minimum[1] + maximum[1]) / 2,
            (minimum[2] + maximum[2]) / 2)
        val normalization = normalizeBodyFrame(minimum[1], height, axis)
        val scale = normalization.scale.toFloat()
        val translation = normalization.translation
        val rootTransform = floatArrayOf(scale, 0f, 0f, 0f, 0f, scale, 0f, 0f, 0f, 0f, scale, 0f,
            translation.x.toFloat(), translation.y.toFloat(), translation.z.toFloat(), 1f)
        transform.setTransform(transform.getInstance(loaded.root), rootTransform)
        diagnostics.orbitTarget = normalization.target
        diagnostics.normalizationTranslation = translation
        diagnostics.normalizedRestAxis = normalization.transform(axis)
        source.joints.forEach { name ->
            val target = loaded.getFirstEntityByName(name)
            val from = animation.getFirstEntityByName(name)
            require(target != 0 && from != 0) { "Lookaround skeleton differs at $name" }
            jointBridge += from to target
        }
        // The armature ancestor may carry root motion and belongs to the same named graph.
        loaded.entities.filter { loaded.getName(it) == "InBodySkeleton" }.forEach { target ->
            val from = animation.getFirstEntityByName("InBodySkeleton")
            if (from != 0) jointBridge += from to target
        }
        val expectedMorphs = BODY_SHAPE_NAMES + BODY_CORRECTION_NAMES
        loaded.renderableEntities.forEach { entity ->
            val names = loaded.getMorphTargetNames(entity).toList()
            if (loaded.getName(entity) in source.morphNodes) {
                require(names == expectedMorphs) { "BodyAtlas requires all 30+4 authored morphs" }
                morphEntities += entity
            }
        }
        require(morphEntities.isNotEmpty())
        installSkinMaterials(source, loaded)
        diagnostics.loadedMorphTargetCount = expectedMorphs.size
        diagnostics.skinningJointCount = source.joints.size
        val animator = loaded.instance.animator
        diagnostics.animationNames = List(animator.animationCount) { animator.getAnimationName(it) } +
            List(animation.instance.animator.animationCount) { animation.instance.animator.getAnimationName(it) }
        require(listOf("relaxed", BODY_LOOKAROUND_CLIP).all { it in diagnostics.animationNames })
        scene.addEntities(loaded.entities)
        loaded.releaseSourceData()
        animation.releaseSourceData()
    }

    private fun installSkinMaterials(source: BodyGlb, loaded: FilamentAsset) {
        skinMaterial = context.assets.open("body/materials/body_skin.filamat").use { stream ->
            val buffer = directBuffer(stream.readBytes())
            Material.Builder().payload(buffer, buffer.remaining()).build(engine)
        }
        surfaceMaterial = context.assets.open("body/materials/body_surface.filamat").use { stream ->
            val buffer = directBuffer(stream.readBytes())
            Material.Builder().payload(buffer, buffer.remaining()).build(engine)
        }
        val relief = source.reliefTextureIndices.map { textureId ->
            val bytes = source.embeddedTexture(textureId)
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888; inScaled = false }))
            val texture = Texture.Builder().width(bitmap.width).height(bitmap.height).levels(0xff)
                .sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.RGBA8)
                .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE).build(engine)
            textures += texture
            TextureHelper.setBitmap(engine, texture, 0, bitmap, Handler(Looper.getMainLooper()), Runnable { bitmap.recycle() })
            texture.generateMipmaps(engine)
            texture
        }
        val sampler = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
            TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.REPEAT)
        source.surfaces.forEach { surface ->
            val entity = loaded.getFirstEntityByName(surface.node)
            require(entity != 0)
            val renderable = engine.renderableManager.getInstance(entity)
            val authored = source.material(surface.material)
            val original = authored.getJSONObject("pbrMetallicRoughness")
            val color = original.getJSONArray("baseColorFactor")
            val skin = surface.role == "skin"
            val instance = requireNotNull(if (skin) skinMaterial else surfaceMaterial).createInstance()
            instance.setDoubleSided(authored.optBoolean("doubleSided", false))
            if (skin) {
                skinInstances += instance
                listOf("normalSoft", "normalNeutral", "normalDefined", "reliefMask").forEachIndexed { index, name ->
                    instance.setParameter(name, relief[index], sampler)
                }
                instance.setParameter("normalStrength", 0.6f)
                instance.setParameter("reliefWeights", 0f, 1f, 0f)
                instance.setParameter("tint", 0f, 0f, 0f)
                instance.setParameter("tintAmount", 0f)
            } else {
                authoredInstances += instance
            }
            instance.setParameter("baseColor", color.getDouble(0).toFloat(), color.getDouble(1).toFloat(), color.getDouble(2).toFloat())
            instance.setParameter("roughness", original.optDouble("roughnessFactor", 1.0).toFloat())
            instance.setParameter("metallic", original.optDouble("metallicFactor", 1.0).toFloat())
            val primitiveMaterials = source.primitiveMaterials(surface.node)
            require(primitiveMaterials.size == engine.renderableManager.getPrimitiveCount(renderable))
            for (primitive in primitiveMaterials.indices) {
                if (primitiveMaterials[primitive] == surface.material) {
                    engine.renderableManager.setMaterialInstanceAt(renderable, primitive, instance)
                }
            }
        }
    }

    fun render(frame: BodyRendererFrame, nanos: Long): Boolean {
        val loaded = asset ?: return false
        val source = glb ?: return false
        val composition = bodyComposition(frame.visual)
        val clip = if (frame.comparison) "relaxed" else BODY_LOOKAROUND_CLIP
        val animator = loaded.instance.animator
        if (clip == BODY_LOOKAROUND_CLIP) {
            val animation = requireNotNull(motion).instance.animator
            val index = (0 until animation.animationCount).first { animation.getAnimationName(it) == BODY_LOOKAROUND_CLIP }
            val sample = lookaroundSample(frame.lookaroundElapsedSeconds, animation.getAnimationDuration(index).toDouble())
            animation.applyAnimation(index, sample.sourceSeconds.toFloat())
            sample.blendSourceSeconds?.let { head ->
                animation.applyAnimation(index, head.toFloat())
                animation.applyCrossFade(index, sample.sourceSeconds.toFloat(), sample.blendWeight.toFloat())
            }
            val transforms = engine.transformManager
            jointBridge.forEach { (fromEntity, targetEntity) ->
                val from = transforms.getInstance(fromEntity)
                val target = transforms.getInstance(targetEntity)
                transforms.setTransform(target, transforms.getTransform(from, null))
            }
            diagnostics.sampledSeconds = frame.lookaroundElapsedSeconds % sample.durationSeconds
        } else {
            val index = (0 until animator.animationCount).first { animator.getAnimationName(it) == clip }
            animator.applyAnimation(index, 0f)
            diagnostics.sampledSeconds = 0.0
        }
        diagnostics.sampledClip = clip
        val shapeWeights = compositionWeights(composition)
        val correctionWeights = source.corrections.map { correction ->
            val entity = loaded.getFirstEntityByName(correction.bone)
            val transform = engine.transformManager.getTransform(engine.transformManager.getInstance(entity), null)
            correction.weight(matrixQuaternion(transform), composition)
        }
        val weights = (shapeWeights + correctionWeights).map(Double::toFloat).toFloatArray()
        morphEntities.forEach { entity ->
            val renderables = engine.renderableManager
            renderables.setMorphWeights(renderables.getInstance(entity), weights)
        }
        animator.updateBoneMatrices()
        val relief = reliefWeights(composition, source.reliefGrid)
        val tint = bodyTint(frame.layer)
        skinInstances.forEach {
            it.setParameter("reliefWeights", relief[0].toFloat(), relief[1].toFloat(), relief[2].toFloat())
            it.setParameter("tint", tint[0], tint[1], tint[2])
            it.setParameter("tintAmount", if (frame.layer == BodyLayer.Composition) 0f else 0.16f)
        }
        diagnostics.lastMorphWeights = weights.toList()
        diagnostics.layer = frame.layer
        val yaw = frame.yaw.takeIf(Double::isFinite) ?: 0.0
        val target = requireNotNull(diagnostics.orbitTarget)
        val position = bodyOrbitPosition(target, yaw, frame.pitch, frame.distance)
        camera.lookAt(position.x, position.y, position.z, target.x, target.y, target.z)
        diagnostics.orbitPosition = position
        updateLighting(yaw)
        val chain = swapChain ?: return false
        if (!renderer.beginFrame(chain, nanos)) return false
        renderer.render(view)
        renderer.endFrame()
        return true
    }

    fun jointTransformSnapshot(): Map<String, List<Float>> {
        val loaded = asset ?: return emptyMap()
        val source = glb ?: return emptyMap()
        return source.joints.associateWith { name ->
            val entity = loaded.getFirstEntityByName(name)
            engine.transformManager.getTransform(engine.transformManager.getInstance(entity), null).toList()
        }
    }

    private fun setupLighting() {
        val sky = bodyToken("body-light-sky")
        indirectLight = IndirectLight.Builder().irradiance(1, sky).intensity(12_000f).build(engine)
        scene.indirectLight = indirectLight
        val colors = listOf(bodyToken("body-light-key"), bodyToken("body-light-fill"), bodyToken("body-light-rim"))
        val intensities = listOf(65_000f, 16_250f, 42_650f)
        colors.forEachIndexed { index, color ->
            val entity = EntityManager.get().create()
            lightEntities += entity
            LightManager.Builder(LightManager.Type.DIRECTIONAL).color(color[0], color[1], color[2])
                .intensity(intensities[index]).direction(0f, -1f, -1f).castShadows(index == 0)
                .build(engine, entity)
            scene.addEntity(entity)
        }
    }

    private fun updateLighting(yaw: Double) {
        val angle = yaw - sin(yaw) * cos(yaw)
        val directions = listOf(doubleArrayOf(3.8, -4.6, -4.0), doubleArrayOf(-4.0, -0.6, -3.0), doubleArrayOf(-2.5, -1.6, 4.0))
        lightEntities.forEachIndexed { index, entity ->
            val d = directions[index]
            engine.lightManager.setDirection(engine.lightManager.getInstance(entity),
                (d[0] * cos(angle) + d[2] * sin(angle)).toFloat(), d[1].toFloat(),
                (-d[0] * sin(angle) + d[2] * cos(angle)).toFloat())
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        uiHelper.detach()
        asset?.let { scene.removeEntities(it.entities); assetLoader.destroyAsset(it) }
        motion?.let(assetLoader::destroyAsset)
        skinInstances.forEach(engine::destroyMaterialInstance)
        authoredInstances.forEach(engine::destroyMaterialInstance)
        textures.forEach(engine::destroyTexture)
        skinMaterial?.let(engine::destroyMaterial)
        surfaceMaterial?.let(engine::destroyMaterial)
        lightEntities.forEach { scene.remove(it); engine.destroyEntity(it); EntityManager.get().destroy(it) }
        indirectLight?.let(engine::destroyIndirectLight)
        resourceLoader.destroy()
        assetLoader.destroy()
        materialProvider.destroyMaterials()
        materialProvider.destroy()
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        engine.destroyRenderer(renderer)
        engine.flushAndWait()
        engine.destroy()
        BodyFilamentView.liveRenderers.decrementAndGet()
    }
}

private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper()) { "Filament is confined to the Android main thread" }
private fun directBuffer(bytes: ByteArray): ByteBuffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); flip() }

/** Illustration tokens stay fixed across themes and enter Filament in linear RGB. */
private fun bodyTint(layer: BodyLayer) = when (layer) {
    BodyLayer.Composition -> bodyToken("body-composition")
    BodyLayer.Muscle -> bodyToken("body-muscle")
    BodyLayer.Fat -> bodyToken("body-fat")
    BodyLayer.Water -> bodyToken("body-water")
}
private fun bodyToken(name: String): FloatArray {
    val color = GeneratedColors.light.getValue(name)
    fun linear(value: Float) = if (value <= 0.04045f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)
    return floatArrayOf(linear(color.red), linear(color.green), linear(color.blue))
}

private fun matrixQuaternion(matrix: FloatArray): BodyQuaternion {
    val scales = List(3) { column -> sqrt((0..2).sumOf { row -> matrix[column * 4 + row].toDouble().let { it * it } }) }
    val m = List(9) { i -> matrix[(i % 3) * 4 + i / 3] / scales[i % 3] }
    val trace = m[0] + m[4] + m[8]
    return if (trace > 0) {
        val s = sqrt(trace + 1) * 2
        BodyQuaternion((m[7] - m[5]) / s, (m[2] - m[6]) / s, (m[3] - m[1]) / s, s / 4)
    } else if (m[0] > m[4] && m[0] > m[8]) {
        val s = sqrt(1 + m[0] - m[4] - m[8]) * 2
        BodyQuaternion(s / 4, (m[1] + m[3]) / s, (m[2] + m[6]) / s, (m[7] - m[5]) / s)
    } else if (m[4] > m[8]) {
        val s = sqrt(1 + m[4] - m[0] - m[8]) * 2
        BodyQuaternion((m[1] + m[3]) / s, s / 4, (m[5] + m[7]) / s, (m[2] - m[6]) / s)
    } else {
        val s = sqrt(1 + m[8] - m[0] - m[4]) * 2
        BodyQuaternion((m[2] + m[6]) / s, (m[5] + m[7]) / s, s / 4, (m[3] - m[1]) / s)
    }
}
