package com.doulala.android;

import android.app.Application;
import android.webkit.WebView;

/** Each account runs in its own Android process and Chromium storage directory. */
public final class DolaApplication extends Application {
    private static SpaceReset.BrowserGate profileGate;
    /** BrowserActivity calls this before constructing a WebView. */
    public static boolean isSpaceResetPending(android.content.Context context,int slot) {
        return SpaceReset.isPending(context,slot);
    }
    @Override public void onCreate() {
        super.onCreate();
        String process = Application.getProcessName();
        int separator = process.lastIndexOf(':');
        if (separator >= 0) {
            String suffix = process.substring(separator + 1);
            if (!SpaceSlots.isSuffix(suffix)) throw new IllegalStateException("Unknown browser process");
            int slot=Integer.parseInt(suffix.substring("space".length()));
            // Shared gate is held for this process lifetime. Reset first writes its
            // file marker, kills us, then obtains the exclusive gate before deleting.
            try { profileGate=SpaceReset.acquireBrowserGate(this,slot); } catch (java.io.IOException blocked) {
                android.os.Process.killProcess(android.os.Process.myPid());
                throw new IllegalStateException("space reset pending",blocked);
            }
            // Must precede CookieManager, WebSettings or any WebView initialization.
            WebView.setDataDirectorySuffix(suffix);
        }
    }
}
