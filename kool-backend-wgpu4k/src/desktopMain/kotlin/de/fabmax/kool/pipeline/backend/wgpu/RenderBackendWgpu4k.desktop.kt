package de.fabmax.kool.pipeline.backend.wgpu

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import darwin.CAMetalLayer
import darwin.NSWindow
import de.fabmax.kool.FrameData
import de.fabmax.kool.KoolContext
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.configJvm
import de.fabmax.kool.pipeline.backend.RenderBackendJvm
import de.fabmax.kool.platform.ClientApi
import de.fabmax.kool.platform.Lwjgl3Context
import de.fabmax.kool.platform.OsInfo
import de.fabmax.kool.platform.glfw.GlfwWindow
import de.fabmax.kool.platform.glfw.GlfwWindowSubsystem
import de.fabmax.kool.platform.KoolWindowJvm
import de.fabmax.kool.platform.headless.HeadlessWindow
import de.fabmax.kool.platform.headless.HeadlessWindowSubsystem
import de.fabmax.kool.platform.swing.SwingWindowSubsystem
import de.fabmax.kool.util.logD
import ffi.LibraryLoader
import ffi.NativeAddress
import io.ygdrasil.webgpu.GPUAdapter
import io.ygdrasil.webgpu.NativeSurface
import io.ygdrasil.webgpu.WGPU
import io.ygdrasil.webgpu.WGPU.Companion.createInstance
import io.ygdrasil.wgpu.WGPUSurface
import kotlinx.coroutines.runBlocking
import org.lwjgl.glfw.GLFWNativeCocoa.glfwGetCocoaWindow
import org.lwjgl.glfw.GLFWNativeWayland.glfwGetWaylandDisplay
import org.lwjgl.glfw.GLFWNativeWayland.glfwGetWaylandWindow
import org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window
import org.lwjgl.glfw.GLFWNativeX11.glfwGetX11Display
import org.lwjgl.glfw.GLFWNativeX11.glfwGetX11Window
import org.rococoa.ID
import org.rococoa.Rococoa
import java.lang.foreign.MemorySegment

internal actual fun isRenderBackendWgpu4kSupported(): Boolean = true

internal actual suspend fun createRenderBackendWgpu4k(ctx: KoolContext): RenderBackendWgpu4k {
    LibraryLoader.load()
    val wgpu = createInstance() ?: error("fail to wgpu instance")
    return when (val subsystem = KoolSystem.configJvm.windowSubsystem) {
        is HeadlessWindowSubsystem -> headlessBackend(ctx, wgpu, subsystem)
        is GlfwWindowSubsystem -> windowBackend(ctx, wgpu, subsystem)
        is SwingWindowSubsystem -> canvasBackend(ctx, wgpu, subsystem)
        else -> error("Wgpu backend requires the GLFW, the Swing or the headless window subsystem")
    }
}

private suspend fun windowBackend(ctx: KoolContext, wgpu: WGPU, glfw: GlfwWindowSubsystem): RenderBackendWgpu4k {
    val glfwWindow = glfw.createWindow(ClientApi.UNMANAGED, null, ctx as Lwjgl3Context) as GlfwWindow
    glfwWindow.setFullscreen(KoolSystem.configJvm.isFullscreen)

    // make the window visible
    if (KoolSystem.configJvm.showWindowOnStart) {
        glfwWindow.setVisible(true)
    }

    val nativeSurface = wgpu.getNativeSurface(glfwWindow)
    val adapter = wgpu.requestAdapter(nativeSurface)
        ?: error("fail to get adapter")

    // Get supported format and opacity on surface
    nativeSurface.computeSurfaceCapabilities(adapter)

    return DesktopRenderBackendWgpu4kWebGpu(ctx, WgpuSurface(WindowTarget(nativeSurface, glfwWindow)), KoolSystem.configJvm.numSamples) { adapter }
}

/**
 * Renders into an AWT canvas the application provides ([SwingWindowSubsystem.providedCanvas]), which must be showing:
 * its native window is the surface, so frames are presented by the GPU with nothing read back.
 */
