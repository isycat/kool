package de.fabmax.kool.pipeline.backend.wgpu

import de.fabmax.kool.math.Vec2i
import de.fabmax.kool.pipeline.BufferedImageData2d
import de.fabmax.kool.platform.KoolWindowJvm
import de.fabmax.kool.platform.headless.HeadlessFrame
import de.fabmax.kool.platform.headless.HeadlessPixelFormat
import de.fabmax.kool.platform.headless.HeadlessWindow
import de.fabmax.kool.util.Time
import io.ygdrasil.webgpu.*
import kotlinx.coroutines.CompletableDeferred
import java.lang.foreign.MemorySegment

/**
 * The screen pass's target in headless mode: an offscreen texture in the requested pixel format, copied after every
 * frame somebody asked for into one of a ring of host-readable buffers. A frame rendered synchronously
 * ([de.fabmax.kool.platform.headless.HeadlessWindowSubsystem.isSyncReadback]) is mapped and delivered before the
 * render call returns; otherwise it is delivered with the next frame, so its copy runs while that frame renders. The
 * listeners read the mapped buffer itself: no copy on the host.
 */
internal class HeadlessTarget(private val headless: HeadlessWindow, private val device: () -> GPUDevice) : SurfaceTarget {
    private val subsystem = headless.subsystem

    override val window: KoolWindowJvm get() = headless

    override val format: GPUTextureFormat = when (subsystem.pixelFormat) {
        HeadlessPixelFormat.RGBA8 -> GPUTextureFormat.RGBA8Unorm
        HeadlessPixelFormat.BGRA8 -> GPUTextureFormat.BGRA8Unorm
    }

    override val supportedAlphaMode: Set<CompositeAlphaMode> = setOf(CompositeAlphaMode.Opaque)

    private var texture: GPUTexture? = null
    private var slots: List<Slot> = emptyList()
    private var next = 0

    /** How many frames have been copied to the host since this target was made. */
    var framesCopied = 0
        private set

    /** The size the target was last configured for: as a window's surface does, its texture has that size, whatever the window's is now. */
    private var configuredSize = headless.framebufferSize

    override fun getCurrentTexture(): GPUTexture {
        val (width, height) = configuredSize
        val kept = texture?.takeIf { it.width.toInt() == width && it.height.toInt() == height }
        return kept ?: device().createTexture(
            TextureDescriptor(
                label = "headless-output",
                size = Extent3D(width.toUInt(), height.toUInt()),
                format = format,
                usage = setOf(GPUTextureUsage.RenderAttachment, GPUTextureUsage.TextureBinding, GPUTextureUsage.CopySrc),
            )
        ).also {
            texture?.close()
            texture = it
        }
    }

    override fun configure(surfaceConfiguration: SurfaceConfiguration, width: UInt, height: UInt) {
        configuredSize = Vec2i(width.toInt(), height.toInt())
    }

    override fun encodeOutput(encoder: GPUCommandEncoder) {
        if (!subsystem.isFrameRequested) return
        val source = getCurrentTexture()
        val (width, height) = source.width.toInt() to source.height.toInt()
        val rowStride = alignedRow(width)
        if (slots.firstOrNull()?.let { it.rowStride != rowStride || it.height != height } != false) {
            slots.forEach { it.release() }
            slots = List(RING) { Slot(device().createBuffer(
                BufferDescriptor(
                    label = "headless-readback-$it",
                    size = (rowStride.toLong() * height).toULong(),
                    usage = setOf(GPUBufferUsage.MapRead, GPUBufferUsage.CopyDst),
                )
            ), width, height, rowStride) }
        }
        val slot = slots[next]
        check(slot.pending == null) { "readback slot $next is still pending" }
        next = (next + 1) % RING
        encoder.copyTextureToBuffer(
            source = TexelCopyTextureInfo(source),
            destination = TexelCopyBufferInfo(buffer = slot.buffer, bytesPerRow = rowStride.toUInt(), rowsPerImage = height.toUInt()),
            copySize = Extent3D(width.toUInt(), height.toUInt()),
        )
        slot.pending = Pending(Time.frameCount, subsystem.isSyncReadback, subsystem.takePendingCaptures())
    }

    override suspend fun present() {
        // Frames are delivered in the order they were rendered: every earlier one first, then this one if it is wanted now.
        val pending = slots.filter { it.pending != null }.sortedBy { it.pending!!.frameCount }
        val current = pending.lastOrNull()
        pending.forEach { slot -> if (slot !== current || slot.pending!!.isSync) deliver(slot) }
    }

    private suspend fun deliver(slot: Slot) {
        val pending = checkNotNull(slot.pending)
        slot.pending = null
        val size = slot.rowStride.toLong() * slot.height
        slot.buffer.mapAsync(setOf(GPUMapMode.Read), 0u, size.toULong()).getOrThrow()
        val mapped = slot.buffer.getMappedRange(0u, size.toULong())
        val pixels = MemorySegment.ofAddress(mapped.rawPointer.toLong()).reinterpret(size).asByteBuffer().asReadOnlyBuffer()
        try {
            framesCopied++
            subsystem.deliverFrame(
                HeadlessFrame(slot.width, slot.height, subsystem.pixelFormat, pixels, pending.frameCount, slot.rowStride),
                pending.captures,
            )
        } finally {
            slot.buffer.unmap()
        }
    }

    override fun close() {
        slots.forEach { it.release() }
        slots = emptyList()
        texture?.close()
        texture = null
    }

    private class Pending(val frameCount: Int, val isSync: Boolean, val captures: List<CompletableDeferred<BufferedImageData2d>>)

    private class Slot(val buffer: GPUBuffer, val width: Int, val height: Int, val rowStride: Int) {
        var pending: Pending? = null

        fun release() {
            pending?.captures?.forEach { it.completeExceptionally(IllegalStateException("headless output was resized or closed")) }
            buffer.close()
        }
    }

    private companion object {
        /** Frames in flight: one being rendered, one being copied, one being read. */
        const val RING = 3

        /** WebGPU copies texture rows into buffers this many bytes aligned. */
        const val ROW_ALIGNMENT = 256

        fun alignedRow(width: Int): Int = (width * HeadlessFrame.BYTES_PER_PIXEL + ROW_ALIGNMENT - 1) / ROW_ALIGNMENT * ROW_ALIGNMENT
    }
}
