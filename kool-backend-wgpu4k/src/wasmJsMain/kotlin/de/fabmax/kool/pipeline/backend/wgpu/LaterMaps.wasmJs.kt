package de.fabmax.kool.pipeline.backend.wgpu

import de.fabmax.kool.util.BackendScope
import io.ygdrasil.webgpu.GPUBuffer
import io.ygdrasil.webgpu.GPUMapMode
import kotlinx.coroutines.launch

/** The browser maps without blocking: each map is awaited in a coroutine of its own, and nothing is left to poll. */
internal actual class LaterMaps actual constructor(private val backend: RenderBackendWgpu4k) {
    actual fun map(buffer: GPUBuffer, size: Long, onMapped: (Boolean) -> Unit) {
        BackendScope.launch { onMapped(buffer.mapAsync(setOf(GPUMapMode.Read), 0uL, size.toULong()).isSuccess) }
    }

    actual fun poll() { }
}
