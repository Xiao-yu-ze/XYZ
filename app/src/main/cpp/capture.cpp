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
// ## 取帧方式：事件驱动（照 MaaFwApp 的 bridge_capture / bridge_frame_buffer）
//
// 以前是 Java 定时轮询 `acquireFrame`（每秒 30 次）。而虚拟屏生产是
// **每秒 120 帧** —— 取 30、产 120，ImageReader 队列永远满，
// SurfaceFlinger 在"取空闲缓冲"那一步被阻塞，合成线程卡住，
// system_server 连锁超时，平板直接重启（dropbox: wd_restart / uncaught_restart）。
//
// 现在改成：注册 `AImageReader_setImageListener`，**来一帧就在回调里取走一帧**，
// 队列永远有空缓冲，生产者再也不被阻塞。这是 MaaFwApp 的做法，也是
// 120fps 能安全跑起来的前提。
//
// ## 三缓冲 + 原子状态机
//
// 照 MaaFwApp 的 `bridge_frame_buffer.cpp`：3 块缓冲轮转，
// 每块有 state(FREE/WRITING/READY/READING)，写者用锁串行、读者不拿锁。
//
// 差别只有一处：它的缓冲是进程内 malloc，我们的读端在 **App 进程**，
// 所以缓冲和状态机都放在 mmap 的共享区里（由 Java 侧建文件、两端映射）。
// 原子操作在共享内存上依然成立 —— 同一份 page cache。

#include <jni.h>
#include <android/log.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>

#include <atomic>
#include <cerrno>
#include <chrono>
#include <sys/mman.h>
#include <unistd.h>
#include <new>
#include <cstring>
#include <mutex>
#include <thread>

#define LOG_TAG "Pvz2Capture"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// 三缓冲：一块在写、一块刚写完待读、一块空闲。够周转又不会让延迟拖长。
constexpr int kBufferCount = 3;

// 缓冲状态。与 Java 侧 FrameRing 的常量必须一致。
constexpr int32_t kFree = 0;
constexpr int32_t kWriting = 1;
constexpr int32_t kReady = 2;
constexpr int32_t kReading = 3;

/**
 * 共享区头部布局。**与 Java 侧 FrameRing 逐字节对应**，改这里必须同步改那边。
 *
 * ```
 * offset 0    : int32  state[kBufferCount]     每块缓冲的状态
 * offset 12   : int32  readerCount[kBufferCount]  正在读的读者数
 * offset 24   : int64  frameCount              写成功的总帧数（单调递增）
 * offset 32   : int32  width
 * offset 36   : int32  height
 * offset 40   : int32  released                1 = 采集端已释放，读者应停止
 * offset 44   : int32  _pad
 * offset 48   : 像素数据起点（3 × width*height*4）
 * ```
 */
constexpr int kHeaderBytes = 48;
constexpr int kOffState = 0;
constexpr int kOffReaderCount = 12;
constexpr int kOffFrameCount = 24;
constexpr int kOffWidth = 32;
constexpr int kOffHeight = 36;
constexpr int kOffReleased = 40;

struct Capturer {
    AImageReader *reader = nullptr;
    ANativeWindow *window = nullptr;
    AImageReader_ImageListener listener{};

    int width = 0;
    int height = 0;

    // 共享区（由 Java 侧传入 fd 后 mmap）
    uint8_t *shared = nullptr;     // 映射起点
    size_t sharedBytes = 0;
    int fd = -1;

    // 写者串行；读者不拿这把锁
    std::mutex writeMutex;
    int writeIndex = 0;
};

Capturer *g_capturer = nullptr;

/** 保护 g_capturer 本身（回调线程 vs release）。 */
std::mutex g_mutex;

std::atomic<int64_t> g_dropped{0};

// ------------------------------------------------------------ 共享区访问

inline std::atomic<int32_t> &StateAt(uint8_t *shared, int index) {
    return *reinterpret_cast<std::atomic<int32_t> *>(shared + kOffState + index * 4);
}

inline std::atomic<int32_t> &ReaderCountAt(uint8_t *shared, int index) {
    return *reinterpret_cast<std::atomic<int32_t> *>(shared + kOffReaderCount + index * 4);
}

inline std::atomic<int64_t> &FrameCountAt(uint8_t *shared) {
    return *reinterpret_cast<std::atomic<int64_t> *>(shared + kOffFrameCount);
}

