package de.fabmax.kool.pipeline.backend.wgpu

import de.fabmax.kool.platform.KoolWindowJvm
import io.ygdrasil.webgpu.*

/**
 * Where the screen pass renders to on the desktop: a window's surface ([WindowTarget]) or, in headless mode, an offscreen
 * texture read back after every frame ([HeadlessTarget]).
 */
internal interface SurfaceTarget : AutoCloseable {
    val window: KoolWindowJvm
    val format: GPUTextureFormat
    val supportedAlphaMode: Set<CompositeAlphaMode>

    fun getCurrentTexture(): GPUTexture
    fun configure(surfaceConfiguration: SurfaceConfiguration, width: UInt, height: UInt)
    fun encodeOutput(encoder: GPUCommandEncoder) { }
    suspend fun present()
}

actual class WgpuSurface internal constructor(internal val target: SurfaceTarget) : AutoCloseable {
    actual val width: UInt get() = target.window.framebufferSize.x.toUInt()
    actual val height: UInt get() = target.window.framebufferSize.y.toUInt()

    actual val supportedAlphaMode: Set<CompositeAlphaMode> get() = target.supportedAlphaMode
    actual val format: GPUTextureFormat get() = target.format

    private var currentTextureView: GPUTextureView? = null

    actual fun getCurrentTexture(): GPUTexture = target.getCurrentTexture()

    actual fun getCurrentTextureView(): GPUTextureView =
        currentTextureView ?: target.getCurrentTexture().createView().also { currentTextureView = it }

    actual fun encodeOutput(encoder: GPUCommandEncoder) = target.encodeOutput(encoder)

    actual suspend fun present() {
        currentTextureView?.close()
        currentTextureView = null
        target.present()
    }

    actual fun configure(surfaceConfiguration: SurfaceConfiguration) {
        target.configure(surfaceConfiguration, width, height)
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
    private val surfaceOf: (Long) -> NativeSurface,
) : SurfaceTarget {
    private var handle = handleOf()
    private var handler = surfaceOf(handle)
    private var configured: Triple<SurfaceConfiguration, UInt, UInt>? = null
    private var currentTexture: SurfaceTexture? = null

    private fun current(): NativeSurface {
        val now = handleOf()
        if (now != handle) {
            currentTexture?.texture?.close()
            currentTexture = null
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

    override fun getCurrentTexture(): GPUTexture =
        (currentTexture ?: current().getCurrentTexture().also { currentTexture = it }).texture

    override suspend fun present() {
        handler.present()
        currentTexture?.texture?.close()
        currentTexture = null
    }

    override fun configure(surfaceConfiguration: SurfaceConfiguration, width: UInt, height: UInt) {
        configured = Triple(surfaceConfiguration, width, height)
        current().configure(surfaceConfiguration, width, height)
    }

    override fun close() = handler.close()
}

/** A window's surface: a GLFW window's. */
internal class WindowTarget(private val handler: NativeSurface, override val window: KoolWindowJvm) : SurfaceTarget {

    override val supportedAlphaMode: Set<CompositeAlphaMode> get() = handler.supportedAlphaMode
    override val format: GPUTextureFormat
        get() = handler.supportedFormats.firstOrNull {
            it.name.contains("8Unorm") && !it.name.contains("Srgb")
        } ?: handler.supportedFormats.first()

    private var currentTexture: SurfaceTexture? = null

    override fun getCurrentTexture(): GPUTexture =
        (currentTexture ?: handler.getCurrentTexture().also { currentTexture = it }).texture

    override suspend fun present() {
        handler.present()
        currentTexture?.texture?.close()
        currentTexture = null
    }

    override fun configure(surfaceConfiguration: SurfaceConfiguration, width: UInt, height: UInt) {
        handler.configure(surfaceConfiguration, width, height)
    }

    override fun close() = handler.close()
}
