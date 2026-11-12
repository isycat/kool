package de.fabmax.kool.pipeline.backend.wgpu

import io.ygdrasil.webgpu.*

actual class WgpuSurface(private val handler: WGPUCanvasContext) : AutoCloseable {
    actual val width: UInt
        get() = handler.canvas.castAs<HTMLCanvasElement>().width.asUInt()
    actual val height: UInt
        get() = handler.canvas.castAs<HTMLCanvasElement>().height.asUInt()

    actual val supportedAlphaMode: Set<CompositeAlphaMode> =
        setOf(CompositeAlphaMode.Opaque, CompositeAlphaMode.Premultiplied)

    actual val format: GPUTextureFormat
        get() = navigator.gpu?.getPreferredCanvasFormat()
            ?.let { GPUTextureFormat.of(it) ?: error("Unsupported surface format: $it") }
            ?: error("WebGPU not supported")

    actual fun getCurrentTexture(): GPUTexture = Texture(handler.getCurrentTexture(), canBeDestroy = false)

    actual fun getCurrentTextureView(): GPUTextureView = getCurrentTexture().createView()

    actual fun encodeOutput(encoder: GPUCommandEncoder) { /* the canvas shows the frame */ }

    actual suspend fun present() { /* does not exists on Web */ }

    actual fun configure(surfaceConfiguration: SurfaceConfiguration) {
        handler.configure(map(surfaceConfiguration))
    }

    actual override fun close() { /* does not exists on Web */ }
}

private fun map(input: SurfaceConfiguration) = createJsObject<WGPUCanvasConfiguration>().apply {
    device = (input.device as Device).handler
    format = input.format.value
    usage = input.usage.toFlagInt().asJsNumber()
    viewFormats = input.viewFormats.mapJsArray { it.value.asJsString().castAs() }
    colorSpace = input.colorSpace.value.asJsString().castAs()
    toneMapping = createJsObject<WGPUCanvasToneMapping>().apply {
        // GPUCanvasToneMappingMode.Standard is the default value on specification, should we allow to use extends for HDR ?
        mode = GPUCanvasToneMappingMode.Standard.value.asJsString().castAs()
    }
    alphaMode = input.alphaMode.value.asJsString().castAs()
}