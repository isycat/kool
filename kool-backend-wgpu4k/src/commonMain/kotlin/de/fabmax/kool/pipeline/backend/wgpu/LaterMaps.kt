package de.fabmax.kool.pipeline.backend.wgpu

import io.ygdrasil.webgpu.GPUBuffer

/**
 * Maps buffers for reading without the render thread waiting for the GPU: a map is started, and [poll], called once a
 * frame, hands over each map the GPU has finished since, never waiting for one that has not.
 */
internal expect class LaterMaps(backend: RenderBackendWgpu4k) {
    /** Starts mapping all of [buffer], [size] bytes, for reading; [onMapped] is called from a later [poll], true where it is mapped. */
    fun map(buffer: GPUBuffer, size: Long, onMapped: (Boolean) -> Unit)

    /** Hands over the maps the GPU has finished, without waiting for any. */
    fun poll()
}
