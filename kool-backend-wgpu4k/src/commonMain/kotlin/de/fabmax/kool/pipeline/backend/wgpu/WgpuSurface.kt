package de.fabmax.kool.pipeline.backend.wgpu

import de.fabmax.kool.math.Vec2i

import io.ygdrasil.webgpu.CompositeAlphaMode
import io.ygdrasil.webgpu.GPUCommandEncoder
import io.ygdrasil.webgpu.GPUTexture
import io.ygdrasil.webgpu.GPUTextureFormat
import io.ygdrasil.webgpu.GPUTextureView
import io.ygdrasil.webgpu.SurfaceConfiguration

expect class WgpuSurface: AutoCloseable {

    val width: UInt
    val height: UInt

    /** [width] and [height] read together: on the desktop the window's size changes on another thread. */
    val size: Vec2i

    val supportedAlphaMode: Set<CompositeAlphaMode>
    val format: GPUTextureFormat

    /**
     * Takes the texture the next frame is drawn into, and says whether there is one: a window's surface has none to
     * give while it is out of date, lost or late, and nothing may then be drawn or presented on it.
     */
    fun acquire(): Boolean

    /** The texture the screen pass renders this frame's image into: the one taken by [acquire]. */
    fun getCurrentTexture(): GPUTexture

    /** Gives back the texture taken by [acquire] without presenting it, where there is one: its frame was not finished. */
    fun discard()

    fun getCurrentTextureView(): GPUTextureView

    /** Records whatever this surface does with the finished frame before it is submitted: a readback in headless mode. */
    fun encodeOutput(encoder: GPUCommandEncoder)

    suspend fun present()

    /** Configures the surface's textures for [size]; a canvas on the web takes its own size instead. */
    fun configure(surfaceConfiguration: SurfaceConfiguration, size: Vec2i)

    override fun close()
}