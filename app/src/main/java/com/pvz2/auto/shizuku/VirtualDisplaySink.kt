package com.pvz2.auto.shizuku

import android.graphics.SurfaceTexture
import android.view.Surface

/**
 * 交给虚拟屏的**显示端** surface。
 *
 * ## 为什么需要这个类（这是我们之前一直走不对的地方）
 *
 * 虚拟屏的 `createVirtualDisplay(..., surface, flags)` 里那个 surface 是
 * **显示端**：系统把你给它的一切画面合成到这块 surface 上。
 *
 * 我们以前直接把 `ImageReader.getSurface()` 传了进去，等于把这块屏的"显示器"
 * 焊死在一个只能被我们读取的缓冲上 —— 没有任何东西"显示"它。表现就是：
 *
 *  - 任务 `displayId` 对了，窗口却留在主屏；
 *  - 注入被 `InputDispatcher` 以 "no touchable window" 丢弃；
 *  - 拿到的帧是残留旧帧（亮度看着非零，其实画面早停了）。
 *
 * 正确做法（照 ShadowAuto）：建屏时给一块**正常可渲染的 surface**，
 * 需要截图时用 `VirtualDisplay.setSurface(ImageReader.surface)` 临时切换，
 * 取完立刻切回来。所以这里用 [SurfaceTexture] 造一块"黑洞"surface：
 * 它能正常接收渲染（GPU 有地方画），我们不需要读它的内容。
 *
 * ## 为什么不直接用 TextureView
 *
 * 那需要界面层配合（要在 UI 线程、要有 View 生命周期），而我们建屏发生在
 * shell 侧服务里。`SurfaceTexture` 不依赖 View，只占一个 GL 纹理槽，
 * 是这个场景下最轻的选择。帧缓冲大小为 0 表示"只接收，不实际分配影像缓冲"，
 * 对我们这种不需要显示内容的用法是最省的。
 */
class VirtualDisplaySink(width: Int, height: Int) : AutoCloseable {

    private val texture = SurfaceTexture(false).apply {
        // 我们从不读这里的内容，所以把缓冲降到最小。
        setDefaultBufferSize(width, height)
    }

    /** 传给 `createVirtualDisplay(...)` 的显示端 surface。 */
    val surface: Surface = Surface(texture)

    override fun close() {
        runCatching { surface.release() }
        runCatching { texture.release() }
    }
}
