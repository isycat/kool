package de.fabmax.kool.pipeline.backend.wgpu

import de.fabmax.kool.PassData
import de.fabmax.kool.pipeline.ClearColorFill
import de.fabmax.kool.pipeline.ClearColorLoad
import de.fabmax.kool.pipeline.ClearDepthLoad
import de.fabmax.kool.pipeline.FrameCopy
import de.fabmax.kool.math.Vec2i
import de.fabmax.kool.util.releaseDelayed
import io.ygdrasil.webgpu.*

class WgpuScreenPass(backend: RenderBackendWgpu4k, numSamples: Int) :
    WgpuRenderPass(GPUTextureFormat.Depth32Float, numSamples, backend)
{
    private val surface: WgpuSurface
        get() = backend.surface

    override val colorTargetFormats: List<GPUTextureFormat>
        get() = listOf(backend.canvasFormat)

    private var colorTexture: GPUTexture? = null
    private var colorTextureView: GPUTextureView? = null

    private var depthAttachment: GPUTexture? = null
    private var depthAttachmentView: GPUTextureView? = null

    /** The size the depth and multi-sampled attachments were made for: always the acquired surface texture's. */
    private var attachedSize = Vec2i(0, 0)

    // No viewport may reach past the texture the frame draws into.
    override fun viewportLimit() = attachedSize

    suspend fun renderScene(passData: PassData, passEncoderState: RenderPassEncoderState) {
        sizeAttachmentsFor(surface.getCurrentTexture())
        render(passData, passEncoderState)
    }

    /**
     * Makes the depth and multi-sampled attachments again wherever they differ in size from [target], the texture this
     * frame draws into. The window's size is read on another thread and may differ from the size the surface was last
     * configured for: only the acquired texture says what size every attachment of the pass must have.
     */
    private fun sizeAttachmentsFor(target: GPUTexture) {
        val width = target.width
        val height = target.height
        val missing = depthAttachment == null || (numSamples > 1 && colorTexture == null)
        if (missing || attachedSize.x.toUInt() != width || attachedSize.y.toUInt() != height) {
            updateRenderTextures(width, height)
            attachedSize = Vec2i(width.toInt(), height.toInt())
        }
    }

    override fun generateMipLevels(encoder: GPUCommandEncoder) { }

    override fun copy(frameCopy: FrameCopy, encoder: GPUCommandEncoder) {
        val colorDst = if (frameCopy.isCopyColor) frameCopy.colorCopy2d else null
        val depthDst = if (frameCopy.isCopyDepth) frameCopy.depthCopy2d else null

        // With one sample per pixel the pass renders straight into the surface's texture.
        val colorSrc = colorTexture ?: surface.getCurrentTexture()
        val width = colorSrc.width
        val height = colorSrc.height

        var colorDstWgpu: WgpuTextureResource? = null
        var depthDstWgpu: WgpuTextureResource? = null

        colorDst?.let { dst ->
            var copyDstC = (dst.gpuTexture as WgpuTextureResource?)
            if (copyDstC == null || copyDstC.width.toUInt() != width || copyDstC.height.toUInt() != height) {
                copyDstC?.releaseDelayed(1)

                val descriptor = TextureDescriptor(
                    label = colorDst.name,
                    size = Extent3D(width, height),
                    format = backend.canvasFormat,
                    usage = setOf(GPUTextureUsage.CopyDst, GPUTextureUsage.TextureBinding, GPUTextureUsage.RenderAttachment),
                )
                val texResource = backend.createTexture(descriptor)
                copyDstC = texResource
                dst.gpuTexture = copyDstC
            }
            colorDstWgpu = copyDstC
        }

        depthDst?.let { dst ->
            var copyDstD = (dst.gpuTexture as WgpuTextureResource?)
            if (copyDstD == null || copyDstD.width.toUInt() != width || copyDstD.height.toUInt() != height) {
                copyDstD?.releaseDelayed(1)

                val descriptor = TextureDescriptor(
                    label = dst.name,
                    size = Extent3D(width, height),
                    format = depthFormat!!,
                    usage = setOf(GPUTextureUsage.CopyDst, GPUTextureUsage.TextureBinding, GPUTextureUsage.RenderAttachment),
                )
                val texResource = backend.createTexture(descriptor)
                copyDstD = texResource
                dst.gpuTexture = copyDstD
            }
            depthDstWgpu = copyDstD
        }

        if (numSamples > 1 && colorDstWgpu != null) {
            // run an empty render pass, which resolves the multi-sampled color texture into the target capture texture
            val passEncoder = encoder.beginRenderPass(
                colorAttachments = listOf(
                    RenderPassColorAttachment(
                        view = colorTextureView!!,
                        loadOp = GPULoadOp.Load,
                        storeOp = GPUStoreOp.Store,
                        resolveTarget = colorDstWgpu.gpuTexture.createView(),
                    )
                ),
            )
            passEncoder.end()

        } else {
            colorDstWgpu?.let { copyDst ->
                backend.textureLoader.copyTexture2d(colorSrc, copyDst.gpuTexture, 1, encoder)
            }
        }
        depthDstWgpu?.let { copyDst ->
            backend.textureLoader.resolveMultiSampledDepthTexture(depthAttachment!!, copyDst.gpuTexture, encoder)
        }
    }

    override fun beginRenderPass(
        passEncoderState: RenderPassEncoderState,
        forceLoad: Boolean,
        timestampWrites: GPURenderPassTimestampWrites?
    ): GPURenderPassEncoder {
        val renderPass = passEncoderState.renderPass
        val colorLoadOp = when {
            forceLoad -> GPULoadOp.Load
            renderPass.colorAttachments[0].clearColor is ClearColorLoad -> GPULoadOp.Load
            else -> GPULoadOp.Clear
        }
        val clearColor = if (colorLoadOp == GPULoadOp.Load) null else {
            (renderPass.colorAttachments[0].clearColor as? ClearColorFill)?.let { Color(it.clearColor.r.toDouble(), it.clearColor.g.toDouble(), it.clearColor.b.toDouble(), it.clearColor.a.toDouble()) }
        }

        val depthLoadOp = when {
            forceLoad -> GPULoadOp.Load
            renderPass.depthAttachment?.clearDepth == ClearDepthLoad -> GPULoadOp.Load
            else -> GPULoadOp.Clear
        }

        val colors = listOf(
            RenderPassColorAttachment(
                view = colorTextureView ?: surface.getCurrentTextureView(),
                loadOp = colorLoadOp,
                storeOp = GPUStoreOp.Store,
                clearValue = clearColor,
                // A resolve target needs a multi-sampled source: one sample per pixel renders into the surface itself.
                resolveTarget = if (numSamples > 1) surface.getCurrentTextureView() else null
            )
        )
        val depth = RenderPassDepthStencilAttachment(
            view = depthAttachmentView!!,
            depthLoadOp = depthLoadOp,
            depthStoreOp = GPUStoreOp.Store,
            depthClearValue = renderPass.depthMode.far
        )
        return passEncoderState.encoder.beginRenderPass(colors, depth, timestampWrites, renderPass.name)
    }

    private fun updateRenderTextures(width: UInt, height: UInt) {
        colorTexture?.close()
        depthAttachment?.close()

        if (numSamples > 1) {
            val colorDescriptor = TextureDescriptor(
                size = Extent3D(width, height),
                format = backend.canvasFormat,
                usage = setOf(GPUTextureUsage.RenderAttachment, GPUTextureUsage.TextureBinding),
                sampleCount = numSamples.toUInt()
            )
            colorTexture = device.createTexture(colorDescriptor).also {
                colorTextureView = it.createView()
            }
        } else {
            colorTexture = null
            colorTextureView = null
        }

        val depthDescriptor = TextureDescriptor(
            size = Extent3D(width, height),
            format = depthFormat!!,
            usage = setOf(GPUTextureUsage.RenderAttachment, GPUTextureUsage.TextureBinding),
            sampleCount = numSamples.toUInt()
        )
        depthAttachment = device.createTexture(depthDescriptor).also {
            depthAttachmentView = it.createView()
        }
    }

    override fun doRelease() { }
}