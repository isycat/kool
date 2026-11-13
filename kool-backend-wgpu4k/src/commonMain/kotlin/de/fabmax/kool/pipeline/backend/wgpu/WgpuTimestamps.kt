package de.fabmax.kool.pipeline.backend.wgpu

import de.fabmax.kool.util.Releasable
import io.ygdrasil.webgpu.*
import kotlin.math.max

/**
 * GPU timestamps, read back a few frames late and never waited for: each frame's queries are resolved and copied into
 * one of a ring of read buffers, which is mapped without the render thread waiting ([LaterMaps]) and read once the GPU
 * has finished with it. A frame finds no free read buffer only where the GPU is that far behind, and then resolves
 * nothing that frame.
 */
class WgpuTimestamps(val size: Int, val backend: RenderBackendWgpu4k) {

    private var querySet: GPUQuerySet? = null
    private val bytes = size * 8L
    private val resolveBuffer = backend.device.createBuffer(
        BufferDescriptor(
            bytes.toULong(),
            setOf(GPUBufferUsage.QueryResolve, GPUBufferUsage.CopySrc)
        )
    )

    /** A read buffer: free, copied into by a frame not yet submitted, mapping, or mapped and ready to read. */
    private enum class State { FREE, COPIED, MAPPING, MAPPED }

    private inner class ReadBuffer {
        val buffer = backend.device.createBuffer(BufferDescriptor(bytes.toULong(), setOf(GPUBufferUsage.MapRead, GPUBufferUsage.CopyDst)))
        var state = State.FREE
        var lastActive = -1
    }

    private val reads = Array(READ_RING) { ReadBuffer() }
    private val maps = LaterMaps(backend)

    private val slots = Array<QuerySlot?>(size) { null }
    var activeQueries = 0
        private set

    private var lastActive = -1

    fun getQuerySet(): GPUQuerySet? {
        if (!backend.isTimestampQuerySupported) {
            return null
        }
        if (querySet == null) {
            querySet = backend.device.createQuerySet(QuerySetDescriptor(GPUQueryType.Timestamp, size.toUInt()))
        }
        return querySet
    }

    fun createQuery(): QuerySlot? {
        if (!backend.isTimestampQuerySupported) {
            return null
        }

        var slot: QuerySlot? = null
        for (i in slots.indices) {
            if (slots[i] == null) {
                slot = QuerySlot(i)
                slots[i] = slot
                activeQueries++
                lastActive = max(lastActive, i)
                break
            }
        }
        return slot
    }

    /** Resolves this frame's queries into a free read buffer, where one is free. */
    fun resolve(encoder: GPUCommandEncoder) {
        val querySet = getQuerySet() ?: return
        if (activeQueries == 0) return
        val read = reads.firstOrNull { it.state == State.FREE } ?: return
        encoder.resolveQuerySet(querySet, 0u, (lastActive + 1).toUInt(), resolveBuffer, 0uL)
        encoder.copyBufferToBuffer(resolveBuffer, 0uL, read.buffer, 0uL, bytes.toULong())
        read.state = State.COPIED
        read.lastActive = lastActive
    }

    /**
     * After the frame is submitted: starts mapping what it copied, and reads every read buffer the GPU has finished
     * with, waiting for none.
     */
    @OptIn(ExperimentalUnsignedTypes::class)
    fun readTimestamps() {
        for (read in reads) {
            if (read.state == State.COPIED) {
                read.state = State.MAPPING
                maps.map(read.buffer, bytes) { mapped -> read.state = if (mapped) State.MAPPED else State.FREE }
            }
        }
        maps.poll()
        for (read in reads) {
            if (read.state != State.MAPPED) continue
            val decoded = read.buffer.getMappedRange().asUIntArray()
            for (i in 0..minOf(read.lastActive, lastActive)) {
                val slot = slots[i]
                if (slot != null) {
                    val lower = decoded[i * 2]
                    val upper = decoded[i * 2 + 1]
                    slot.latestResult = upper.toLong() shl 32 or lower.toLong()
                }
            }
            read.buffer.unmap()
            read.state = State.FREE
        }
    }

    inner class QuerySlot(val index: Int) : Releasable {
        override var isReleased = false
            private set

        /** Whether the query may be written this frame: always, as each frame's are copied out before the next. */
        val isReady: Boolean
            get() = true
        var latestResult = 0L

        override fun release() {
            slots[index] = null
            if (index == lastActive) {
                lastActive = slots.indices.reversed().firstOrNull { slots[it] != null } ?: -1
            }
            activeQueries--
            isReleased = true
        }
    }

    private companion object {
        /** Read buffers in turn: one being copied into, and the GPU up to two frames behind. */
        const val READ_RING = 3
    }
}
