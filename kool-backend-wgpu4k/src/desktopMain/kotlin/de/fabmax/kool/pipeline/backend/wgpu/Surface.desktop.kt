package de.fabmax.kool.pipeline.backend.wgpu

import de.fabmax.kool.math.Vec2i
import de.fabmax.kool.platform.KoolWindowJvm
import ffi.memoryScope
import io.ygdrasil.webgpu.*
import io.ygdrasil.wgpu.WGPUSurfaceGetCurrentTextureStatus_SuccessOptimal
import io.ygdrasil.wgpu.WGPUSurfaceGetCurrentTextureStatus_SuccessSuboptimal
import io.ygdrasil.wgpu.WGPUSurfaceGetCurrentTextureStatus_Timeout
import io.ygdrasil.wgpu.WGPUSurfaceTexture
import io.ygdrasil.wgpu.wgpuSurfaceGetCurrentTexture
import io.ygdrasil.wgpu.wgpuTextureRelease
import java.lang.foreign.MemorySegment

/**
 * Where the screen pass renders to on the desktop: a window's surface ([WindowTarget]) or, in headless mode, an offscreen
 * texture read back after every frame ([HeadlessTarget]).
 */
internal interface SurfaceTarget : AutoCloseable {
    val window: KoolWindowJvm
    val format: GPUTextureFormat
    val supportedAlphaMode: Set<CompositeAlphaMode>

    /**
     * The size of what frames are presented on, in pixels, now: what the surface must be configured for. A native
     * window's own, where the target can ask it: the window subsystem hears of a new size only later, on its own thread.
     */
    val size: Vec2i get() = window.framebufferSize

    /**
     * Takes the texture the next frame is drawn into, and says whether there is one: a window's surface has none to
     * give while it is out of date (its window changed size since it was configured, or went to another screen), lost,
     * or late.
     */
    fun acquire(): SurfaceAnswer

    /** The texture taken by [acquire]. */
    fun getCurrentTexture(): GPUTexture

    /** Gives back the texture taken by [acquire] without presenting it, where there is one: a frame was not finished. */
    fun discard()
    fun configure(surfaceConfiguration: SurfaceConfiguration, width: UInt, height: UInt)
    fun encodeOutput(encoder: GPUCommandEncoder) { }
    suspend fun present()
}

/**
 * The texture the surface gives to draw the next frame into, or why it gives none now: it is late with it, or out of
 * date or lost in whatever way. Asked of the native library itself: wgpu4k's own call hands back a texture made of a
 * null pointer for such a surface, and the first use of that ends the process (the crash of a window resized quickly
 * on Vulkan, 2026-11-16).
 */
internal fun NativeSurface.currentTexture(): Pair<SurfaceAnswer, GPUTexture?> = memoryScope { scope ->
    val given = WGPUSurfaceTexture.allocate(scope)
    wgpuSurfaceGetCurrentTexture(handler, given)
    val texture = given.texture?.takeIf { it.handler.handler != MemorySegment.NULL }
    val usable = given.status == WGPUSurfaceGetCurrentTextureStatus_SuccessOptimal ||
        given.status == WGPUSurfaceGetCurrentTextureStatus_SuccessSuboptimal
    if (texture != null && usable) return@memoryScope SurfaceAnswer.GIVEN to Texture(texture, "")
    texture?.let(::wgpuTextureRelease)
    (if (given.status == WGPUSurfaceGetCurrentTextureStatus_Timeout) SurfaceAnswer.LATE else SurfaceAnswer.STALE) to null
}

actual class WgpuSurface internal constructor(internal val target: SurfaceTarget) : AutoCloseable {
    actual val width: UInt get() = size.x.coerceAtLeast(0).toUInt()
    actual val height: UInt get() = size.y.coerceAtLeast(0).toUInt()
    actual val size: Vec2i get() = target.size

    actual val supportedAlphaMode: Set<CompositeAlphaMode> get() = target.supportedAlphaMode
    actual val format: GPUTextureFormat get() = target.format

    private var currentTextureView: GPUTextureView? = null

    actual fun acquire(): SurfaceAnswer = target.acquire()

    actual fun getCurrentTexture(): GPUTexture = target.getCurrentTexture()

    actual fun discard() {
        currentTextureView?.close()
        currentTextureView = null
        target.discard()
    }

    actual fun getCurrentTextureView(): GPUTextureView =
        currentTextureView ?: target.getCurrentTexture().createView().also { currentTextureView = it }

    actual fun encodeOutput(encoder: GPUCommandEncoder) = target.encodeOutput(encoder)

    actual suspend fun present() {
        currentTextureView?.close()
        currentTextureView = null
        target.present()
    }

    actual fun configure(surfaceConfiguration: SurfaceConfiguration, size: Vec2i) {
        target.configure(surfaceConfiguration, size.x.toUInt(), size.y.toUInt())
    }

    actual override fun close() = target.close()
}