inline uint8_t *BufferAt(uint8_t *shared, int index, int width, int height) {
    return shared + kHeaderBytes + static_cast<size_t>(index) * width * height * 4;
}

/**
 * 找一块可写的缓冲。
 *
 * 优先 FREE；都不可用时抢一块 READY（反正即将被新帧覆盖，
 * 旧帧本来就已经过期）。**但绝不碰 READING** —— 那是读者正在拷的。
 *
 * @return 缓冲下标，-1 = 找不到
 */
int AcquireWriteIndex(Capturer *c) {
    const int n = kBufferCount;
    const int start = c->writeIndex;

    // 第一轮：只找 FREE
    for (int i = 0; i < n; ++i) {
        int idx = (start + i) % n;
        int32_t expected = kFree;
        if (StateAt(c->shared, idx).compare_exchange_strong(
                expected, kWriting, std::memory_order_acq_rel)) {
            c->writeIndex = (idx + 1) % n;
            return idx;
        }
    }
    // 第二轮：抢最旧的 READY
    for (int i = 0; i < n; ++i) {
        int idx = (start + i) % n;
        int32_t expected = kReady;
        if (StateAt(c->shared, idx).compare_exchange_strong(
                expected, kWriting, std::memory_order_acq_rel)) {
            c->writeIndex = (idx + 1) % n;
            return idx;
        }
    }
    return -1;
}

/**
 * 把这一帧写进共享区。
 *
 * 调用方持有 writeMutex。
 */
void WriteFrameLocked(Capturer *c, AImage *image) {
    int32_t rowStride = 0, width = 0, height = 0, dataLength = 0;
    uint8_t *pixels = nullptr;

    if (AImage_getPlaneRowStride(image, 0, &rowStride) != AMEDIA_OK ||
        AImage_getWidth(image, &width) != AMEDIA_OK ||
        AImage_getHeight(image, &height) != AMEDIA_OK ||
        AImage_getPlaneData(image, 0, &pixels, &dataLength) != AMEDIA_OK ||
        pixels == nullptr) {
        return;
    }
    if (width != c->width || height != c->height) {
        // 尺寸变了说明屏被重建过，这块共享区是按旧尺寸分的，不能写。
        return;
    }

    int idx = AcquireWriteIndex(c);
    if (idx < 0) {
        // 三块都在被读 —— 极罕见。丢掉这一帧比阻塞生产者好得多：
        // 一阻塞就会把 SurfaceFlinger 拖死，那才是真正的事故。
        g_dropped.fetch_add(1, std::memory_order_relaxed);
        return;
    }

    uint8_t *dst = BufferAt(c->shared, idx, width, height);

    // rowStride 通常**大于** width*4（GPU 按对齐补齐），必须逐行拷并裁掉补白，
    // 否则画面斜切。也要防 rowStride 偏小的情况，否则是无 bounds check 的读越界。
    const int32_t dstRowBytes = width * 4;
    const int32_t srcRowBytes = rowStride > 0 ? rowStride : dstRowBytes;
    const int32_t copyBytes = srcRowBytes < dstRowBytes ? srcRowBytes : dstRowBytes;

    const int64_t needSrc = static_cast<int64_t>(height - 1) * srcRowBytes + copyBytes;
    if (dataLength > 0 && needSrc > dataLength) {
        LOGE("帧数据不够: need=%lld got=%d (w=%d h=%d rowStride=%d)",
             (long long) needSrc, dataLength, width, height, rowStride);
        StateAt(c->shared, idx).store(kFree, std::memory_order_release);
        return;
    }

    for (int32_t y = 0; y < height; ++y) {
        memcpy(dst + static_cast<size_t>(y) * dstRowBytes,
               pixels + static_cast<size_t>(y) * srcRowBytes,
               static_cast<size_t>(copyBytes));
        if (copyBytes < dstRowBytes) {
            memset(dst + static_cast<size_t>(y) * dstRowBytes + copyBytes, 0,
                   static_cast<size_t>(dstRowBytes - copyBytes));
        }
    }

    // 先递增帧号再发布 READY：读者靠"帧号变了"判断有新帧，
    // 顺序反了会读到 READY 但帧号还是旧的。
    FrameCountAt(c->shared).fetch_add(1, std::memory_order_acq_rel);
    StateAt(c->shared, idx).store(kReady, std::memory_order_release);
}

