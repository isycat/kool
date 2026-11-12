package de.fabmax.kool.pipeline

data class PipelineConfig(
    val blendMode: BlendMode = BlendMode.BLEND_MULTIPLY_ALPHA,
    val cullMethod: CullMethod = CullMethod.CULL_BACK_FACES,
    val depthTest: DepthCompareOp = DepthCompareOp.LESS_EQUAL,
    val isWriteDepth: Boolean = true,
    val lineWidth: Float = 1f,
    val autoReverseDepthFunc: Boolean = true,
    /**
     * Polygon depth offset of triangles, in units of the depth buffer's smallest step ([depthBias]) and per unit of the
     * triangle's depth slope ([depthBiasSlope]). Positive values move triangles away from the eye, whatever the depth
     * mode. Not applied by the OpenGL backend.
     */
    val depthBias: Float = 0f,
    val depthBiasSlope: Float = 0f
) {
    class Builder {
        private val defaultConfig = PipelineConfig()

        var blendMode: BlendMode = defaultConfig.blendMode
        var cullMethod: CullMethod = defaultConfig.cullMethod
        var depthTest: DepthCompareOp = defaultConfig.depthTest
        var isWriteDepth: Boolean = defaultConfig.isWriteDepth
        var lineWidth: Float = defaultConfig.lineWidth
        var autoReverseDepthFunc: Boolean = defaultConfig.autoReverseDepthFunc
        var depthBias: Float = defaultConfig.depthBias
        var depthBiasSlope: Float = defaultConfig.depthBiasSlope

        fun build() = PipelineConfig(
            blendMode = blendMode,
            cullMethod = cullMethod,
            depthTest = depthTest,
            isWriteDepth = isWriteDepth,
            lineWidth = lineWidth,
            autoReverseDepthFunc = autoReverseDepthFunc,
            depthBias = depthBias,
            depthBiasSlope = depthBiasSlope
        )
    }
}