/**
 * The surface of whatever native window [handleOf] names now, made by [surfaceOf]: an AWT canvas gets another native
 * window when it is taken out of its window and put back, and the surface is then made again and configured as it was.
 */
internal class FollowingTarget(
    override val window: KoolWindowJvm,
    private val handleOf: () -> Long,
    private val sizeOf: (Long) -> Vec2i?,
    private val surfaceOf: (Long) -> NativeSurface,
) : SurfaceTarget {
    private var handle = handleOf()
    private var handler = surfaceOf(handle)
    private var configured: Triple<SurfaceConfiguration, UInt, UInt>? = null
    private var currentTexture: GPUTexture? = null

    /** The native window's own size, where it says it; the size the window subsystem last heard of otherwise. */
    override val size: Vec2i get() = sizeOf(handleOf()) ?: window.framebufferSize

    private fun current(): NativeSurface {
        val now = handleOf()
        if (now != handle) {
            discard()
            handler.close()
            handle = now
            handler = surfaceOf(now)
            configured?.let { (configuration, width, height) -> handler.configure(configuration, width, height) }
        }
        return handler
    }

    override val supportedAlphaMode: Set<CompositeAlphaMode> get() = handler.supportedAlphaMode
    override val format: GPUTextureFormat
        get() = handler.supportedFormats.firstOrNull {
            it.name.contains("8Unorm") && !it.name.contains("Srgb")
        } ?: handler.supportedFormats.first()

    override fun acquire(): SurfaceAnswer {
        if (currentTexture != null) return SurfaceAnswer.GIVEN
        return current().currentTexture().also { (_, texture) -> currentTexture = texture }.first
    }

    override fun getCurrentTexture(): GPUTexture = checkNotNull(currentTexture) { "No texture was acquired for this frame" }

    // A texture released before it is presented is given back to its surface.
    override fun discard() {
        currentTexture?.close()
        currentTexture = null
    }

    override suspend fun present() {
        // Presenting with no texture taken ends the process: only a frame drawn into one is presented.
        val drawn = currentTexture ?: return
        handler.present()
        drawn.close()
        currentTexture = null
    }

    override fun configure(surfaceConfiguration: SurfaceConfiguration, width: UInt, height: UInt) {
        // A surface is never configured while a texture of it is held: the library ends the process on that.
        discard()
        configured = Triple(surfaceConfiguration, width, height)
        current().configure(surfaceConfiguration, width, height)
    }

    override fun close() {
        discard()
        handler.close()
    }
}

/** A window's surface: a GLFW window's. */
internal class WindowTarget(private val handler: NativeSurface, override val window: KoolWindowJvm) : SurfaceTarget {

    override val supportedAlphaMode: Set<CompositeAlphaMode> get() = handler.supportedAlphaMode
    override val format: GPUTextureFormat
        get() = handler.supportedFormats.firstOrNull {
            it.name.contains("8Unorm") && !it.name.contains("Srgb")
        } ?: handler.supportedFormats.first()

    private var currentTexture: GPUTexture? = null

    override fun acquire(): SurfaceAnswer {
        if (currentTexture != null) return SurfaceAnswer.GIVEN
        return handler.currentTexture().also { (_, texture) -> currentTexture = texture }.first
    }

    override fun getCurrentTexture(): GPUTexture = checkNotNull(currentTexture) { "No texture was acquired for this frame" }

    override fun discard() {
        currentTexture?.close()
        currentTexture = null
    }

    override suspend fun present() {
        val drawn = currentTexture ?: return
        handler.present()
        drawn.close()
        currentTexture = null
    }

    override fun configure(surfaceConfiguration: SurfaceConfiguration, width: UInt, height: UInt) {
        discard()
        handler.configure(surfaceConfiguration, width, height)
    }

    override fun close() {
        discard()
        handler.close()
    }
}
