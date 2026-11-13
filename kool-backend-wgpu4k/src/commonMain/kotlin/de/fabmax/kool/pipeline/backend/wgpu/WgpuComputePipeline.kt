package de.fabmax.kool.pipeline.backend.wgpu

import de.fabmax.kool.pipeline.ComputePass
import de.fabmax.kool.pipeline.ComputePipeline
import io.ygdrasil.webgpu.GPUComputePipeline
import io.ygdrasil.webgpu.GPUShaderModule
import io.ygdrasil.webgpu.ProgrammableStage

class WgpuComputePipeline(
    val computePipeline: ComputePipeline,
    private val computeShaderModule: GPUShaderModule,
    backend: RenderBackendWgpu4k,
): WgpuPipeline(computePipeline, backend) {

    // Shared with every compute pipeline of the same code: its layout is made from the code, so theirs are the same.
    private val gpuComputePipeline = backend.pipelineManager.sharedComputePipeline(
        (computePipeline.shaderCode as RenderBackendWgpu4k.WebGpuComputeShaderCode).computeSrc,
        ::createComputePipeline,
    )

    private fun createComputePipeline(): GPUComputePipeline {
        return device.createComputePipeline(
            label = "${computePipeline.name}-layout",
            layout = pipelineLayout,
            compute = ProgrammableStage(
                module = computeShaderModule,
                entryPoint = "computeMain"
            )
        )
    }

    fun bind(task: ComputePass.Task, passEncoderState: ComputePassEncoderState): Boolean {
        val pipelineData = computePipeline.capturedPipelineData
        if (!pipelineData.checkBindings()) {
            return false
        }

        passEncoderState.setPipeline(gpuComputePipeline)
        pipelineData.getOrCreateWgpuData().bind(passEncoderState, 0)
        return true
    }
}