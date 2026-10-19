// AIDL contract for the Shizuku UserService.
//
// The Shizuku server instantiates Pvz2UserService inside its own app_process running with
// shell (uid 2000) or root (uid 0) identity, and drives it through this interface.
//
// Why UserService instead of a shell command wrapper: Shizuku API 13 made
// `Shizuku.newProcess` private and deprecated it, and the Shizuku-API README states that
// developers "have to use UserService instead" because it "can replace newProcess in all
// cases". This is therefore the officially supported way to run privileged work.

package com.pvz2.auto;

import android.os.ParcelFileDescriptor;
import android.view.Surface;

interface IPvz2Service {

    /**
     * Runs `screencap -p` with shell privileges and streams the resulting PNG back.
     *
     * A ParcelFileDescriptor is returned rather than a byte[] on purpose: Binder
     * transactions are capped at roughly 1 MB, and a full-resolution PNG can exceed that
     * (TransactionTooLargeException). Streaming keeps it resolution-independent.
     * The caller owns the returned descriptor and must close it.
     */
    ParcelFileDescriptor screencap() = 1;

    /** Injects a tap at physical screen coordinates via `input tap`. */
    void tap(int x, int y) = 2;

    /** Injects a swipe via `input swipe`. */
    void swipe(int x1, int y1, int x2, int y2, int durationMs) = 3;

    /** Generic privileged shell execution. Returns merged stdout+stderr. */
    String exec(String command) = 4;

    /** Effective display size as "WxH", parsed from `wm size`. */
    String getDisplaySize() = 5;

    /**
     * 建一块虚拟屏，把画面输出到 [surface]（由调用方在 App 进程里用 ImageReader 创建）。
     *
     * 为什么必须在**特权侧**建：`DisplayManager.createVirtualDisplay` 是 @hide API，
     * 而且建屏者的身份决定了后面「能不能把别的 App 拉到这块屏上」。我们的 UserService
     * 跑在 Shizuku 拉起的 app_process 里，身份就是 shell(2000) —— 正好是 AOSP 里
     * `packages/Shell` 那个 platform 签名特权进程的同款特权面。
     *
     * Surface 是 Parcelable，可以整个通过 Binder 传过来：画面在特权侧产生，
     * 帧数据在 App 进程被消费，中间不需要任何临时文件或 socket。
     *
     * @param flags 用 VirtualDisplayFlags.forSdk() 按系统版本算好后传进来。
     * @return 虚拟屏的 displayId（> 0 成功；-1 失败，原因见 logcat 的 Pvz2UserService）
     */
    int createVirtualDisplay(String name, int width, int height, int dpi, in Surface surface, int flags) = 6;

    /** 释放 [createVirtualDisplay] 建的那块屏；没建过时是空操作。 */
    void releaseVirtualDisplay() = 7;

    /** 当前虚拟屏的 displayId；没有虚拟屏时返回 -1。调试与断言用。 */
    int getVirtualDisplayId() = 8;

    /**
     * 在**指定 display** 上点一下，并可以控制最短按住时长。
     *
     * 为什么不能继续用 `input tap`：
     *  1. `input tap` 只能打到默认屏（display 0），搬去副屏的游戏收不到；
     *  2. `input tap` 的按下时长极短，低帧率下会丢点击（会话 7 已定位）。
     * 所以改用 `InputManager.injectInputEvent` + `MotionEvent.setDisplayId()`，
     * 并显式按住 [holdMs] 毫秒。
     *
     * @return true 表示注入被系统接受（false 通常是缺 INJECT_EVENTS 权限）
     */
    boolean tapOnDisplay(int displayId, int x, int y, int holdMs) = 9;

    /** 在指定 display 上滑动。同样走 injectInputEvent + displayId。 */
    boolean swipeOnDisplay(int displayId, int x1, int y1, int x2, int y2, int durationMs) = 10;

    // ---------------------------------------------------------------- 任务与显示
    //
    // 下面四个方法**必须在 UserService（app_process / shell 身份）里执行**。
    //
    // 实测：在 App 进程里反射 `IActivityTaskManager$Stub.asInterface` /
    // `IActivityManager$Stub.asInterface` 会得到
    // `NoSuchMethodException: ...asInterface [interface android.os.IBinder]`
    // —— 不是权限问题，是**隐藏 API 限制**把这些方法从反射结果里滤掉了。
    // 而 app_process 不受该限制，所以这些能力只能放在 shell 侧，由 App 通过 AIDL 调用。

    /**
     * 把 [packageName] 的任务搬到 [displayId]。
     *
     * @return 搬动成功的 taskId；-1 失败，-2 表示该包没有运行中任务
     */
    int moveTaskToDisplay(String packageName, int displayId) = 11;

    /**
     * 查 [packageName] 的任务现在在哪块 display 上。
     *
     * @return displayId；-1 判断不了，-2 表示没有运行中任务。
     *   **调用方必须区分这三者** —— 把「判断不了」当成「不在副屏」会误触发搬移。
     */
    int getTaskDisplayId(String packageName) = 12;

    /** 列出最近任务及其 displayId，一行一条。排查「到底搬没搬过去」用。 */
    String dumpTasks() = 13;

    /**
     * 在 [displayId] 上启动 [packageName]。
     *
     * 用 `ActivityOptions.launchDisplayId` 在启动那一刻指定副屏 ——
     * `am start --display` 实测不吃 display（任务会落在主屏）。
     *
     * @return true 表示系统接受了启动请求（不保证窗口已建好）
     */
    boolean launchAppOnDisplay(String packageName, int displayId) = 14;

    /**
     * 抓一份 logcat 文本。
     *
     * 为什么必须放在这一侧：Android 4.1 起普通应用只能读**自己进程**的日志，
     * 而排查虚拟屏问题需要看 system_server 打的
     * `Permission Denial: ... requires ADD_TRUSTED_DISPLAY` 之类。
     * UserService 跑在 shell(uid 2000)，有读全部日志的权限。
     *
     * @param tagFilter 为空则抓全部；非空时按 `grep -E` 过滤（区分大小写）
     * @param maxLines  最多返回多少行（取**末尾**，最新的日志才有用）
     * @return 日志文本；失败返回带 `ERROR:` 前缀的字符串
     */
    String grabLogcat(String tagFilter, int maxLines) = 15;

    /**
     * 查 [displayId] 这块屏的 flags 与 owner，一行摘要。
     *
     * 用来判断屏到底是不是 TRUSTED —— 这是"窗口能不能挂上去"的唯一判据。
     * 返回形如 `display=10 type=VIRTUAL trusted=false owner=com.android.shell`。
     */
    String describeDisplay(int displayId) = 16;

    /**
     * Shizuku invokes this with transaction code 16777115 when the user service is removed.
     * The AIDL value must be 16777114 (Shizuku-API README, "Stop the User Service").
     * Use it for cleanup and finally System.exit(0), otherwise the process stays alive.
     */
    void destroy() = 16777114;
}
