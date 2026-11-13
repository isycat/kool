package de.fabmax.kool.pipeline.backend.wgpu

import io.ygdrasil.webgpu.GPUDevice
import io.ygdrasil.webgpu.GPUTextureDescriptor

interface GPUBackend {

    val device: GPUDevice

    fun createBuffer(descriptor: io.ygdrasil.webgpu.GPUBufferDescriptor, info: String?): GpuBufferWgpu
    fun createTexture(descriptor: GPUTextureDescriptor): WgpuTextureResource

    /** Counts a buffer upload of [bytes] bytes. */
    fun counted(bytes: Long) { }

}