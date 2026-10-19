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
     * Shizuku invokes this with transaction code 16777115 when the user service is removed.
     * The AIDL value must be 16777114 (Shizuku-API README, "Stop the User Service").
     * Use it for cleanup and finally System.exit(0), otherwise the process stays alive.
     */
    void destroy() = 16777114;
}
