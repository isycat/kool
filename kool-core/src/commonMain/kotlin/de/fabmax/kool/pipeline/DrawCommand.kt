package de.fabmax.kool.pipeline

import de.fabmax.kool.math.Mat4d
import de.fabmax.kool.math.Mat4f
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.MeshInstanceList
import de.fabmax.kool.scene.geometry.IndexedVertexList

class DrawCommand(val queue: DrawQueue, var pipeline: DrawPipeline) {
    var drawGroupId = 0
        private set

    // What it draws, none while it waits in its queue's pool: a command pooled holding the mesh it drew last kept that
    // mesh, its node and its geometry from being let go, however long ago the mesh was removed and released.
    private var drawn: Mesh<*>? = null
    private var vertices: IndexedVertexList<*>? = null

    var mesh: Mesh<*>
        get() = checkNotNull(drawn) { "DrawCommand has no mesh: it is pooled" }
        set(value) {
            drawn = value
        }
    var vertexData: IndexedVertexList<*>
        get() = checkNotNull(vertices) { "DrawCommand has no vertex data: it is pooled" }
        set(value) {
            vertices = value
        }
    var instanceData: MeshInstanceList<*>? = null

    var isActive = true

    /**
     * Single precision model matrix of this command's [mesh].
     */
    val modelMatF: Mat4f get() = mesh.modelMatF

    /**
     * Double precision model matrix of this command's [mesh].
     */
    val modelMatD: Mat4d get() = mesh.modelMatD

    fun setup(mesh: Mesh<*>, vertexData: IndexedVertexList<*>, instanceData: MeshInstanceList<*>?, pipeline: DrawPipeline, drawGroupId: Int) {
        this.mesh = mesh
        this.pipeline = pipeline
        this.drawGroupId = drawGroupId
        this@DrawCommand.vertexData = vertexData
        this@DrawCommand.instanceData = instanceData
        isActive = true
    }

    /** Lets go of what it drew, as it goes back to its queue's pool. */
    internal fun pooled() {
        drawn = null
        vertices = null
        instanceData = null
    }

    fun captureData() {
        mesh.captureBuffer()
        pipeline.captureBuffer()
        pipeline.addUser(mesh)
    }

    fun updatePipelineData() {
        pipeline.updatePipelineData(this)
    }
}