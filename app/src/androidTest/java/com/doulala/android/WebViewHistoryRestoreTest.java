package com.doulala.android;

import android.os.Bundle;
import android.content.Intent;
import android.app.Activity;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises Android WebView's native history state, not DOM/form-state restoration. */
@RunWith(AndroidJUnit4.class)
public class WebViewHistoryRestoreTest {
    @Test public void boundedStateRestoresBackForwardHistory() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        final WebView[] first = new WebView[1];
        final CountDownLatch loaded = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            first[0] = new WebView(context);
            first[0].setWebViewClient(new android.webkit.WebViewClient() {
                @Override public void onPageFinished(WebView view, String url) { loaded.countDown(); }
            });
            activity.setContentView(first[0]);
            first[0].loadDataWithBaseURL("https://fixture.invalid/one", "one", "text/html", "UTF-8", null);
        });
        assertTrue("fixture page did not finish", loaded.await(15, TimeUnit.SECONDS));
        final CountDownLatch pushed = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
            first[0].evaluateJavascript("history.pushState({}, '', '/two'); location.pathname", value -> pushed.countDown()));
        assertTrue("history fixture did not update", pushed.await(5, TimeUnit.SECONDS));
        final Bundle[] saved = new Bundle[1];
        final String[] encoded = new String[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            saved[0] = new Bundle();
            assertNotNull("WebView returned no saved state", first[0].saveState(saved[0]));
            encoded[0] = TabStateCodec.encode(saved[0], TabStateCodec.TOTAL_LIMIT);
            assertNotNull("small history should fit the bounded preference budget", encoded[0]);
        });
        final WebView[] second = new WebView[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            second[0] = new WebView(context);
            activity.setContentView(second[0]);
            Bundle decoded = TabStateCodec.decode(encoded[0]);
            assertNotNull(decoded);
            assertNotNull("WebView did not restore the saved history", second[0].restoreState(decoded));
            assertTrue("restored history has no back entry", second[0].canGoBack());
            assertTrue("restored URL was " + second[0].getUrl(), second[0].getUrl().endsWith("/two"));
            second[0].destroy();first[0].destroy();activity.finish();
        });
    }
}

