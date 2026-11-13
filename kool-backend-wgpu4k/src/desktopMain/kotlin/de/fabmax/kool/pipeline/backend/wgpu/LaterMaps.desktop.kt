package de.fabmax.kool.pipeline.backend.wgpu

import ffi.MemoryAllocator
import io.ygdrasil.webgpu.Buffer
import io.ygdrasil.webgpu.Device
import io.ygdrasil.webgpu.GPUBuffer
import io.ygdrasil.wgpu.WGPUBufferMapCallback
import io.ygdrasil.wgpu.WGPUBufferMapCallbackInfo
import io.ygdrasil.wgpu.WGPUCallbackMode_AllowSpontaneous
import io.ygdrasil.wgpu.WGPUMapAsyncStatus_Success
import io.ygdrasil.wgpu.WGPUMapMode_Read
import io.ygdrasil.wgpu.wgpuBufferMapAsync
import io.ygdrasil.wgpu.wgpuDevicePoll

/**
 * wgpu's own maps, started with a callback and handed over by a poll that does not wait: wgpu4k's mapAsync polls the
 * device until it is idle, which stalls the frame, and never returned where timestamps were read every frame.
 */
internal actual class LaterMaps actual constructor(private val backend: RenderBackendWgpu4k) {
    /** A map started: the memory its callback lives in, kept until it is called, and what it said. */
    private class Pending(val memory: MemoryAllocator, val onMapped: (Boolean) -> Unit) {
        @Volatile
        var mapped: Boolean? = null
    }

    private val pending = ArrayList<Pending>()

    actual fun map(buffer: GPUBuffer, size: Long, onMapped: (Boolean) -> Unit) {
        val memory = MemoryAllocator()
        val started = Pending(memory, onMapped)
        val callback = WGPUBufferMapCallback.allocate(memory) { status, _, _, _ ->
            started.mapped = status == WGPUMapAsyncStatus_Success
        }
        val info = WGPUBufferMapCallbackInfo.allocate(memory).apply {
            mode = WGPUCallbackMode_AllowSpontaneous
            this.callback = callback
            userdata2 = callback.handler
        }
        wgpuBufferMapAsync((buffer as Buffer).handler, WGPUMapMode_Read, 0uL, size.toULong(), info)
        pending += started
    }

    actual fun poll() {
        if (pending.isEmpty()) return
        // Runs the callbacks of the maps the GPU has finished; false: returns at once, whatever is still running.
        wgpuDevicePoll((backend.device as Device).handler, false, null)
        var i = 0
        while (i < pending.size) {
            val started = pending[i]
            val mapped = started.mapped
            if (mapped == null) {
                i++
            } else {
                pending.removeAt(i)
                started.memory.close()
                started.onMapped(mapped)
            }
        }
    }
}