private suspend fun canvasBackend(ctx: KoolContext, wgpu: WGPU, swing: SwingWindowSubsystem): RenderBackendWgpu4k {
    val canvas = checkNotNull(swing.providedCanvas) { "The wgpu backend renders into a canvas the application provides" }
    check(OsInfo.os == OsInfo.OS.WINDOWS) { "The wgpu backend renders into an AWT canvas on Windows only" }
    check(canvas.isDisplayable) { "The canvas has no native window yet: add it to a showing window first" }
    val window = swing.createWindow(ClientApi.UNMANAGED, null, ctx as Lwjgl3Context)
    val hinstance = Kernel32.INSTANCE.GetModuleHandle(null).pointer.toNativeAddress()
    var adapter: io.ygdrasil.webgpu.Adapter? = null
    val target = FollowingTarget(window, { Pointer.nativeValue(Native.getComponentPointer(canvas)) }) { hwnd ->
        val surface = wgpu.getSurfaceFromWindows(hinstance, hwnd.toNativeAddress()) ?: error("fail to get surface of the canvas")
        val compatible = adapter ?: runBlocking { wgpu.requestAdapter(surface) }.also { adapter = it } ?: error("fail to get adapter")
        // Get supported format and opacity on surface
        surface.computeSurfaceCapabilities(compatible)
        surface
    }
    return DesktopRenderBackendWgpu4kWebGpu(ctx, WgpuSurface(target), KoolSystem.configJvm.numSamples) { checkNotNull(adapter) }
}

/** Renders without a window: into an offscreen texture, read back for the headless subsystem's listeners. */
private fun headlessBackend(ctx: KoolContext, wgpu: WGPU, subsystem: HeadlessWindowSubsystem): RenderBackendWgpu4k {
    val window = subsystem.createWindow(ClientApi.UNMANAGED, null, ctx as Lwjgl3Context) as HeadlessWindow
    lateinit var backend: RenderBackendWgpu4k
    val target = HeadlessTarget(window) { backend.device }
    backend = DesktopRenderBackendWgpu4kWebGpu(ctx, WgpuSurface(target), KoolSystem.configJvm.numSamples) {
        // No surface to be compatible with: any adapter that can render will do.
        wgpu.requestAdapter(NativeSurface(WGPUSurface(NativeAddress(MemorySegment.NULL)))) ?: error("no WebGPU adapter on this machine")
    }
    return backend
}

internal class DesktopRenderBackendWgpu4kWebGpu(
    ctx: KoolContext,
    surface: WgpuSurface,
    numSamples: Int,
    adapterProvider: suspend () -> GPUAdapter
) : RenderBackendWgpu4k(ctx, surface, numSamples, adapterProvider), RenderBackendJvm {
    override val window: KoolWindowJvm
        get() = surface.target.window

    override val isAsyncRendering: Boolean = KoolSystem.configJvm.asyncSceneUpdate

    override fun renderFrame(frameData: FrameData, ctx: KoolContext) {
        runBlocking { renderFrameSuspending(frameData, ctx) }
    }
}

private fun WGPU.getNativeSurface(window: GlfwWindow): NativeSurface = when (OsInfo.os) {
    OsInfo.OS.LINUX -> when {
        glfwGetWaylandWindow(window.windowHandle) == 0L -> {
            logD { "Get X11 surface" }
            val display = glfwGetX11Display().toNativeAddress()
            val x11Window = glfwGetX11Window(window.windowHandle).toULong()
            getSurfaceFromX11Window(display, x11Window) ?: error("fail to get surface on Linux")
        }
        else -> {
            logD { "Get wayland surface" }
            val display = glfwGetWaylandDisplay().toNativeAddress()
            val waylandWindow = glfwGetWaylandWindow(window.windowHandle).toNativeAddress()
            getSurfaceFromWaylandWindow(display, waylandWindow)
        }
    }

    OsInfo.OS.WINDOWS -> {
        val hwnd = glfwGetWin32Window(window.windowHandle).toNativeAddress()
        val hinstance = Kernel32.INSTANCE.GetModuleHandle(null).pointer.toNativeAddress()
        getSurfaceFromWindows(hinstance, hwnd) ?: error("fail to get surface on Windows")
    }

    OsInfo.OS.MACOS_X -> {
        val nsWindowPtr = glfwGetCocoaWindow(window.windowHandle)
        val nswindow = Rococoa.wrap(ID.fromLong(nsWindowPtr), NSWindow::class.java)
        nswindow.contentView()?.setWantsLayer(true)
        val layer = CAMetalLayer.layer()
        nswindow.contentView()?.setLayer(layer.id().toLong().toPointer())
        getSurfaceFromMetalLayer(layer.id().toLong().toNativeAddress())
    }

    OsInfo.OS.UNKNOWN -> error("unsupported OS: ${OsInfo.os}")
} ?: error("fail to get surface")

private fun Long.toPointer(): Pointer = Pointer(this)

fun Pointer.toNativeAddress() = let { MemorySegment.ofAddress(Pointer.nativeValue(this)) }
    .let { NativeAddress(it) }

fun Long.toNativeAddress() = let { MemorySegment.ofAddress(it) }
    .let { NativeAddress(it) }