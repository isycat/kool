package de.fabmax.kool.pipeline.backend.wgpu

import io.ygdrasil.webgpu.CompositeAlphaMode
import io.ygdrasil.webgpu.GPUCommandEncoder
import io.ygdrasil.webgpu.GPUTexture
import io.ygdrasil.webgpu.GPUTextureFormat
import io.ygdrasil.webgpu.GPUTextureView
import io.ygdrasil.webgpu.SurfaceConfiguration

expect class WgpuSurface: AutoCloseable {

    val width: UInt
    val height: UInt

    val supportedAlphaMode: Set<CompositeAlphaMode>
    val format: GPUTextureFormat

    /** The texture the screen pass renders this frame's image into. */
    fun getCurrentTexture(): GPUTexture

    fun getCurrentTextureView(): GPUTextureView

    /** Records whatever this surface does with the finished frame before it is submitted: a readback in headless mode. */
    fun encodeOutput(encoder: GPUCommandEncoder)

    suspend fun present()

    fun configure(surfaceConfiguration: SurfaceConfiguration)

    override fun close()
}