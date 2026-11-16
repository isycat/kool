package de.fabmax.kool.pipeline.backend.wgpu

import de.fabmax.kool.math.Vec2i

import io.ygdrasil.webgpu.CompositeAlphaMode
import io.ygdrasil.webgpu.GPUCommandEncoder
import io.ygdrasil.webgpu.GPUTexture
import io.ygdrasil.webgpu.GPUTextureFormat
import io.ygdrasil.webgpu.GPUTextureView
import io.ygdrasil.webgpu.SurfaceConfiguration
import kotlin.time.Duration

/** What a surface answers when asked for the texture a frame is drawn into. */
enum class SurfaceAnswer {
    /** A texture, held from now until it is presented or given back. */
    GIVEN,

    /** None: the surface is out of date or lost, and may give one once it is configured again. */
    STALE,

    /**
     * None in the time the GPU's library waits for one, a second on Vulkan: nothing has taken what the surface was
     * given to present. Asked again at once, it is waited on as long again.
     */
    LATE,
}

/**
 * What asking a surface for its textures has come to: how often it was [asked], how often it answered
 * [SurfaceAnswer.STALE] and [SurfaceAnswer.LATE], how often it was [configuredAgain] for answering so, and how long
 * the asks [waited] in all and the [longest] of them.
 */
data class SurfaceAsks(
    val asked: Int = 0,
    val stale: Int = 0,
    val late: Int = 0,
    val configuredAgain: Int = 0,
    val waited: Duration = Duration.ZERO,
    val longest: Duration = Duration.ZERO,
) {
    /** With one more ask, which [answer] came back from after [took]. */
    fun asked(answer: SurfaceAnswer, took: Duration) = copy(
        asked = asked + 1,
        stale = stale + if (answer == SurfaceAnswer.STALE) 1 else 0,
        late = late + if (answer == SurfaceAnswer.LATE) 1 else 0,
        waited = waited + took,
        longest = maxOf(longest, took),
    )
}

expect class WgpuSurface: AutoCloseable {

    val width: UInt
    val height: UInt

    /** [width] and [height] read together: on the desktop the window's size changes on another thread. */
    val size: Vec2i

    val supportedAlphaMode: Set<CompositeAlphaMode>
    val format: GPUTextureFormat

    /** How the surface can present frames, as its capabilities list them; none where it does not say. */
    val presentModes: Set<PresentMode>

    /**
     * Takes the texture the next frame is drawn into, and says whether there is one: a window's surface has none to
     * give while it is out of date, lost or late, and nothing may then be drawn or presented on it.
     */
    fun acquire(): SurfaceAnswer

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