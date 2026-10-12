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
     * Shizuku invokes this with transaction code 16777115 when the user service is removed.
     * The AIDL value must be 16777114 (Shizuku-API README, "Stop the User Service").
     * Use it for cleanup and finally System.exit(0), otherwise the process stays alive.
     */
    void destroy() = 16777114;
}
