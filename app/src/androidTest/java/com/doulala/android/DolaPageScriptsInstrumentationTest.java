package com.doulala.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Disposable-emulator tests only. No live Dola requests, accounts or uploads. */
@RunWith(AndroidJUnit4.class)
public class DolaPageScriptsInstrumentationTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test public void bothSourcesRunBeforeFirstPageScriptWithoutChromeMocks() throws Exception {
        JSONObject early=loadFixture("https://www.dola.com/",true,true);
        assertTrue(early.getBoolean("skill"));assertTrue(early.getBoolean("upload"));
        assertTrue(early.getBoolean("fetchHook"));assertTrue(early.getBoolean("xhrHook"));
        assertEquals("30",early.getString("duration"));
        assertTrue(early.getJSONObject("state").getBoolean("storageSeeded"));
        assertFalse(early.getBoolean("chromeDebugger"));assertFalse(early.getBoolean("chromeRuntime"));
        assertFalse(early.getBoolean("uploadResult"));
        for(String marker:Arrays.asList("legacyV150","legacyUploadV1","legacyUploadV2","legacyUploadResult","legacyAndroidAdapter","legacyFingerprintPrivacy","legacyAndroidDrain","legacyPromptTools","legacyWatermark")) assertFalse(marker,early.getBoolean(marker));
        assertTrue(early.getBoolean("hostWebViewState"));
        assertTrue(early.getBoolean("sharedNormalizer"));
        assertFalse(early.getJSONObject("state").getBoolean("backendVerified"));
    }
    @Test public void disablingCreatesAHookFreeNewDocumentAndResetsManagedDuration() throws Exception {
        assertTrue(loadFixture("https://dola.com/",true,true).getBoolean("skill"));
        JSONObject off=loadFixture("https://dola.com/",false,true);
        assertFalse(off.getBoolean("skill"));assertFalse(off.getBoolean("upload"));
        assertFalse(off.getBoolean("fetchHook"));assertFalse(off.getBoolean("xhrHook"));
        assertEquals("15",off.getString("duration"));
        assertFalse(off.getJSONObject("state").getBoolean("enabled"));
    }
    @Test public void otherOriginsNeverReceiveAttachmentSources() throws Exception {
        for(String url:Arrays.asList("https://www.doubao.com/","https://dola.com.example.org/","https://example.org/")) {
            JSONObject result=loadFixture(url,true,true);
            assertFalse(url,result.getBoolean("skill"));assertFalse(url,result.getBoolean("upload"));
            assertTrue(url,result.isNull("state"));
        }
    }
    @Test public void additionalSourcesRequireConsentAndRespectIdleStage() throws Exception {
        JSONObject previous=loadFixture("https://www.dola.com/",true,true,false);
        assertFalse(previous.getBoolean("durationIdle"));assertFalse(previous.getBoolean("mediaObserver"));
        JSONObject full=loadFixture("https://www.dola.com/",true,true,true);
        assertTrue(full.getBoolean("skill"));assertTrue(full.getBoolean("upload"));
        assertFalse("duration must not execute before the first inline page script",full.getBoolean("legacyWatermark"));
        assertTrue(full.getBoolean("durationIdle"));assertTrue(full.getBoolean("mediaObserver"));
        assertFalse(full.getBoolean("chromeRuntime"));assertTrue(full.getJSONArray("mediaItems").length()>0);
        JSONObject doubao=loadFixture("https://www.doubao.com/",true,true,true);
        assertFalse(doubao.getBoolean("skill"));assertFalse(doubao.getBoolean("upload"));
        assertTrue(doubao.getBoolean("durationIdle"));assertTrue(doubao.getBoolean("mediaObserver"));
        JSONObject off=loadFixture("https://www.doubao.com/",false,true,true);
        assertFalse(off.getBoolean("durationIdle"));assertFalse(off.getBoolean("mediaObserver"));assertEquals("15",off.getString("duration"));
    }
    @Test public void spaceSettingsPersistIndependentlyAndDoNotMigrateLegacyOptIn() {
        SharedPreferences a=context.getSharedPreferences(DolaPageScripts.preferenceName("Space0"),Context.MODE_PRIVATE);
        SharedPreferences b=context.getSharedPreferences(DolaPageScripts.preferenceName("Space1"),Context.MODE_PRIVATE);
        boolean hadA=a.contains(DolaPageScripts.PREF_KEY),hadB=b.contains(DolaPageScripts.PREF_KEY);
        boolean oldA=a.getBoolean(DolaPageScripts.PREF_KEY,false),oldB=b.getBoolean(DolaPageScripts.PREF_KEY,false);
        boolean hadLegacy=a.contains("originalDola30"),oldLegacy=a.getBoolean("originalDola30",false);
        boolean hadOldKey=a.contains("dola30PageScripts"),oldKey=a.getBoolean("dola30PageScripts",false);
        try {
            assertTrue(a.edit().remove(DolaPageScripts.PREF_KEY).putBoolean("originalDola30",true).putBoolean("dola30PageScripts",true).commit());
            assertFalse("Legacy request opt-in must not enable new page scripts",a.getBoolean(DolaPageScripts.PREF_KEY,false));
            assertTrue(a.getBoolean("dola30PageScripts",false));
            assertTrue(a.edit().putBoolean(DolaPageScripts.PREF_KEY,true).commit());
            assertTrue(b.edit().putBoolean(DolaPageScripts.PREF_KEY,false).commit());
            assertTrue(context.getSharedPreferences("browser_Space0",Context.MODE_PRIVATE).getBoolean(DolaPageScripts.PREF_KEY,false));
            assertFalse(context.getSharedPreferences("browser_Space1",Context.MODE_PRIVATE).getBoolean(DolaPageScripts.PREF_KEY,true));
            assertTrue(a.edit().putBoolean(DolaPageScripts.PREF_KEY,false).commit());
            assertFalse(b.getBoolean(DolaPageScripts.PREF_KEY,true));
        } finally {
            SharedPreferences.Editor x=a.edit(),y=b.edit();
            if(hadA)x.putBoolean(DolaPageScripts.PREF_KEY,oldA);else x.remove(DolaPageScripts.PREF_KEY);
            if(hadB)y.putBoolean(DolaPageScripts.PREF_KEY,oldB);else y.remove(DolaPageScripts.PREF_KEY);
            if(hadLegacy)x.putBoolean("originalDola30",oldLegacy);else x.remove("originalDola30");
            if(hadOldKey)x.putBoolean("dola30PageScripts",oldKey);else x.remove("dola30PageScripts");
            assertTrue(x.commit());assertTrue(y.commit());
        }
    }
    private JSONObject loadFixture(String origin,boolean enabled,boolean managed)throws Exception {return loadFixture(origin,enabled,managed,false);}
    private JSONObject loadFixture(String origin,boolean enabled,boolean managed,boolean additional)throws Exception {
        assertTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT));
        String bundle=DolaPageScripts.bootstrap(enabled,managed,additional,read(DolaPageScripts.SKILL_ASSET),read(DolaPageScripts.UPLOAD_ASSET),additional?read(DolaPageScripts.DURATION_ASSET):null,additional?read(DolaPageScripts.MEDIA_ASSET):null);
        AtomicReference<WebView> ref=new AtomicReference<>();AtomicReference<String> result=new AtomicReference<>();
        CountDownLatch done=new CountDownLatch(1);
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                WebView view=new WebView(context);ref.set(view);
                view.getSettings().setJavaScriptEnabled(true);view.getSettings().setDomStorageEnabled(true);
                view.getSettings().setBlockNetworkLoads(true);
                // Same single registration boundary as production. No Chrome API shims.
                WebViewCompat.addDocumentStartJavaScript(view,
                    "window.__beforeFetch=window.fetch;window.__beforeOpen=XMLHttpRequest.prototype.open;"+bundle,
                    new HashSet<>(Arrays.asList("https://dola.com","https://*.dola.com","https://doubao.com","https://*.doubao.com")));
                view.setWebViewClient(new WebViewClient(){@Override public void onPageFinished(WebView v,String url){
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->v.evaluateJavascript("JSON.stringify(Object.assign({},window.__early,{durationIdle:!!window.__watermarkFreeDuration30Installed,mediaObserver:!!window.__dolaNewMediaState,mediaItems:window.__dolaNewMediaState?window.__dolaNewMediaState.items:[]}))",s->{result.set(s);done.countDown();}),250);
                }});
                String html="<!doctype html><html><head><script>window.__early={"
                    +"skill:!!window.__dolaNative30SkillPackInstalledV162,upload:!!window.__DOLA_TOOLKIT_MEDIA_UPLOAD_BRIDGE_V3__,"
                    +"fetchHook:!!window.__beforeFetch&&window.fetch!==window.__beforeFetch,xhrHook:!!window.__beforeOpen&&XMLHttpRequest.prototype.open!==window.__beforeOpen,"
                    +"duration:localStorage.getItem('intl_doubao_target_duration_v1'),state:window.__dolaWebViewPageScripts||null,"
                    +"chromeDebugger:!!(window.chrome&&window.chrome.debugger),chromeRuntime:!!(window.chrome&&window.chrome.runtime),"
                    +"uploadResult:!!window.__DOLA_TOOLKIT_MEDIA_UPLOAD__,"
                    +"legacyV150:!!window.__dolaNative30SkillPackInstalledV150,legacyUploadV1:!!window.__DOLA_TOOLKIT_MEDIA_UPLOAD_BRIDGE_V1__,"
                    +"legacyUploadV2:!!window.__DOLA_TOOLKIT_MEDIA_UPLOAD_BRIDGE_V2__,legacyUploadResult:!!window.__DOLA_TOOLKIT_MEDIA_UPLOAD__,hostWebViewState:!!window.__dolaWebViewPageScripts,sharedNormalizer:!!window.__DOLA_TOOLKIT_UPLOAD_URL_NORMALIZER__,legacyAndroidAdapter:!!window.__dola30AndroidAdapterInstalled,legacyFingerprintPrivacy:!!window.__dolaFingerprintPrivacy,legacyAndroidDrain:typeof window.__dolaAndroidDrain===\'function\',legacyPromptTools:!!window.DolaPromptTools,legacyWatermark:!!window.__watermarkFreeDuration30Installed};</script></head><body>local fixture<video src=\"https://media.example.test/generated.mp4\" poster=\"https://media.example.test/poster.jpg\"></video></body></html>";
                view.loadDataWithBaseURL(origin,html,"text/html","UTF-8",null);
            });
            assertTrue("Document-start fixture timed out",done.await(20,TimeUnit.SECONDS));
            Object value=new JSONTokener(result.get()).nextValue();assertTrue(value instanceof String);
            return new JSONObject((String)value);
        } finally {
            if(ref.get()!=null)InstrumentationRegistry.getInstrumentation().runOnMainSync(ref.get()::destroy);
        }
    }
    private String read(String name)throws Exception {
        try(InputStream in=context.getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
}