/**
 * AImageReader 的来帧回调。**运行在 ImageReader 自己的线程上。**
 *
 * 这里是整条链路的关键：只要这个函数及时返回，队列就有空缓冲，
 * SurfaceFlinger 就不会被阻塞。所以它必须快，而且不能等任何人。
 */
void OnImageAvailable(void *context, AImageReader *reader) {
    auto *c = static_cast<Capturer *>(context);
    if (c == nullptr || c->shared == nullptr) {
        return;
    }

    AImage *image = nullptr;
    // acquireLatest：积压的旧帧没有处理价值，直接丢。
    // 配合 5 个缓冲，正常情况下每次只积压 0~1 帧。
    if (AImageReader_acquireLatestImage(reader, &image) != AMEDIA_OK || image == nullptr) {
        return;
    }

    {
        std::lock_guard<std::mutex> lock(c->writeMutex);
        WriteFrameLocked(c, image);
    }
    AImage_delete(image);
}

void ReleaseLocked() {
    if (g_capturer == nullptr) {
        return;
    }
    // ★ 先摘 listener，再标记 released，最后删 reader。
    //
    //   顺序错了会有回调打到正在销毁的对象上。标记 released 是为了让
    //   已经进入回调的那一帧知道该收手（它读 shared 前会检查）。
    if (g_capturer->reader != nullptr) {
        AImageReader_setImageListener(g_capturer->reader, nullptr);
    }
    if (g_capturer->shared != nullptr) {
        // released 放在 unmount 之前：读者看到它就不再读这块内存。
        reinterpret_cast<std::atomic<int32_t> *>(g_capturer->shared + kOffReleased)
                ->store(1, std::memory_order_release);
    }
    // 给可能的在途回调一点时间收尾。
    std::this_thread::sleep_for(std::chrono::milliseconds(30));

    if (g_capturer->reader != nullptr) {
        // 只删 reader，**不要单独 release window** —— window 归 reader 所有，
        // 多补一次 ANativeWindow_release 就是双重释放（真机 native crash 过）。
        AImageReader_delete(g_capturer->reader);
        g_capturer->reader = nullptr;
        g_capturer->window = nullptr;
    }
    if (g_capturer->shared != nullptr) {
        munmap(g_capturer->shared, g_capturer->sharedBytes);
        g_capturer->shared = nullptr;
    }
    if (g_capturer->fd >= 0) {
        close(g_capturer->fd);
        g_capturer->fd = -1;
    }
    delete g_capturer;
    g_capturer = nullptr;
    LOGI("capturer released");
}

/**
 * 从 java.io.FileDescriptor 取原始 fd。
 *
 * 不用 `GetFieldID("fd")` 直接读 int —— 各版本字段名都是 "fd"，但用
 * `getInt` 走反射属性更稳（API 30+ 上 descriptor 字段本身是 private）。
 */
int jniGetFd(JNIEnv *env, jobject fileDescriptor) {
    if (fileDescriptor == nullptr) return -1;
    jclass cls = env->GetObjectClass(fileDescriptor);
    jfieldID fid = env->GetFieldID(cls, "descriptor", "I");
    if (fid == nullptr) {
        env->ExceptionClear();
        fid = env->GetFieldID(cls, "fd", "I");
    }
    if (fid == nullptr) {
        env->ExceptionClear();
        LOGE("取 FileDescriptor 字段失败");
        return -1;
    }
    return env->GetIntField(fileDescriptor, fid);
}

}  // namespace

