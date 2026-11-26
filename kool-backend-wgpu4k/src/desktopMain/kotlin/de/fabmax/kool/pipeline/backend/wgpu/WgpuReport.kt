package de.fabmax.kool.pipeline.backend.wgpu

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * wgpu's own count of the objects it holds, by kind, as wgpuGenerateReport tells it: what a leak of command buffers,
 * buffers or bind groups shows in, where the driver's memory grows and kool's counts of buffers and textures do not.
 */
object WgpuReport {
    /** The instance asked: the one the backend made. */
    internal var instance: MemorySegment? = null

    // The kinds in the order WGPUGlobalReport holds them: surfaces, then the hub's.
    private val kinds = listOf(
        "surfaces", "adapters", "devices", "queues", "pipeline layouts", "shader modules", "bind group layouts",
        "bind groups", "command buffers", "render bundles", "render pipelines", "compute pipelines", "pipeline caches",
        "query sets", "buffers", "textures", "texture views", "samplers",
    )

    // Each kind's report: allocated, kept from the user, released from the user, element size; each a size_t.
    private const val FIELDS = 4

    private val generate: MethodHandle? by lazy {
        SymbolLookup.loaderLookup().find("wgpuGenerateReport").map {
            Linker.nativeLinker().downcallHandle(it, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS))
        }.orElse(null)
    }

    /** How many objects of each kind wgpu holds now, the kinds it holds none of left out; empty where it can't say. */
    fun held(): Map<String, Long> {
        val made = instance ?: return emptyMap()
        val call = generate ?: return emptyMap()
        Arena.ofConfined().use { arena ->
            val size = ValueLayout.JAVA_LONG.byteSize()
            val report = arena.allocate(kinds.size * FIELDS * size)
            call.invokeWithArguments(made, report)
            return kinds.withIndex()
                .associate { (i, kind) -> kind to report.get(ValueLayout.JAVA_LONG, i * FIELDS * size) }
                .filterValues { it > 0 }
        }
    }
}
