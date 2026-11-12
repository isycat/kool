package de.fabmax.kool.pipeline.backend

import de.fabmax.kool.KoolContext

interface BackendProvider {
    val displayName: String

    /**
     * Whether the backend can render without a window, in headless mode (`HeadlessWindowSubsystem` on the desktop):
     * into an offscreen image whose frames it hands to the subsystem.
     */
    val isHeadlessCapable: Boolean get() = false

    suspend fun createBackend(ctx: KoolContext): Result<RenderBackend>
}