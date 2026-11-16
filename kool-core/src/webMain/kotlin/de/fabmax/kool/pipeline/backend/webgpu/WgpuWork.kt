package de.fabmax.kool.pipeline.backend.webgpu

/** Work given to the GPU, counted: how many times data has been given to its buffers, and how many bytes. */
class WgpuWork {
    /** Data given to the GPU's vertex, index, instance and storage buffers. */
    var bufferUploads = 0L
        private set
    var bufferUploadBytes = 0L
        private set

    /** Counts a buffer upload of [bytes] bytes. */
    fun counted(bytes: Long) {
        bufferUploads++
        bufferUploadBytes += bytes
    }
}
