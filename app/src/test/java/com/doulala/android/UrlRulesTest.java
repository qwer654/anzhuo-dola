package com.doulala.android;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class UrlRulesTest {
    @Test public void normalizeAcceptsWebUrlsOnly() {
        assertEquals("https://example.com/a?q=1", UrlRules.normalize("example.com/a?q=1"));
        assertNull(UrlRules.normalize("javascript:alert(1)"));
        assertNull(UrlRules.normalize("file:///sdcard/private"));
        assertNull(UrlRules.normalize("https://user:password@example.com/a"));
        assertNull(UrlRules.normalize("https://example.com/a\n" + "b"));
        assertNull(UrlRules.normalize(null));
    }
    @Test public void scriptsOnlyTrustHttpsExactOrigins() {
        assertTrue(UrlRules.trusted("https://www.dola.com/chat/"));
        assertTrue(UrlRules.trusted("https://doubao.com:443/chat/"));
        assertFalse(UrlRules.trusted("http://dola.com"));
        assertFalse(UrlRules.trusted("https://dola.com.evil.test"));
        assertFalse(UrlRules.trusted("https://evil-dola.com"));
        assertFalse(UrlRules.trusted("https://dola.com:444"));
        assertFalse(UrlRules.trusted("https://user@dola.com"));
        assertFalse(UrlRules.trusted(null));
    }
    @Test public void privacyModeKeepsNativeUserAgentAndBlocksThirdPartyCookies() {
        String nativeAgent="Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/153.0.0.0 Mobile Safari/537.36";
        assertEquals(nativeAgent,BrowserActivity.userAgentForPrivacyMode(nativeAgent,true));
        assertFalse(BrowserActivity.acceptThirdPartyCookiesInPrivacyMode(true));
        assertFalse(BrowserActivity.geolocationEnabledInPrivacyMode(true));
        assertEquals(nativeAgent+" DoulalaAndroid/0.2",BrowserActivity.userAgentForPrivacyMode(nativeAgent,false));
        assertTrue(BrowserActivity.acceptThirdPartyCookiesInPrivacyMode(false));
        assertTrue(BrowserActivity.geolocationEnabledInPrivacyMode(false));
    }
    @Test public void privacyFingerprintBootstrapIsOptInAndSeedsApiReductionsWithoutIdentitySpoofing() {
        assertNull(BrowserActivity.privacyFingerprintBootstrap("script", false));
        String bootstrap=BrowserActivity.privacyFingerprintBootstrap("script", true);
        assertTrue(bootstrap.startsWith("window.__dolaFingerprintInitialSettings={blockCanvasReadback:true,hideWebglDebugInfo:true,blockAudioFingerprint:true,blockWebRTC:true,blockDeviceEnumeration:true};\n"));
        assertTrue(bootstrap.endsWith("script"));
        assertFalse(bootstrap.contains("cleanMode:true"));
    }
    @Test public void durationToolExplainsAvailableAndUnavailableParityWithoutOverclaiming() {
        String available=BrowserActivity.durationToolCapabilitySummary(true);
        assertTrue(available.contains("页面资源观察：已注入，仅观察页面已暴露的 URL"));
        assertTrue(available.contains("Debugger 深度/自动嗅探：不可用"));
        assertTrue(available.contains("Android WebView 不提供 Chrome MV3 Debugger"));
        assertTrue(available.contains("fallback API 解析：未实现"));
        String unavailable=BrowserActivity.durationToolCapabilitySummary(false);
        assertTrue(unavailable.contains("页面资源观察：未启用"));
        assertTrue(unavailable.contains("fallback API 解析：未实现"));
    }
    @Test public void filenamesAreBoundedAndFlat() {
        String file = UrlRules.safeFilename("../../secret:?.mp4", "media.mp4");
        assertFalse(file.contains("/"));
        assertFalse(file.contains(":"));
        assertFalse(file.startsWith("."));
        assertEquals("media.mp4",UrlRules.safeFilename("", "media.mp4"));
        String longName = UrlRules.safeFilename("x".repeat(200)+".mp4","media.mp4");
        assertTrue(longName.length()<=120);
        assertTrue(longName.endsWith(".mp4"));
    }
    @Test public void canonicalizesSchemeWithoutReencodingSignedQueries(){
        assertEquals("https://example.com/a%2Fb?sig=a%2Bb%3D",UrlRules.normalize("HTTPS://example.com/a%2Fb?sig=a%2Bb%3D"));
        assertEquals("https://example.com",UrlRules.origin("HTTPS://EXAMPLE.COM:443/path?q=1"));
        assertEquals("https://example.com:444",UrlRules.origin("https://example.com:444/"));
        assertEquals("https://[::1]",UrlRules.origin("https://[::1]:443/a"));
        assertNull(UrlRules.normalize("https://example.com\r\n"));
    }
    @Test public void bulkCopyPreservesExactHttpsUrlsAndTheirSignedQueries() {
        assertEquals("https://cdn.example/a.mp4?sig=a%2Fb\nhttps://cdn.example/b.jpg?id=2&token=x%3Dy",
            BrowserActivity.joinMediaUrls(Arrays.asList(
                "https://cdn.example/a.mp4?sig=a%2Fb",
                "https://cdn.example/b.jpg?id=2&token=x%3Dy")));
    }
    @Test public void bulkCopyOmitsUnsafeAndEmptyUrls() {
        assertEquals("https://cdn.example/ok.jpg", BrowserActivity.joinMediaUrls(Arrays.asList(
            "javascript:alert(1)", "http://cdn.example/insecure.jpg", "https://cdn.example/ok.jpg", null)));
        assertEquals("", BrowserActivity.joinMediaUrls(null));
    }

}

