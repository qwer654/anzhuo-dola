package com.doulala.android;

import org.junit.Test;
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
    @Test public void platformSelectionLabelsRemainReadOnlyAndNonPromissory(){
        assertTrue(BrowserActivity.platformSelectionSummary("selection_matches_30s").contains("只读"));
        assertTrue(BrowserActivity.platformSelectionSummary("selection_matches_30s").contains("不代表服务端接受"));
        assertTrue(BrowserActivity.platformSelectionSummary("detected_not_30s").contains("当前选择不是"));
        assertTrue(BrowserActivity.platformSelectionSummary("not_applicable").contains("仅适用于 Dola"));
        assertTrue(BrowserActivity.platformSelectionSummary("controls_not_detected").contains("不推断平台能力"));
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
    @Test public void mediaShareUrlAcceptsOnlyCompleteValidatedHttpsUrls(){
        String url="https://cdn.example.test/media?id=abc%2F123&quality=hd";
        assertEquals(url,BrowserActivity.validatedMediaShareUrl(url));
        assertEquals(url,BrowserActivity.validatedMediaShareUrl("HTTPS://cdn.example.test/media?id=abc%2F123&quality=hd"));
        assertNull(BrowserActivity.validatedMediaShareUrl(null));
        assertNull(BrowserActivity.validatedMediaShareUrl("  "));
        assertNull(BrowserActivity.validatedMediaShareUrl("cdn.example.test/media.mp4"));
        assertNull(BrowserActivity.validatedMediaShareUrl(" https://cdn.example.test/media.mp4"));
        assertNull(BrowserActivity.validatedMediaShareUrl("http://cdn.example.test/media.mp4"));
        assertNull(BrowserActivity.validatedMediaShareUrl("javascript:alert(1)"));
        assertNull(BrowserActivity.validatedMediaShareUrl("blob:https://dola.com/id"));
        assertNull(BrowserActivity.validatedMediaShareUrl("data:text/plain,secret"));
        assertNull(BrowserActivity.validatedMediaShareUrl("https://user:pass@cdn.example.test/media.mp4"));
    }
    @Test public void clearingObservedMediaAlsoClearsDeduplicationKeys(){
        java.util.List<String> items=new java.util.ArrayList<>();items.add("https://cdn.example.test/a.jpg");
        java.util.Set<String> keys=new java.util.HashSet<>();keys.add("image|https://cdn.example.test/a.jpg");
        BrowserActivity.clearObservedMedia(items,keys);
        assertTrue(items.isEmpty());assertTrue(keys.isEmpty());
    }
    @Test public void mediaPreviewAcceptsOnlyCredentialFreeHttpsImages(){
        assertNotNull(MediaPreview.validateImageUrl("https://cdn.example.test/a.png?sig=x%2Fy", "image"));
        assertNull(MediaPreview.validateImageUrl("http://cdn.example.test/a.png", "image"));
        assertNull(MediaPreview.validateImageUrl("https://user:pass@cdn.example.test/a.png", "image"));
        assertNull(MediaPreview.validateImageUrl("https://cdn.example.test/a.png", "video"));
        assertNull(MediaPreview.validateImageUrl(" https://cdn.example.test/a.png", "image"));
        assertNull(MediaPreview.validateImageUrl("https://cdn.example.test:444/a.png", "image"));
        assertEquals(8*1024*1024,MediaPreview.MAX_BYTES);
    }
    @Test public void downloadStatusLabelsMatchSystemDownloadManagerLifecycle(){
        assertEquals("等待下载",DownloadCenter.statusLabel(android.app.DownloadManager.STATUS_PENDING,0));
        assertEquals("下载中",DownloadCenter.statusLabel(android.app.DownloadManager.STATUS_RUNNING,0));
        assertTrue(DownloadCenter.statusLabel(android.app.DownloadManager.STATUS_PAUSED,android.app.DownloadManager.PAUSED_WAITING_FOR_NETWORK).contains("暂停 / 等待网络"));
        assertEquals("已完成",DownloadCenter.statusLabel(android.app.DownloadManager.STATUS_SUCCESSFUL,0));
        assertTrue(DownloadCenter.statusLabel(android.app.DownloadManager.STATUS_FAILED,android.app.DownloadManager.ERROR_HTTP_DATA_ERROR).contains("失败"));
        assertEquals("系统记录已不存在",DownloadCenter.statusLabel(-1,0));
    }
    @Test public void directSaveCopyEnforcesDeclaredAndStreamingByteLimit(){
        byte[] data="signed-media".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
        try {assertEquals(data.length,DownloadCenter.copyDirect(new java.io.ByteArrayInputStream(data),out,-1,64));}
        catch(java.io.IOException e){fail(e.toString());}
        assertArrayEquals(data,out.toByteArray());
        try {DownloadCenter.copyDirect(new java.io.ByteArrayInputStream(data),new java.io.ByteArrayOutputStream(),65,64);fail("declared oversize accepted");}
        catch(java.io.IOException expected){}
        try {DownloadCenter.copyDirect(new java.io.ByteArrayInputStream(data),new java.io.ByteArrayOutputStream(),-1,4);fail("stream oversize accepted");}
        catch(java.io.IOException expected){}
    }
    @Test public void liveMediaDialogRefreshIsBoundToVisibleOwnerAndNavigation(){
        assertTrue(BrowserActivity.mediaDialogMayRefresh(true,true,true,true));
        assertFalse(BrowserActivity.mediaDialogMayRefresh(false,true,true,true));
        assertFalse(BrowserActivity.mediaDialogMayRefresh(true,false,true,true));
        assertFalse(BrowserActivity.mediaDialogMayRefresh(true,true,false,true));
        assertFalse(BrowserActivity.mediaDialogMayRefresh(true,true,true,false));
    }
    @Test public void mediaFingerprintDeduplicatesRotatingSignaturesAndKeepsLatestRawUrl(){
        String oldUrl="https://cdn.example.test/v.mp4?id=42&X-Amz-Signature=old%2Bsig&Expires=100&quality=hd";
        String freshUrl="https://cdn.example.test/v.mp4?quality=hd&id=42&X-Amz-Signature=new%2Bsig&Expires=200";
        assertEquals(BrowserActivity.mediaFingerprint(oldUrl),BrowserActivity.mediaFingerprint(freshUrl));
        assertNotEquals(BrowserActivity.mediaFingerprint(oldUrl),BrowserActivity.mediaFingerprint("https://cdn.example.test/v.mp4?id=43&quality=hd&Expires=200"));
        java.util.ArrayList<BrowserActivity.MediaItem> items=new java.util.ArrayList<>();
        java.util.Set<String> keys=new java.util.HashSet<>();
        assertTrue(BrowserActivity.mergeObservedMedia(items,keys,oldUrl,"video","clip",null,java.util.Collections.singletonList(oldUrl)));
        assertTrue(BrowserActivity.mergeObservedMedia(items,keys,freshUrl,"video","clip","https://cdn.example.test/poster.jpg",java.util.Arrays.asList(freshUrl,"https://cdn.example.test/alternate.mp4?sig=alt")));
        assertEquals(1,items.size());
        assertEquals(freshUrl,items.get(0).url);
        assertEquals(freshUrl,items.get(0).channels.get(0));
        assertEquals("https://cdn.example.test/alternate.mp4?sig=alt",items.get(0).channels.get(1));
        assertEquals("https://cdn.example.test/poster.jpg",items.get(0).posterUrl);
        assertEquals(1,keys.size());assertTrue(keys.iterator().next().contains("id=42"));
        assertEquals("https://cdn.example.test/i.png?id=5&signature=two%2Fthree",itemsRawSignatureUnchanged());
    }
    private static String itemsRawSignatureUnchanged(){
        String raw="https://cdn.example.test/i.png?id=5&signature=two%2Fthree";
        java.util.ArrayList<BrowserActivity.MediaItem> items=new java.util.ArrayList<>();
        BrowserActivity.mergeObservedMedia(items,new java.util.HashSet<>(),raw,"image","image",null,java.util.Collections.emptyList());
        return items.get(0).url;
    }
}

