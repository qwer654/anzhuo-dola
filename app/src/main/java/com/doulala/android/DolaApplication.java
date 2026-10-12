package com.doulala.android;

import android.app.Application;
import android.webkit.WebView;

/** Each account runs in its own Android process and Chromium storage directory. */
public final class DolaApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        String process = Application.getProcessName();
        int separator = process.lastIndexOf(':');
        if (separator >= 0) {
            String suffix = process.substring(separator + 1);
            if (!SpaceSlots.isSuffix(suffix)) throw new IllegalStateException("Unknown browser process");
            // Must precede CookieManager, WebSettings or any WebView initialization.
            WebView.setDataDirectorySuffix(suffix);
        }
    }
}