extern "C" {

/**
 * 建采集端。
 *
 * @param sharedFd  Java 侧建好的共享文件 fd，会被 mmap 到这里
 * @return Java Surface；失败返回 null
 */
JNIEXPORT jobject JNICALL
Java_com_pvz2_auto_shizuku_NativeCapture_nativeSetup(
        JNIEnv *env, jclass /*clazz*/, jint width, jint height, jobject sharedFdObj) {
    std::lock_guard<std::mutex> guard(g_mutex);

    if (g_capturer != nullptr) {
        LOGI("已有 capturer，先释放再重建");
        ReleaseLocked();
    }

    auto *capturer = new Capturer();
    capturer->width = width;
    capturer->height = height;

    // 映射共享区
    const size_t frameBytes = static_cast<size_t>(width) * height * 4;
    capturer->sharedBytes = kHeaderBytes + frameBytes * kBufferCount;
    if (sharedFdObj != nullptr) {
        // dup 一份自己管，避免 Java 那边关掉 fd 后我们的映射被影响到。
        int sharedFd = jniGetFd(env, sharedFdObj);
        capturer->fd = sharedFd >= 0 ? dup(sharedFd) : -1;
        if (capturer->fd >= 0) {
            void *p = mmap(nullptr, capturer->sharedBytes, PROT_READ | PROT_WRITE,
                           MAP_SHARED, capturer->fd, 0);
            if (p != MAP_FAILED) {
                capturer->shared = static_cast<uint8_t *>(p);
                // 初始化。共享区是裸 mmap 内存，原子对象必须显式构造。
                // 传指针而不是引用：StateAt 返回的是引用，取址才是构造所需的 void*。
                for (int i = 0; i < kBufferCount; ++i) {
                    new(static_cast<void *>(capturer->shared + kOffState + i * 4))
                            std::atomic<int32_t>(kFree);
                    new(static_cast<void *>(capturer->shared + kOffReaderCount + i * 4))
                            std::atomic<int32_t>(0);
                }
                new(static_cast<void *>(capturer->shared + kOffFrameCount))
                        std::atomic<int64_t>(0);
                *reinterpret_cast<int32_t *>(capturer->shared + kOffWidth) = width;
                *reinterpret_cast<int32_t *>(capturer->shared + kOffHeight) = height;
                *reinterpret_cast<int32_t *>(capturer->shared + kOffReleased) = 0;
                LOGI("共享区已映射: %zu 字节（%d 块缓冲）", capturer->sharedBytes, kBufferCount);
            } else {
                LOGE("mmap 共享区失败");
                close(capturer->fd);
                capturer->fd = -1;
            }
        }
    }
    if (capturer->shared == nullptr) {
        LOGE("没有共享区，采集无法工作");
        delete capturer;
        return nullptr;
    }

    media_status_t status = AImageReader_newWithUsage(
            width, height, AIMAGE_FORMAT_RGBA_8888,
            AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN |
                AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,
            // 5 与 MaaFwApp 一致。回调模式下一帧取一帧，5 个足够周转。
            5, &capturer->reader);
    if (status != AMEDIA_OK || capturer->reader == nullptr) {
        LOGE("AImageReader_newWithUsage 失败: %d", status);
        munmap(capturer->shared, capturer->sharedBytes);
        if (capturer->fd >= 0) close(capturer->fd);
        delete capturer;
        return nullptr;
    }

    status = AImageReader_getWindow(capturer->reader, &capturer->window);
    if (status != AMEDIA_OK || capturer->window == nullptr) {
        LOGE("AImageReader_getWindow 失败: %d", status);
        if (capturer->window != nullptr) {
            ANativeWindow_release(capturer->window);
            capturer->window = nullptr;
        }
        AImageReader_delete(capturer->reader);
        munmap(capturer->shared, capturer->sharedBytes);
        if (capturer->fd >= 0) close(capturer->fd);
        delete capturer;
        return nullptr;
    }

    // ★ 注册回调，从此由生产者推帧，我们不再轮询。
    capturer->listener.context = capturer;
    capturer->listener.onImageAvailable = OnImageAvailable;
    status = AImageReader_setImageListener(capturer->reader, &capturer->listener);
    if (status != AMEDIA_OK) {
        LOGE("setImageListener 失败: %d", status);
        AImageReader_delete(capturer->reader);
        munmap(capturer->shared, capturer->sharedBytes);
        if (capturer->fd >= 0) close(capturer->fd);
        delete capturer;
        return nullptr;
    }

    g_capturer = capturer;
    jobject surface = ANativeWindow_toSurface(env, capturer->window);
    LOGI("capturer ready: %dx%d, 事件驱动采集已启动", width, height);
    return surface;
}

JNIEXPORT void JNICALL
Java_com_pvz2_auto_shizuku_NativeCapture_nativeRelease(JNIEnv * /*env*/, jclass /*clazz*/) {
    std::lock_guard<std::mutex> guard(g_mutex);
    ReleaseLocked();
}

/** 因三块缓冲全被占用而丢掉的帧数。调试用。 */
JNIEXPORT jlong JNICALL
Java_com_pvz2_auto_shizuku_NativeCapture_nativeDroppedFrames(JNIEnv * /*env*/, jclass /*clazz*/) {
    return g_dropped.load(std::memory_order_acquire);
}
}  // extern "C"
