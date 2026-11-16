package de.fabmax.kool.pipeline.backend.webgpu

import de.fabmax.kool.pipeline.*
import de.fabmax.kool.util.logE
import de.fabmax.kool.util.logW
import kotlin.js.toList

class WgpuPipelineManager(val backend: RenderBackendWebGpu) {

    private val vertexShaderModules = mutableMapOf<String, UsedShaderModule>()
    private val fragmentShaderModules = mutableMapOf<String, UsedShaderModule>()
    private val computeShaderModules = mutableMapOf<String, UsedShaderModule>()

    /**
     * The GPU's pipelines, one for each different way of drawing or computing: kool pipelines of the same code, layout,
     * state and targets share one, so a shader made again, as for each batch of a model, compiles nothing again. They
     * are kept for the device's life, as are shader modules, though nothing uses them for a while: an app has a bounded
     * few ways of drawing, and one made once (as a warm-up makes them) is never compiled again.
     */
    private val renderPipelines = HashMap<Any, GPURenderPipeline>()
    private val computePipelines = HashMap<String, GPUComputePipeline>()

    /** How many GPU pipelines, and shader modules, have been made since this was made: the GPU compiles each. */
    var pipelinesMade = 0
        private set
    var shaderModulesMade = 0
        private set

    internal fun sharedRenderPipeline(key: Any, make: () -> GPURenderPipeline): GPURenderPipeline =
        renderPipelines.getOrPut(key) {
            pipelinesMade++
            make()
        }

    internal fun sharedComputePipeline(key: String, make: () -> GPUComputePipeline): GPUComputePipeline =
        computePipelines.getOrPut(key) {
            pipelinesMade++
            make()
        }

    fun prepareDrawPipeline(cmd: DrawCommand) {
        cmd.pipeline.getWgpuPipeline().updateGeometry(cmd)
    }

    fun bindDrawPipeline(cmd: DrawCommand, passEncoderState: RenderPassEncoderState): Boolean {
        return cmd.pipeline.getWgpuPipeline().bind(cmd, passEncoderState)
    }

    fun bindComputePipeline(task: ComputePass.Task, passEncoderState: ComputePassEncoderState): Boolean {
        return task.pipeline.getWgpuPipeline().bind(task, passEncoderState)
    }

    private fun DrawPipeline.getWgpuPipeline(): WgpuDrawPipeline {
        (pipelineBackend as WgpuDrawPipeline?)?.let { return it }

        val vertexShader = getOrCreateVertexShaderModule(this)
        val fragmentShader = getOrCreateFragmentShaderModule(this)
        return WgpuDrawPipeline(this, vertexShader, fragmentShader, backend).also { pipelineBackend = it }
    }

    private fun ComputePipeline.getWgpuPipeline(): WgpuComputePipeline {
        (pipelineBackend as WgpuComputePipeline?)?.let { return it }

        val computeShader = getOrCreateComputeShaderModule(this)
        return WgpuComputePipeline(this, computeShader, backend).also { pipelineBackend = it }
    }

    private fun getOrCreateVertexShaderModule(pipeline: DrawPipeline): GPUShaderModule {
        val shaderCode = pipeline.shaderCode as WebGpuShaderCode
        val usedModule = vertexShaderModules.getOrPut(shaderCode.vertexSrc) {
            shaderModulesMade++
            val desc = GPUShaderModuleDescriptor(
                label = "${pipeline.name} vertex shader",
                code = shaderCode.vertexSrc
            )
            val module = backend.device.createShaderModule(desc)
            module.checkErrors("vertex-shader", shaderCode.vertexSrc, pipeline)
            UsedShaderModule(module)
        }
        usedModule.users += pipeline
        return usedModule.shaderModule
    }

    private fun getOrCreateFragmentShaderModule(pipeline: DrawPipeline): GPUShaderModule {
        val shaderCode = pipeline.shaderCode as WebGpuShaderCode
        val usedModule = fragmentShaderModules.getOrPut(shaderCode.fragmentSrc) {
            shaderModulesMade++
            val desc = GPUShaderModuleDescriptor(
                label = "${pipeline.name} fragment shader",
                code = shaderCode.fragmentSrc
            )
            val module = backend.device.createShaderModule(desc)
            module.checkErrors("fragment-shader", shaderCode.fragmentSrc, pipeline)
            UsedShaderModule(module)
        }
        usedModule.users += pipeline
        return usedModule.shaderModule
    }

    private fun getOrCreateComputeShaderModule(pipeline: ComputePipeline): GPUShaderModule {
        val shaderCode = pipeline.shaderCode as WebGpuComputeShaderCode
        val usedModule = computeShaderModules.getOrPut(shaderCode.computeSrc) {
            shaderModulesMade++
            val desc = GPUShaderModuleDescriptor(
                label = "${pipeline.name} compute shader",
                code = shaderCode.computeSrc
            )
            val module = backend.device.createShaderModule(desc)
            module.checkErrors("compute-shader", shaderCode.computeSrc, pipeline)
            UsedShaderModule(module)
        }
        usedModule.users += pipeline
        return usedModule.shaderModule
    }

    private fun GPUShaderModule.checkErrors(stage: String, code: String, pipeline: PipelineBase) {
        getCompilationInfo().then { info ->
            val messages = info.messages.toList()
            if (messages.any { it.type == "error" }) {
                logE { "Errors occurred on compilation of shader ${pipeline.name}:$stage:" }
                messages.filter { it.type == "error" }.forEach {
                    logE { it.message }
                }
                logE { code }
            }
            if (messages.any { it.type == "warning" }) {
                logW { "Warnings occurred on compilation of fragment shader ${pipeline.name}:" }
                messages.filter { it.type == "warning" }.forEach {
                    logW { it.message }
                }
            }
            info
        }
    }

    internal fun removeDrawPipeline(pipeline: WgpuDrawPipeline) {
        pipeline.drawPipeline.pipelineBackend = null
        val shaderCode = pipeline.drawPipeline.shaderCode as WebGpuShaderCode

        vertexShaderModules[shaderCode.vertexSrc]?.let { usedModule ->
            usedModule.users -= pipeline.drawPipeline
        }
        fragmentShaderModules[shaderCode.fragmentSrc]?.let { usedModule ->
            usedModule.users -= pipeline.drawPipeline
        }
    }

    internal fun removeComputePipeline(pipeline: WgpuComputePipeline) {
        pipeline.computePipeline.pipelineBackend = null
        val shaderCode = pipeline.computePipeline.shaderCode as WebGpuComputeShaderCode

        computeShaderModules[shaderCode.computeSrc]?.let { usedModule ->
            usedModule.users -= pipeline.computePipeline
        }
    }

    private class UsedShaderModule(val shaderModule: GPUShaderModule) {
        val users = mutableSetOf<PipelineBase>()
    }
}