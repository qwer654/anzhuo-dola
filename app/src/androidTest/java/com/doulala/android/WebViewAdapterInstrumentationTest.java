package com.doulala.android;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Verifies the WebView adapter contract on a real Android WebView implementation. */
@RunWith(AndroidJUnit4.class)
public class WebViewAdapterInstrumentationTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test public void documentStartAdapterExposesLocalControlsWithoutChangingNetwork() throws Exception {
        assertTrue("WebView provider lacks document-start JavaScript support",
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT));
        String promptTools = readAsset("prompt-tools.js");
        String adapter = readAsset("dola30-android-adapter.js");

        AtomicReference<WebView> viewRef = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            WebView view = new WebView(context);
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setDomStorageEnabled(true);
            viewRef.set(view);
            try {
                WebViewCompat.addDocumentStartJavaScript(view, promptTools, Collections.singleton("https://*.dola.com"));
                WebViewCompat.addDocumentStartJavaScript(view, adapter, Collections.singleton("https://*.dola.com"));
            } catch (Exception e) {
                throw new AssertionError("Could not install Android adapter scripts", e);
            }
        });

        WebView view = viewRef.get();
        CountDownLatch loaded = new CountDownLatch(1);
        AtomicReference<String> pageResult = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            view.setWebViewClient(new android.webkit.WebViewClient() {
                @Override public void onPageFinished(WebView webView, String url) {
                    webView.evaluateJavascript("(function(){"
                        + "if(!window.getDola30sStatus||!window.rewriteDolaTimeline)return 'missing';"
                        + "window.setDola30s(false);"
                        + "var before=JSON.parse(window.getDola30sStatus());"
                        + "window.setDola30s(true);"
                        + "var after=JSON.parse(window.getDola30sStatus());"
                        + "return JSON.stringify({before:before,after:after,timeline:window.rewriteDolaTimeline('00:00-00:05 开场',30),guarded:window.rewriteDolaTimeline('00:00-00:05 开场',30,true)});"
                        + "})()", value -> { pageResult.set(value); loaded.countDown(); });
                }
            });
            view.loadDataWithBaseURL("https://www.dola.com/", "<!doctype html><html><head></head><body>adapter test</body></html>",
                "text/html", "UTF-8", null);
        });

        assertTrue("WebView page did not finish", loaded.await(20, TimeUnit.SECONDS));
        Object decoded = new JSONTokener(pageResult.get()).nextValue();
        assertTrue("Expected JSON result from adapter", decoded instanceof String);
        JSONObject result = new JSONObject((String) decoded);
        assertFalse(result.getJSONObject("before").getBoolean("on"));
        JSONObject after = result.getJSONObject("after");
        assertTrue(after.getBoolean("on"));
        assertEquals(30, after.getInt("duration"));
        assertFalse(after.getBoolean("networkRewrite"));
        assertFalse(after.getBoolean("autoSubmit"));
        assertTrue(result.getString("timeline").contains("全片进度 0%–16.7%"));
        String guarded = result.getString("guarded");
        assertTrue(guarded.startsWith("【直接生成控制】"));
        assertTrue(guarded.contains("【单片生成控制】"));
        assertTrue(guarded.contains("全片进度 0%–16.7%"));

        InstrumentationRegistry.getInstrumentation().runOnMainSync(view::destroy);
    }

    @Test public void observedMediaLinkCopyWritesExactUrlToClipboard() {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        assertNotNull(clipboard);
        String fixture = "https://cdn.example.test/media?id=abc%2F123&quality=hd";
        try {
            clipboard.clearPrimaryClip();
            assertTrue(BrowserActivity.writeMediaUrlToClipboard(context, fixture));
            assertEquals(fixture, clipboard.getPrimaryClip().getItemAt(0).coerceToText(context).toString());
            assertFalse(BrowserActivity.writeMediaUrlToClipboard(context, "  "));
        } finally {
            clipboard.clearPrimaryClip();
        }
    }
    private String readAsset(String name) throws Exception {
        try (InputStream in = context.getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
