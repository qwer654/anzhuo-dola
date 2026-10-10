package com.doulala.android;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.WebViewAssetLoader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONTokener;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Verifies the WebView adapter contract on a real Android WebView implementation. */
@RunWith(AndroidJUnit4.class)
public class WebViewAdapterInstrumentationTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test public void defaultModeLeavesOriginalRequestHooksDisabled() throws Exception {
        assertTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT));
        String original = readAsset("dola30-original/duration-main.js");
        String promptTools = readAsset("prompt-tools.js");
        String adapter = readAsset("dola30-android-adapter.js");
        AtomicReference<WebView> viewRef = new AtomicReference<>();
        AtomicReference<String> resultHolder = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            WebView view = new WebView(context);
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setDomStorageEnabled(true);
            viewRef.set(view);
            Set<String> origins = new HashSet<>(Arrays.asList("https://dola.com", "https://*.dola.com"));
            try {
                WebViewCompat.addDocumentStartJavaScript(view, "window.__fetchBefore=window.fetch;window.__xhrSendBefore=XMLHttpRequest.prototype.send;", origins);
                WebViewCompat.addDocumentStartJavaScript(view, promptTools, origins);
                WebViewCompat.addDocumentStartJavaScript(view, "window.__dola30ExtOff=true;\n" + original, origins);
                WebViewCompat.addDocumentStartJavaScript(view, adapter, origins);
            } catch (Exception e) {
                throw new AssertionError("Could not install default-mode scripts", e);
            }
            view.setWebViewClient(new android.webkit.WebViewClient() {
                @Override public void onPageFinished(WebView webView, String url) {
                    webView.evaluateJavascript("JSON.stringify({"
                        + "fetchUntouched:window.fetch===window.__fetchBefore,"
                        + "xhrUntouched:XMLHttpRequest.prototype.send===window.__xhrSendBefore,"
                        + "originalInstalled:!!window.__watermarkFreeDuration30Installed,"
                        + "networkRewrite:window.dola30Android.limitations.networkRewrite,"
                        + "autoSubmit:window.dola30Android.limitations.autoSubmit})",
                        value -> { resultHolder.set(value); finished.countDown(); });
                }
            });
            view.loadDataWithBaseURL("https://www.dola.com/", "<!doctype html><html><body>default-mode fixture</body></html>",
                "text/html", "UTF-8", null);
        });
        assertTrue("Default-mode fixture did not finish", finished.await(20, TimeUnit.SECONDS));
        Object decoded = new JSONTokener(resultHolder.get()).nextValue();
        assertTrue(decoded instanceof String);
        JSONObject result = new JSONObject((String) decoded);
        assertTrue(result.getBoolean("fetchUntouched"));
        assertTrue(result.getBoolean("xhrUntouched"));
        assertFalse(result.getBoolean("originalInstalled"));
        assertFalse(result.getBoolean("networkRewrite"));
        assertFalse(result.getBoolean("autoSubmit"));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(viewRef.get()::destroy);
    }

    @Test public void privacyCleanModeReducesWebglDebugInfoWithoutSpoofingWebViewUa() throws Exception {
        assertTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT));
        String privacy = readAsset("fingerprint-privacy/content-main.js");
        String bootstrap = BrowserActivity.privacyFingerprintBootstrap(privacy, true);
        AtomicReference<WebView> viewRef = new AtomicReference<>();
        AtomicReference<String> resultHolder = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            WebView view = new WebView(context);
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setDomStorageEnabled(true);
            viewRef.set(view);
            try {
                WebViewCompat.addDocumentStartJavaScript(view, bootstrap,
                    new HashSet<>(Arrays.asList("https://dola.com", "https://*.dola.com")));
            } catch (Exception e) {
                throw new AssertionError("Could not install privacy-clean WebGL reduction", e);
            }
            view.setWebViewClient(new android.webkit.WebViewClient() {
                @Override public void onPageFinished(WebView webView, String url) {
                    String expectedUa = JSONObject.quote(webView.getSettings().getUserAgentString());
                    webView.evaluateJavascript("JSON.stringify({"
                        + "settings:window.__dolaFingerprintPrivacy.getSettings(),"
                        + "ua:navigator.userAgent,expectedUa:" + expectedUa + ","
                        + "debugResult:(window.WebGLRenderingContext ? WebGLRenderingContext.prototype.getExtension.call({},'WEBGL_debug_renderer_info') : 'unavailable')"
                        + "})", value -> { resultHolder.set(value); finished.countDown(); });
                }
            });
            view.loadDataWithBaseURL("https://www.dola.com/", "<!doctype html><html><body>privacy fixture</body></html>",
                "text/html", "UTF-8", null);
        });
        assertTrue("Privacy fixture did not finish", finished.await(20, TimeUnit.SECONDS));
        Object decoded = new JSONTokener(resultHolder.get()).nextValue();
        assertTrue(decoded instanceof String);
        JSONObject result = new JSONObject((String) decoded);
        assertTrue(result.getJSONObject("settings").getBoolean("hideWebglDebugInfo"));
        assertFalse("Android must not enable identity spoofing", result.getJSONObject("settings").getBoolean("cleanMode"));
        assertEquals(result.getString("expectedUa"), result.getString("ua"));
        if (!"unavailable".equals(result.getString("debugResult"))) assertTrue(result.isNull("debugResult"));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(viewRef.get()::destroy);
    }

    @Test public void privacyCleanModeAlsoRunsInMatchingOriginFrames() throws Exception {
        assertTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT));
        String bootstrap = BrowserActivity.privacyFingerprintBootstrap(
            readAsset("fingerprint-privacy/content-main.js"), true);
        AtomicReference<WebView> viewRef = new AtomicReference<>();
        AtomicReference<String> resultHolder = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            WebView view = new WebView(context);
            view.getSettings().setJavaScriptEnabled(true);
            viewRef.set(view);
            try {
                WebViewCompat.addDocumentStartJavaScript(view, bootstrap,
                    new HashSet<>(Arrays.asList("https://dola.com", "https://*.dola.com")));
            } catch (Exception e) {
                throw new AssertionError("Could not install frame privacy-clean script", e);
            }
            WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain("www.dola.com")
                .addPathHandler("/fixture/", path -> new WebResourceResponse("text/html", "UTF-8",
                    new ByteArrayInputStream("<!doctype html><p>matching origin frame</p>".getBytes(StandardCharsets.UTF_8))))
                .build();
            view.setWebViewClient(new android.webkit.WebViewClient() {
                @Override public WebResourceResponse shouldInterceptRequest(WebView webView, WebResourceRequest request) {
                    return loader.shouldInterceptRequest(request.getUrl());
                }
                @Override public void onPageFinished(WebView webView, String url) {
                    webView.postDelayed(() -> webView.evaluateJavascript("JSON.stringify({frameConfigured:!!frames[0].__dolaFingerprintPrivacy,"
                        + "hideWebgl:frames[0].__dolaFingerprintPrivacy && frames[0].__dolaFingerprintPrivacy.getSettings().hideWebglDebugInfo})",
                        value -> { resultHolder.set(value); finished.countDown(); }), 300);
                }
            });
            view.loadDataWithBaseURL("https://www.dola.com/fixture/parent.html",
                "<!doctype html><iframe src='https://www.dola.com/fixture/frame.html'></iframe>", "text/html", "UTF-8", null);
        });
        assertTrue("Frame privacy fixture did not finish", finished.await(20, TimeUnit.SECONDS));
        Object decoded = new JSONTokener(resultHolder.get()).nextValue();
        assertTrue(decoded instanceof String);
        JSONObject result = new JSONObject((String) decoded);
        assertTrue("Matching-origin child frame did not receive the privacy script", result.getBoolean("frameConfigured"));
        assertTrue("Matching-origin child frame did not receive the WebGL reduction setting", result.getBoolean("hideWebgl"));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(viewRef.get()::destroy);
    }

    @Test public void dolaScriptsAreNotInjectedOnUntrustedOrigin() throws Exception {
        assertTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT));
        String original = readAsset("dola30-original/duration-main.js");
        String promptTools = readAsset("prompt-tools.js");
        String adapter = readAsset("dola30-android-adapter.js");
        AtomicReference<WebView> viewRef = new AtomicReference<>();
        AtomicReference<String> resultHolder = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            WebView view = new WebView(context);
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setDomStorageEnabled(true);
            viewRef.set(view);
            Set<String> origins = new HashSet<>(Arrays.asList("https://dola.com", "https://*.dola.com"));
            try {
                WebViewCompat.addDocumentStartJavaScript(view, promptTools, origins);
                WebViewCompat.addDocumentStartJavaScript(view, "window.__dola30ExtOff=false;\n" + original, origins);
                WebViewCompat.addDocumentStartJavaScript(view, adapter, origins);
            } catch (Exception e) {
                throw new AssertionError("Could not install Dola-scoped scripts", e);
            }
            view.setWebViewClient(new android.webkit.WebViewClient() {
                @Override public void onPageFinished(WebView webView, String url) {
                    webView.evaluateJavascript("JSON.stringify({"
                        + "adapter:typeof window.dola30Android,"
                        + "original:!!window.__watermarkFreeDuration30Installed,"
                        + "promptTools:typeof window.DolaPromptTools})",
                        value -> { resultHolder.set(value); finished.countDown(); });
                }
            });
            view.loadDataWithBaseURL("https://example.com/", "<!doctype html><html><body>untrusted fixture</body></html>",
                "text/html", "UTF-8", null);
        });
        assertTrue("Untrusted-origin fixture did not finish", finished.await(20, TimeUnit.SECONDS));
        Object decoded = new JSONTokener(resultHolder.get()).nextValue();
        assertTrue(decoded instanceof String);
        JSONObject result = new JSONObject((String) decoded);
        assertEquals("undefined", result.getString("adapter"));
        assertFalse(result.getBoolean("original"));
        assertEquals("undefined", result.getString("promptTools"));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(viewRef.get()::destroy);
    }

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

    @Test public void floatingControlDragUsesAndPersistsOriginalPanelPositionKey() throws Exception {
        assertTrue("WebView provider lacks document-start JavaScript support",
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT));
        String adapter = readAsset("dola30-android-adapter.js");
        AtomicReference<WebView> viewRef = new AtomicReference<>();
        AtomicReference<String> result = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            WebView view = new WebView(context);
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setDomStorageEnabled(true);
            viewRef.set(view);
            try {
                WebViewCompat.addDocumentStartJavaScript(view, adapter,
                    new HashSet<>(Arrays.asList("https://dola.com", "https://*.dola.com")));
            } catch (Exception e) {
                throw new AssertionError("Could not install Android adapter", e);
            }
            view.setWebViewClient(new android.webkit.WebViewClient() {
                @Override public void onPageFinished(WebView webView, String url) {
                    webView.evaluateJavascript("(function(){"
                        + "var button=document.getElementById('dola30-android-control');"
                        + "if(!button)return JSON.stringify({missing:true});"
                        + "window.setDola30s(false);var original=JSON.parse(window.getDola30sStatus());"
                        + "var left=parseFloat(button.style.left),bottom=parseFloat(button.style.bottom);"
                        + "function fire(type,x,y){button.dispatchEvent(new PointerEvent(type,{bubbles:true,pointerId:9,clientX:x,clientY:y,button:0}));}"
                        + "fire('pointerdown',200,600);fire('pointermove',150,560);fire('pointerup',150,560);"
                        + "fire('pointerdown',150,560);fire('pointercancel',150,560);"
                        + "var dragLeft=parseFloat(button.style.left),dragBottom=parseFloat(button.style.bottom);"
                        + "var dragSaved=JSON.parse(localStorage.getItem('intl_doubao_media_panel_pos_v3'));"
                        + "fire('pointerdown',150,560);fire('pointermove',160,555);fire('pointercancel',160,555);"
                        + "var cancelledSaved=JSON.parse(localStorage.getItem('intl_doubao_media_panel_pos_v3'));"
                        + "Object.defineProperty(window,'innerWidth',{configurable:true,value:100});window.dispatchEvent(new Event('resize'));"
                        + "var resizeSaved=JSON.parse(localStorage.getItem('intl_doubao_media_panel_pos_v3'));"
                        + "return JSON.stringify({left:left,bottom:bottom,dragLeft:dragLeft,dragBottom:dragBottom,dragSaved:dragSaved,cancelledSaved:cancelledSaved,resizedLeft:parseFloat(button.style.left),resizedWidth:button.offsetWidth,resizeSaved:resizeSaved,enabled:JSON.parse(window.getDola30sStatus()).on});"
                        + "})()", value -> { result.set(value); finished.countDown(); });
                }
            });
            view.loadDataWithBaseURL("https://www.dola.com/", "<!doctype html><html><body>drag fixture</body></html>",
                "text/html", "UTF-8", null);
        });
        assertTrue("WebView drag fixture did not finish", finished.await(20, TimeUnit.SECONDS));
        Object decoded = new JSONTokener(result.get()).nextValue();
        assertTrue(decoded instanceof String);
        JSONObject state = new JSONObject((String) decoded);
        assertFalse(state.optBoolean("missing", false));
        assertEquals(state.getInt("left") - 50, state.getInt("dragLeft"));
        assertEquals(state.getInt("bottom") + 40, state.getInt("dragBottom"));
        assertEquals(state.getInt("dragLeft"), state.getJSONObject("dragSaved").getInt("left"));
        assertEquals(state.getInt("dragBottom"), state.getJSONObject("dragSaved").getInt("bottom"));
        assertEquals(state.getInt("dragLeft") + 10, state.getJSONObject("cancelledSaved").getInt("left"));
        assertEquals(state.getInt("dragBottom") + 5, state.getJSONObject("cancelledSaved").getInt("bottom"));
        assertTrue(state.getInt("resizedLeft") >= 8);
        assertTrue(state.getInt("resizedLeft") + state.getInt("resizedWidth") <= 92);
        assertEquals(state.getInt("resizedLeft"), state.getJSONObject("resizeSaved").getInt("left"));
        assertFalse("Pointer movement must not toggle the 30S control", state.getBoolean("enabled"));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(viewRef.get()::destroy);
    }

    @Test public void originalDurationScriptCanRunOptInWithoutAdapterOverwritingItsApi() throws Exception {
        assertTrue("WebView provider lacks document-start JavaScript support",
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT));
        String promptTools = readAsset("prompt-tools.js");
        String original = readAsset("dola30-original/duration-main.js");
        String adapter = readAsset("dola30-android-adapter.js");
        AtomicReference<WebView> viewRef = new AtomicReference<>();
        AtomicReference<String> pageResultHolder = new AtomicReference<>();
        CountDownLatch originalLoaded = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            WebView view = new WebView(context);
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setDomStorageEnabled(true);
            viewRef.set(view);
            Set<String> origins = new HashSet<>(Arrays.asList("https://dola.com", "https://*.dola.com"));
            try {
                WebViewCompat.addDocumentStartJavaScript(view, promptTools, origins);
                WebViewCompat.addDocumentStartJavaScript(view, "window.__dola30ExtOff=false;\n" + original, origins);
                WebViewCompat.addDocumentStartJavaScript(view, "window.__originalDola30Setter=window.setDola30s;", origins);
                WebViewCompat.addDocumentStartJavaScript(view, adapter, origins);
            } catch (Exception e) {
                throw new AssertionError("Could not install the opted-in original duration script", e);
            }
            view.setWebViewClient(new android.webkit.WebViewClient() {
                @Override public void onPageFinished(WebView webView, String url) {
                    webView.evaluateJavascript("(function(){return JSON.stringify({"
                        + "installed:!!window.__watermarkFreeDuration30Installed,"
                        + "setterPreserved:window.setDola30s===window.__originalDola30Setter,"
                        + "previewAvailable:typeof window.rewriteDolaTimeline==='function',"
                        + "optionsAvailable:typeof window.dola30Android.getOriginalOptions==='function'&&typeof window.dola30Android.setOriginalOptions==='function',"
                        + "initialOptions:window.dola30Android.getOriginalOptions(),"
                        + "changedOptions:window.dola30Android.setOriginalOptions({promptGuard:true,timelineRewrite:false}),"
                        + "networkRewrite:window.dola30Android.limitations.networkRewrite,"
                        + "autoSubmit:window.dola30Android.limitations.autoSubmit});})()",
                        value -> { pageResultHolder.set(value); originalLoaded.countDown(); });
                }
            });
            view.loadDataWithBaseURL("https://www.dola.com/", "<!doctype html><html><head></head><body>original script test</body></html>",
                "text/html", "UTF-8", null);
        });
        assertTrue("Original script did not initialize in WebView", originalLoaded.await(20, TimeUnit.SECONDS));
        Object decoded = new JSONTokener(pageResultHolder.get()).nextValue();
        assertTrue(decoded instanceof String);
        JSONObject result = new JSONObject((String) decoded);
        assertTrue(result.getBoolean("installed"));
        assertTrue(result.getBoolean("setterPreserved"));
        assertTrue(result.getBoolean("previewAvailable"));
        assertTrue(result.getBoolean("optionsAvailable"));
        assertFalse(result.getJSONObject("initialOptions").getBoolean("promptGuard"));
        assertTrue(result.getJSONObject("initialOptions").getBoolean("timelineRewrite"));
        assertTrue(result.getJSONObject("changedOptions").getBoolean("promptGuard"));
        assertFalse(result.getJSONObject("changedOptions").getBoolean("timelineRewrite"));
        assertTrue(result.getBoolean("networkRewrite"));
        assertFalse(result.getBoolean("autoSubmit"));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(viewRef.get()::destroy);
    }

    @Test public void originalDurationScriptRewritesOnlyMatchingUserTimelineInFetchAndXhrInWebView() throws Exception {
        assertTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT));
        String original = readAsset("dola30-original/duration-main.js");
        String adapter = readAsset("dola30-android-adapter.js");
        AtomicReference<WebView> viewRef = new AtomicReference<>();
        AtomicReference<String> resultHolder = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            WebView view = new WebView(context);
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setDomStorageEnabled(true);
            viewRef.set(view);
            Set<String> origins = new HashSet<>(Arrays.asList("https://doubao.com", "https://*.doubao.com"));
            try {
                WebViewCompat.addDocumentStartJavaScript(view,
                    "window.__capturedRequests=[];window.fetch=function(url,init){window.__capturedRequests.push({url:String(url),body:init&&init.body});return Promise.resolve({ok:true,status:200})};"
                        + "window.__capturedXhrs=[];window.XMLHttpRequest=function(){};"
                        + "window.XMLHttpRequest.prototype.open=function(method,url){this.__url=url};"
                        + "window.XMLHttpRequest.prototype.send=function(body){window.__capturedXhrs.push({url:this.__url,body:body})};", origins);
                WebViewCompat.addDocumentStartJavaScript(view,
                    "window.__dola30ExtOff=false;\n" + original, origins);
                WebViewCompat.addDocumentStartJavaScript(view, adapter, origins);
            } catch (Exception e) {
                throw new AssertionError("Could not install original script in the WebView request fixture", e);
            }
            view.setWebViewClient(new android.webkit.WebViewClient() {
                @Override public void onPageFinished(WebView webView, String url) {
                    String script = "(function(){try{"
                        + "window.dola30Android.setOriginalOptions({promptGuard:false,timelineRewrite:true});"
                        + "window.setDola30s(true);"
                        + "var matching=JSON.stringify({messages:[{role:'user',content:'00:00-00:05 开场'},{role:'system',content:'00:00-00:05 固定指令'}]});"
                        + "var unrelated=JSON.stringify({messages:[{role:'user',content:'00:00-00:05 不应改写'}]});"
                        + "window.fetch('https://www.doubao.com/api/chat/completion',{method:'POST',body:matching});"
                        + "window.fetch('https://www.doubao.com/api/other',{method:'POST',body:unrelated});"
                        + "var xhrMatch=new XMLHttpRequest();xhrMatch.open('POST','https://www.doubao.com/api/chat/completion');xhrMatch.send(matching);"
                        + "var xhrOther=new XMLHttpRequest();xhrOther.open('POST','https://www.doubao.com/api/other');xhrOther.send(unrelated);"
                        + "window.dola30Android.setOriginalOptions({promptGuard:false,timelineRewrite:false});"
                        + "var disabled=JSON.stringify({messages:[{role:'user',content:'00:00-00:05 关闭后应保持原文'}]});"
                        + "window.fetch('https://www.doubao.com/api/chat/completion',{method:'POST',body:disabled});"
                        + "return JSON.stringify({status:JSON.parse(window.getDola30sStatus()),options:window.dola30Android.getOriginalOptions(),requests:window.__capturedRequests,xhrs:window.__capturedXhrs});"
                        + "}catch(e){return JSON.stringify({__jsError:String(e),stack:String(e.stack||'')})}})()";
                    webView.evaluateJavascript(script, value -> { resultHolder.set(value); finished.countDown(); });
                }
            });
            view.loadDataWithBaseURL("https://www.doubao.com/", "<!doctype html><html><head></head><body>request fixture</body></html>",
                "text/html", "UTF-8", null);
        });
        assertTrue("Original WebView request fixture did not finish", finished.await(20, TimeUnit.SECONDS));
        Object decoded = new JSONTokener(resultHolder.get()).nextValue();
        assertTrue("Unexpected WebView evaluation result: " + resultHolder.get(), decoded instanceof String);
        JSONObject result = new JSONObject((String) decoded);
        assertFalse("WebView fixture JavaScript failed: " + result.optString("__jsError") + " " + result.optString("stack"),
            result.has("__jsError"));
        assertTrue(result.getJSONObject("status").getBoolean("on"));
        JSONArray requests = result.getJSONArray("requests");
        assertEquals(3, requests.length());
        JSONObject rewrittenPayload = new JSONObject(requests.getJSONObject(0).getString("body"));
        JSONArray messages = rewrittenPayload.getJSONArray("messages");
        assertTrue(messages.getJSONObject(0).getString("content").contains("16.7%"));
        assertEquals("00:00-00:05 固定指令", messages.getJSONObject(1).getString("content"));
        JSONObject unrelatedPayload = new JSONObject(requests.getJSONObject(1).getString("body"));
        assertEquals("00:00-00:05 不应改写", unrelatedPayload.getJSONArray("messages").getJSONObject(0).getString("content"));
        JSONObject disabledPayload = new JSONObject(requests.getJSONObject(2).getString("body"));
        assertEquals("00:00-00:05 关闭后应保持原文", disabledPayload.getJSONArray("messages").getJSONObject(0).getString("content"));
        assertFalse(result.getJSONObject("options").getBoolean("timelineRewrite"));
        assertFalse(result.getJSONObject("options").getBoolean("promptGuard"));
        JSONArray xhrs = result.getJSONArray("xhrs");
        assertEquals(2, xhrs.length());
        JSONObject xhrPayload = new JSONObject(xhrs.getJSONObject(0).getString("body"));
        JSONArray xhrMessages = xhrPayload.getJSONArray("messages");
        assertTrue(xhrMessages.getJSONObject(0).getString("content").contains("16.7%"));
        assertEquals("00:00-00:05 固定指令", xhrMessages.getJSONObject(1).getString("content"));
        JSONObject xhrUnrelatedPayload = new JSONObject(xhrs.getJSONObject(1).getString("body"));
        assertEquals("00:00-00:05 不应改写", xhrUnrelatedPayload.getJSONArray("messages").getJSONObject(0).getString("content"));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(viewRef.get()::destroy);
    }

    @Test public void observedMediaLinkCopyWritesExactUrlToClipboard() {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        assertNotNull(clipboard);
        String fixture = "https://cdn.example.test/media?id=abc%2F123&quality=hd";
        try {
            clipboard.clearPrimaryClip();
            assertTrue(BrowserActivity.writeMediaUrlToClipboard(context, fixture));
            ClipData clip = BrowserActivity.mediaUrlClip(fixture);
            assertNotNull(clip);
            assertEquals(fixture, clip.getItemAt(0).coerceToText(context).toString());
            assertNull(BrowserActivity.mediaUrlClip("  "));
            assertFalse(BrowserActivity.writeMediaUrlToClipboard(context, "  "));
        } finally {
            clipboard.clearPrimaryClip();
        }
    }

    @Test public void manualRescanFindsDomMediaInTrustedWebViewPage() throws Exception {
        assertTrue("WebView provider lacks document-start JavaScript support",
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT));
        String probe = readAsset("media-probe.js");
        AtomicReference<WebView> viewRef = new AtomicReference<>();
        AtomicReference<String> resultHolder = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            WebView view = new WebView(context);
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setBlockNetworkLoads(true);
            viewRef.set(view);
            try {
                WebViewCompat.addDocumentStartJavaScript(view, probe,
                    new HashSet<>(Arrays.asList("https://dola.com", "https://*.dola.com")));
            } catch (Exception e) {
                throw new AssertionError("Could not install the media observer", e);
            }
            view.setWebViewClient(new android.webkit.WebViewClient() {
                @Override public void onPageFinished(WebView webView, String url) {
                    webView.evaluateJavascript("(function(){window.__dolaAndroidScan();return window.__dolaAndroidDrain()})()",
                        value -> { resultHolder.set(value); finished.countDown(); });
                }
            });
            view.loadDataWithBaseURL("https://www.dola.com/", "<!doctype html><picture>"
                    + "<source srcset=\"https://www.dola.com/picture-1.avif?sig=c%2Fd 1x, "
                    + "https://www.dola.com/picture-2.avif?sig=e%2Ff 2x\">"
                    + "<img src=\"https://www.dola.com/media.jpg?sig=a%2Fb\"></picture>"
                    + "<video src=\"https://www.dola.com/movie.mp4?sig=g%2Fh\" "
                    + "poster=\"https://www.dola.com/poster.jpg?sig=i%2Fj\">"
                    + "<source src=\"https://www.dola.com/movie-alt.webm?sig=k%2Fl\"></video>",
                "text/html", "UTF-8", null);
        });
        assertTrue("Trusted media fixture did not finish", finished.await(20, TimeUnit.SECONDS));
        JSONArray media = new JSONArray(resultHolder.get());
        java.util.Map<String, String> mediaTypes = new java.util.HashMap<>();
        for (int i = 0; i < media.length(); i++) {
            JSONObject item = media.getJSONObject(i);
            mediaTypes.put(item.getString("url"), item.getString("type"));
        }
        assertEquals("image", mediaTypes.get("https://www.dola.com/media.jpg?sig=a%2Fb"));
        assertEquals("image", mediaTypes.get("https://www.dola.com/picture-1.avif?sig=c%2Fd"));
        assertEquals("image", mediaTypes.get("https://www.dola.com/picture-2.avif?sig=e%2Ff"));
        assertEquals("video", mediaTypes.get("https://www.dola.com/movie.mp4?sig=g%2Fh"));
        assertEquals("image", mediaTypes.get("https://www.dola.com/poster.jpg?sig=i%2Fj"));
        assertEquals("video", mediaTypes.get("https://www.dola.com/movie-alt.webm?sig=k%2Fl"));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(viewRef.get()::destroy);
    }

    private String readAsset(String name) throws Exception {
        try (InputStream in = context.getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
    @Test public void privacyModeUsesNativeWebViewIdentityAndCanRestoreCookiePolicy() {
        AtomicReference<WebView> viewRef = new AtomicReference<>();
        AtomicReference<String> nativeAgentRef = new AtomicReference<>();
        AtomicReference<String> cleanAgentRef = new AtomicReference<>();
        AtomicReference<String> restoredAgentRef = new AtomicReference<>();
        AtomicReference<Boolean> cleanCookiesRef = new AtomicReference<>();
        AtomicReference<Boolean> restoredCookiesRef = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            WebView view = new WebView(context);
            viewRef.set(view);
            String nativeAgent = view.getSettings().getUserAgentString();
            nativeAgentRef.set(nativeAgent);
            view.getSettings().setUserAgentString(BrowserActivity.userAgentForPrivacyMode(nativeAgent, true));
            CookieManager.getInstance().setAcceptThirdPartyCookies(view,
                BrowserActivity.acceptThirdPartyCookiesInPrivacyMode(true));
            cleanAgentRef.set(view.getSettings().getUserAgentString());
            cleanCookiesRef.set(CookieManager.getInstance().acceptThirdPartyCookies(view));
        });
        WebView view = viewRef.get();
        assertEquals(nativeAgentRef.get(), cleanAgentRef.get());
        assertFalse(cleanCookiesRef.get());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            view.getSettings().setUserAgentString(BrowserActivity.userAgentForPrivacyMode(nativeAgentRef.get(), false));
            CookieManager.getInstance().setAcceptThirdPartyCookies(view,
                BrowserActivity.acceptThirdPartyCookiesInPrivacyMode(false));
            restoredAgentRef.set(view.getSettings().getUserAgentString());
            restoredCookiesRef.set(CookieManager.getInstance().acceptThirdPartyCookies(view));
        });
        assertEquals(nativeAgentRef.get() + " DoulalaAndroid/0.2", restoredAgentRef.get());
        assertTrue(restoredCookiesRef.get());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(view::destroy);
    }

}

