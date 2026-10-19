// 虚拟屏的采集端：在 NDK 层建一块 AImageReader，把它的 window 包成 Java Surface。
//
// ## 为什么必须走 native（这是本项目绕了很久才定位的关键）
//
// Java 层的 `ImageReader.getSurface()` 和 `SurfaceTexture` 都能拿到一个 Surface，
// 但它们的 **buffer usage 不满足 WMS 对「可信显示端」的要求**。把这种 Surface
// 交给 `createVirtualDisplay()` 之后：
//
//   - 屏能建出来，任务 displayId 也对；
//   - 但屏被标成 untrusted —— `setDisplayImePolicy` 直接报
//     `Attempted to set IME policy to an untrusted virtual display`；
//   - 窗口始终留在 display 0，注入被 InputDispatcher 以
//     `no touchable window ... in display N` 丢弃。
//
// 换成 NDK 的 `AImageReader_newWithUsage(..., CPU_READ_OFTEN | GPU_SAMPLED_IMAGE, ...)`
// 再用 `ANativeWindow_toSurface()` 拿到 Surface，才被系统当作正常显示端。
//
// 做法参照 MaaFwApp 的 `native/bridge_capture.cpp`，只保留我们需要的部分
// （它还有预览转发、帧计时等，我们暂时不用）。

#include <jni.h>
#include <android/log.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>

// memcpy 需要它；clang 不会隐式带上。
#include <cstring>

#define LOG_TAG "Pvz2Capture"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Capturer {
    AImageReader *reader = nullptr;
    ANativeWindow *window = nullptr;
    int width = 0;
    int height = 0;
};

// 单实例：一次只会有一块虚拟屏在跑。多实例需要的话再改成句柄表。
Capturer *g_capturer = nullptr;

void ReleaseLocked() {
    if (g_capturer == nullptr) {
        return;
    }
    if (g_capturer->reader != nullptr) {
        // 先摘 listener 再删 reader，否则回调可能打到已释放的对象上。
        AImageReader_setImageListener(g_capturer->reader, nullptr);
        AImageReader_delete(g_capturer->reader);
    }
    delete g_capturer;
    g_capturer = nullptr;
    LOGI("capturer released");
}

}  // namespace

