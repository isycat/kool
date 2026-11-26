package de.fabmax.kool.modules.ksl

import de.fabmax.kool.KoolSystem
import de.fabmax.kool.modules.ksl.lang.KslProgram
import de.fabmax.kool.math.Vec3i
import de.fabmax.kool.pipeline.BindGroupLayouts
import de.fabmax.kool.pipeline.ComputePass
import de.fabmax.kool.pipeline.ComputeShaderCode
import de.fabmax.kool.pipeline.ComputePipeline
import de.fabmax.kool.pipeline.ComputeShader
import de.fabmax.kool.util.logW

fun KslComputeShader(
    name: String,
    sharedBy: Any? = null,
    block: KslProgram.() -> Unit
): KslComputeShader {
    val shader = KslComputeShader(name, sharedBy)
    shader.program.apply {
        block()
    }
    return shader
}

/**
 * A compute shader of a KSL program. Shaders made with the same [sharedBy] key must be of the same program: the first one's
 * layout and generated code are kept and given to the others, which then generate nothing, as a renderer making many
 * shaders of one program, each with its own bindings, would otherwise generate the same code each time.
 */
open class KslComputeShader(name: String, private val sharedBy: Any? = null) : ComputeShader(name) {

    val program = KslProgram(name)

    override fun createPipeline(computePass: ComputePass): ComputePipeline {
        val made = sharedBy?.let { key -> generated[key] }
        if (made != null) {
            return ComputePipeline(name, made.layouts, made.workGroupSize) { made.code }
        }
        return generate().also { pipeline ->
            sharedBy?.let { key -> generated[key] = Generated(pipeline.bindGroupLayouts, pipeline.workGroupSize, pipeline.shaderCode) }
        }
    }

    private fun generate(): ComputePipeline {
        programsGenerated++
        val computeStage = program.computeStage
        checkNotNull(computeStage) {
            "KslProgram computeStage is missing (a valid KslComputeShader needs a computeStage)"
        }
        if (program.vertexStage != null || program.fragmentStage != null) {
            logW { "KslProgram has a vertex or fragment stage defined, although it is used as a compute shader. Vertex and fragment stages are ignored." }
        }

        // prepare shader model for generating source code, also updates program dependencies (e.g. which
        // uniform is used by which shader stage)
        program.prepareGenerate()

        return ComputePipeline(
            name = name,
            bindGroupLayouts = program.makeBindGroupLayout(isComputePipeline = true),
            workGroupSize = computeStage.workGroupSize,
            shaderCodeGenerator = { KoolSystem.requireContext().backend.generateKslComputeShader(this, it) }
        )
    }

    private class Generated(val layouts: BindGroupLayouts, val workGroupSize: Vec3i, val code: ComputeShaderCode)

    companion object {
        /** The layout and code first generated for each [sharedBy] key: pipelines are made on the render thread alone. */
        private val generated = HashMap<Any, Generated>()

        /** How many compute programs have had their code generated. */
        var programsGenerated = 0
            private set
    }

    override fun pipelineCreated(pipeline: ComputePipeline) {
        super.pipelineCreated(pipeline)
        pipeline.onUpdatePipelineData += {
            for (i in program.shaderListeners.indices) {
                program.shaderListeners[i].onUpdateComputeData(it)
            }
        }
        program.shaderListeners.forEach { it.onShaderCreated(this) }
    }
}
