package com.doulala.android;

import android.os.Bundle;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises Android WebView's native history state, not DOM/form-state restoration. */
@RunWith(AndroidJUnit4.class)
public class WebViewHistoryRestoreTest {
    @Test public void boundedStateRestoresBackForwardHistory() throws Exception {
        final WebView[] first = new WebView[1];
        final CountDownLatch loaded = new CountDownLatch(2);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            first[0] = new WebView(InstrumentationRegistry.getInstrumentation().getTargetContext());
            first[0].setWebViewClient(new android.webkit.WebViewClient() {
                @Override public void onPageFinished(WebView view, String url) { loaded.countDown(); }
            });
            first[0].loadDataWithBaseURL("https://fixture.invalid/one", "one", "text/html", "UTF-8", null);
            first[0].postDelayed(() -> first[0].loadDataWithBaseURL("https://fixture.invalid/two", "two", "text/html", "UTF-8", null), 250);
        });
        assertTrue("fixture pages did not finish", loaded.await(15, TimeUnit.SECONDS));
        final Bundle[] saved = new Bundle[1];
        final String[] encoded = new String[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            saved[0] = new Bundle();
            assertNotNull(first[0].saveState(saved[0]));
            encoded[0] = TabStateCodec.encode(saved[0], TabStateCodec.TOTAL_LIMIT);
            assertNotNull("small history should fit the bounded preference budget", encoded[0]);
        });
        final WebView[] second = new WebView[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            second[0] = new WebView(InstrumentationRegistry.getInstrumentation().getTargetContext());
            Bundle decoded = TabStateCodec.decode(encoded[0]);
            assertNotNull(decoded);
            assertNotNull(second[0].restoreState(decoded));
            assertTrue(second[0].canGoBack());
            assertTrue(second[0].getUrl().endsWith("/two"));
            second[0].destroy();first[0].destroy();
        });
    }
}