extern "C" {

/**
 * 建一块采集用的 AImageReader，返回它的 Surface 给 Java 侧。
 *
 * @return Java android.view.Surface；失败返回 null（Java 侧必须判空）
 */
JNIEXPORT jobject JNICALL
Java_com_pvz2_auto_shizuku_NativeCapture_nativeSetup(
        JNIEnv *env, jclass /*clazz*/, jint width, jint height) {
    if (g_capturer != nullptr) {
        LOGI("已有 capturer，先释放再重建");
        ReleaseLocked();
    }

    auto *capturer = new Capturer();
    capturer->width = width;
    capturer->height = height;

    // usage 是关键：
    //   CPU_READ_OFTEN      —— 我们要把像素读回 Java 做模板匹配
    //   GPU_SAMPLED_IMAGE   —— 让系统允许 GPU 往这块 buffer 上合成（显示端的基本要求）
    // maxImages=3：够跟上 30fps，又不会让延迟拖长。
    media_status_t status = AImageReader_newWithUsage(
            width, height, AIMAGE_FORMAT_RGBA_8888,
            AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN |
                AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,
            3, &capturer->reader);
    if (status != AMEDIA_OK || capturer->reader == nullptr) {
        LOGE("AImageReader_newWithUsage 失败: %d", status);
        delete capturer;
        return nullptr;
    }

    status = AImageReader_getWindow(capturer->reader, &capturer->window);
    if (status != AMEDIA_OK || capturer->window == nullptr) {
        LOGE("AImageReader_getWindow 失败: %d", status);
        ReleaseLocked();
        AImageReader_delete(capturer->reader);
        delete capturer;
        return nullptr;
    }

    g_capturer = capturer;
    jobject surface = ANativeWindow_toSurface(env, capturer->window);
    LOGI("capturer ready: %dx%d, surface=%p", width, height, surface);
    return surface;
}

/** 释放采集端。可重复调用。 */
JNIEXPORT void JNICALL
Java_com_pvz2_auto_shizuku_NativeCapture_nativeRelease(JNIEnv * /*env*/, jclass /*clazz*/) {
    ReleaseLocked();
}

/**
 * 取最新一帧，把像素拷进 Java 传入的 ByteBuffer（RGBA_8888）。
 *
 * 在 native 侧直接拷，避免每次取帧都新建 Java Bitmap/Mat —— 自动化场景
 * 每秒要取好几帧，分配压力很实在。
 *
 * @param outBuffer 目标缓冲，长度必须 >= width*height*4
 * @param outWidth  实际宽度（经 rowStride 裁剪后）
 * @param outHeight 实际高度
 * @return true 表示取到一帧并已拷贝
 */
JNIEXPORT jboolean JNICALL
Java_com_pvz2_auto_shizuku_NativeCapture_nativeAcquireFrame(
        JNIEnv *env, jclass /*clazz*/, jobject outBuffer, jintArray outSize) {
    if (g_capturer == nullptr || g_capturer->reader == nullptr) {
        return JNI_FALSE;
    }

    AImage *image = nullptr;
    // 用 acquireLatest 而不是 next：自动化要的是"此刻屏幕长什么样"，
    // 积压的旧帧没有处理价值。
    if (AImageReader_acquireLatestImage(g_capturer->reader, &image) != AMEDIA_OK ||
        image == nullptr) {
        return JNI_FALSE;
    }

    bool ok = false;
    uint8_t *pixels = nullptr;
    int32_t rowStride = 0;
    int32_t width = 0;
    int32_t height = 0;

    if (AImage_getPlaneRowStride(image, 0, &rowStride) != AMEDIA_OK ||
        AImage_getWidth(image, &width) != AMEDIA_OK ||
        AImage_getHeight(image, &height) != AMEDIA_OK) {
        LOGE("取图像属性失败");
        AImage_delete(image);
        return JNI_FALSE;
    }

    int32_t dataLength = 0;
    if (AImage_getPlaneData(image, 0, &pixels, &dataLength) != AMEDIA_OK || pixels == nullptr) {
        LOGE("取平面数据失败");
        AImage_delete(image);
        return JNI_FALSE;
    }

    auto *out = static_cast<uint8_t *>(env->GetDirectBufferAddress(outBuffer));
    jlong capacity = env->GetDirectBufferCapacity(outBuffer);
    if (out == nullptr || capacity < static_cast<jlong>(width) * height * 4) {
        LOGE("目标缓冲不可用或太小: capacity=%lld need=%d", (long long) capacity, width * height * 4);
        AImage_delete(image);
        return JNI_FALSE;
    }

    // rowStride 通常**大于** width*4（GPU 按对齐补齐），必须逐行拷并裁掉右侧补白，
    // 否则画面会斜切。
    //
    // 但也要防 rowStride **小于** width*4 的情况（某些实现按像素跨距给值）。
    // 不做这个判断就是一次没有 bounds check 的 memcpy —— 会读越界。
    // dataLength 是这一帧的真实字节数，用它兜底校验，而不是拿到就算了。
    const int32_t dstRowBytes = width * 4;
    const int32_t srcRowBytes = rowStride > 0 ? rowStride : dstRowBytes;
    const int32_t copyBytes = srcRowBytes < dstRowBytes ? srcRowBytes : dstRowBytes;

    const int64_t needSrc = static_cast<int64_t>(height - 1) * srcRowBytes + copyBytes;
    if (dataLength > 0 && needSrc > dataLength) {
        LOGE("帧数据不够: need=%lld got=%d (w=%d h=%d rowStride=%d)",
             (long long) needSrc, dataLength, width, height, rowStride);
        AImage_delete(image);
        return JNI_FALSE;
    }

    for (int32_t y = 0; y < height; ++y) {
        uint8_t *dst = out + static_cast<size_t>(y) * dstRowBytes;
        const uint8_t *src = pixels + static_cast<size_t>(y) * srcRowBytes;
        memcpy(dst, src, static_cast<size_t>(copyBytes));
        // rowStride 比 width*4 小时，右侧会缺几个像素 —— 补 0，避免画面有彩边。
        if (copyBytes < dstRowBytes) {
            memset(dst + copyBytes, 0, static_cast<size_t>(dstRowBytes - copyBytes));
        }
    }

    if (outSize != nullptr) {
        jint size[2] = {width, height};
        env->SetIntArrayRegion(outSize, 0, 2, size);
    }

    ok = true;
    AImage_delete(image);
    return ok ? JNI_TRUE : JNI_FALSE;
}



/**
 * 取最新一帧的字节数（width*height*4），没有帧返回 0。
 *
 * 拆成"先问大小再读"两步，是为了让 Java 侧先判断要不要分配 ashmem ——
 * 没帧时不要每次空转都建一块 3.7 MB 的内存。
 */
JNIEXPORT jint JNICALL
Java_com_pvz2_auto_shizuku_NativeCapture_nativeFrameSize(JNIEnv * /*env*/, jclass /*clazz*/) {
    if (g_capturer == nullptr || g_capturer->reader == nullptr) {
        return 0;
    }
    AImage *image = nullptr;
    if (AImageReader_acquireLatestImage(g_capturer->reader, &image) != AMEDIA_OK || !image) {
        return 0;
    }
    int32_t width = 0, height = 0;
    jint size = 0;
    if (AImage_getWidth(image, &width) == AMEDIA_OK &&
        AImage_getHeight(image, &height) == AMEDIA_OK) {
        size = width * height * 4;
    }
    AImage_delete(image);
    return size;
}
}  // extern "C"
