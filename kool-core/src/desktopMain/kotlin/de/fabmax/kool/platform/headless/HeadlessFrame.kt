package de.fabmax.kool.platform.headless

import de.fabmax.kool.pipeline.BufferedImageData2d
import de.fabmax.kool.pipeline.TexFormat
import de.fabmax.kool.util.Uint8Buffer
import de.fabmax.kool.util.Uint8BufferImpl
import java.nio.ByteBuffer

/**
 * Pixel format of frames rendered in headless mode. Both formats use 8 bits per channel and contain sRGB-encoded
 * (i.e. display-ready) color values.
 */
enum class HeadlessPixelFormat {
    /**
     * Byte order R, G, B, A. Matches [TexFormat.RGBA] and is the most convenient format for writing image files.
     */
    RGBA8,

    /**
     * Byte order B, G, R, A. This is the native format of many UI toolkits (AWT `TYPE_INT_ARGB`, Skia `N32` on
     * little-endian platforms, JavaFX `ByteBgra`), using it avoids a conversion on the receiving side.
     */
    BGRA8,
}

/**
 * Determines how frames are driven in headless mode.
 */
sealed interface HeadlessFrameLoop {
    /**
     * Frames are only rendered on request via [HeadlessWindowSubsystem.renderFrame] /
     * [HeadlessWindowSubsystem.captureFrame]. Rendering is synchronous: The output of a frame is available when
     * the render call returns. Intended for tests.
     */
    data object Manual : HeadlessFrameLoop

    /**
     * Frames are rendered continuously by the render loop started with `KoolContext.run()` (e.g. via
     * `KoolApplication { }`), limited to [maxFps]. Frame outputs are read back asynchronously without stalling the
     * render loop. Intended for embedding kool into other UI frameworks.
     */
    data class Continuous(val maxFps: Int = 60) : HeadlessFrameLoop
}

/**
 * A rendered frame passed to frame listeners registered via [HeadlessWindowSubsystem.onFrame].
 *
 * [pixels] directly references GPU readback memory and is only valid during the listener call. Its content must
 * be copied (or converted via [toImageData]) if it is needed afterward.
 */
class HeadlessFrame(
    val width: Int,
    val height: Int,
    val format: HeadlessPixelFormat,

    /**
     * Pixel data, rows are ordered top to bottom, [rowStride] bytes apart.
     */
    val pixels: ByteBuffer,

    /**
     * Value of `Time.frameCount` when this frame was rendered.
     */
    val frameCount: Int,

    /**
     * Number of bytes from the start of one pixel row to the next: [width] times [BYTES_PER_PIXEL], or more where the
     * backend pads its rows (WebGPU copies rows 256 bytes aligned).
     */
    val rowStride: Int = width * BYTES_PER_PIXEL,
) {

    /**
     * Frame output is opaque, i.e. alpha values should be ignored. Blending can leave alpha values < 1 in the
     * framebuffer, which have no meaning for the displayed image.
     */
    val isOpaque: Boolean get() = true

    /**
     * Copies this frame into a new [BufferedImageData2d] with [TexFormat.RGBA] format. Alpha values are set to 255.
     */
    fun toImageData(): BufferedImageData2d {
        val numPixels = width * height
        val data = Uint8Buffer(numPixels * BYTES_PER_PIXEL) as Uint8BufferImpl
        data.useRaw { dst ->
            val src = pixels.duplicate()
            when (format) {
                HeadlessPixelFormat.RGBA8 -> {
                    for (i in 0 until numPixels) {
                        val o = i * BYTES_PER_PIXEL
                        val s = sourceOffset(i)
                        dst.put(o, src.get(s))
                        dst.put(o + 1, src.get(s + 1))
                        dst.put(o + 2, src.get(s + 2))
                        dst.put(o + 3, OPAQUE_ALPHA)
                    }
                }
                HeadlessPixelFormat.BGRA8 -> {
                    for (i in 0 until numPixels) {
                        val o = i * BYTES_PER_PIXEL
                        val s = sourceOffset(i)
                        dst.put(o, src.get(s + 2))
                        dst.put(o + 1, src.get(s + 1))
                        dst.put(o + 2, src.get(s))
                        dst.put(o + 3, OPAQUE_ALPHA)
                    }
                }
            }
        }
        return BufferedImageData2d(data, width, height, TexFormat.RGBA, id = "headless-frame:$frameCount")
    }

    private fun sourceOffset(pixel: Int): Int = (pixel / width) * rowStride + (pixel % width) * BYTES_PER_PIXEL

    companion object {
        const val BYTES_PER_PIXEL = 4
        private const val OPAQUE_ALPHA = 255.toByte()
    }
}
