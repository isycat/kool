package de.fabmax.kool.pipeline.backend.wgpu

import de.fabmax.kool.util.Buffer
import de.fabmax.kool.util.Uint8Buffer
import io.ygdrasil.webgpu.ArrayBuffer

expect fun Buffer.asArrayBuffer(block: (ArrayBuffer) -> Unit)
expect fun ArrayBuffer.writeInto(target: Buffer)
@OptIn(ExperimentalUnsignedTypes::class)
expect fun ArrayBuffer.asUIntArray(): UIntArray

/** Writes [rows] rows of [row] bytes each, [padded] bytes apart here, into [target] without their padding. */
expect fun ArrayBuffer.writeRowsInto(target: Uint8Buffer, row: Int, padded: Int, rows: Int)

/** [writeRowsInto] a byte at a time, where no faster copy is had. */
internal fun ArrayBuffer.writeRowsByteByByte(target: Uint8Buffer, row: Int, padded: Int, rows: Int) {
    val all = Uint8Buffer(padded * rows)
    writeInto(all)
    for (r in 0 until rows) {
        for (b in 0 until row) target[r * row + b] = all[r * padded + b]
    }
}
