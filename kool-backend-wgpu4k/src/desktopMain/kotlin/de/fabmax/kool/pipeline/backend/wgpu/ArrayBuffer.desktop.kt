package de.fabmax.kool.pipeline.backend.wgpu

import de.fabmax.kool.util.*
import io.ygdrasil.webgpu.ArrayBuffer
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

actual fun ArrayBuffer.writeInto(target: Buffer) {
    target.asNioBuffer()
        .let(MemorySegment::ofBuffer)
        .copyFrom(asArrayBuffer())
}

/** Each row copied whole, memory to memory: a 4K picture's rows a byte at a time held the frames' thread for tens of ms. */
actual fun ArrayBuffer.writeRowsInto(target: Uint8Buffer, row: Int, padded: Int, rows: Int) {
    val from = asArrayBuffer()
    val into = MemorySegment.ofBuffer(target.asNioBuffer())
    for (r in 0 until rows) MemorySegment.copy(from, r.toLong() * padded, into, r.toLong() * row, row.toLong())
}

actual fun Buffer.asArrayBuffer(block: (ArrayBuffer) -> Unit) {
    this.asNioBuffer()
        .asArrayBuffer(block)
}

fun java.nio.Buffer.asArrayBuffer(block: (ArrayBuffer) -> Unit) {
    asArrayBuffer()
        .also { block(it) }
}

@OptIn(ExperimentalUnsignedTypes::class)
actual fun ArrayBuffer.asUIntArray(): UIntArray = asMemorySegment()
    .toArray(ValueLayout.JAVA_INT)
    .asUIntArray()

private fun ArrayBuffer.asMemorySegment(): MemorySegment = MemorySegment.ofAddress(rawPointer.toLong())
    .reinterpret(size.toLong())

private fun java.nio.Buffer.asArrayBuffer(): ArrayBuffer = this.let(MemorySegment::ofBuffer)
    .let { ArrayBuffer(it.address().toULong(), it.byteSize().toULong()) }

private fun Buffer.asNioBuffer(): java.nio.Buffer = when (this) {
    is Float32BufferImpl -> this.getRawBuffer()
    is Int32BufferImpl -> this.getRawBuffer()
    is MixedBufferImpl -> this.getRawBuffer()
    is Uint16BufferImpl -> this.getRawBuffer()
    is Uint8BufferImpl -> this.getRawBuffer()
    else -> error("Unsupported buffer type ${this::class.simpleName}")
}

private fun ArrayBuffer.asArrayBuffer() = MemorySegment
    .ofAddress(rawPointer.toLong())
    .reinterpret(size.toLong())