package com.doulala.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.*;
import java.util.*;

/**
 * A conservative Android replacement for the desktop WebView2 host.  It does
 * not spoof platform capabilities and it never sends a request on behalf of
 * the user.  Each declared Space activity has a separate Chromium data dir.
 */
public class BrowserActivity extends Activity {
    private final ArrayList<MediaItem> media = new ArrayList<>();
    private final HashSet<String> mediaKeys = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private EditText address;
    private TextView status;
    private TextView mediaButton;
    private String home;
    private String title;
    private ValueCallback<Uri[]> fileCallback;
    private static final int FILE_CHOOSER = 8101;
    private static final int MAX_MEDIA = 300;
    private boolean destroyed;
    private boolean probeInstalled;
    private int navigationId;
    private final Runnable scanLoop = new Runnable() { @Override public void run() { scan(); if (!destroyed) handler.postDelayed(this, 3000L); } };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.setup(this);
        title = getIntent().getStringExtra("title");
        if (title == null || title.trim().isEmpty()) title = "创作空间";
        home = UrlRules.normalize(getIntent().getStringExtra("home"));
        if (home == null) home = MainActivity.URLS[0];
        build();
        if (state == null || webView.restoreState(state) == null) webView.loadUrl(home);
    }

    private void build() {
        LinearLayout root = Ui.column(this); root.setBackgroundColor(Ui.BG);
        LinearLayout bar = Ui.row(this); Ui.pad(bar, 6);
        TextView back = Ui.text(this, "‹", 28, Ui.INK); back.setGravity(Gravity.CENTER); back.setOnClickListener(v -> goBack());
        bar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 48)));
        TextView forward = Ui.text(this, "›", 28, Ui.INK); forward.setGravity(Gravity.CENTER); forward.setOnClickListener(v -> goForward());
        bar.addView(forward, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 48)));
        address = Ui.edit(this, "输入网页地址", home); address.setSingleLine(true); address.setTextSize(14); address.setSelectAllOnFocus(true);
        address.setOnEditorActionListener((v, action, event) -> { if (action != 0 || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) { loadAddress(); return true; } return false; });
        bar.addView(address, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        TextView reload = Ui.text(this, "↻", 24, Ui.INK); reload.setGravity(Gravity.CENTER); reload.setOnClickListener(v -> webView.reload());
        bar.addView(reload, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 48)));
        TextView homeButton = Ui.text(this, "⌂", 22, Ui.INK); homeButton.setGravity(Gravity.CENTER); homeButton.setOnClickListener(v -> webView.loadUrl(home));
        bar.addView(homeButton, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 48)));
        root.addView(bar);
        LinearLayout second = Ui.row(this); Ui.pad(second, 4);
        TextView caption = Ui.text(this, title + "  ·  独立空间", 12, Ui.MUTED); second.addView(caption, new LinearLayout.LayoutParams(0, -2, 1));
        mediaButton = Ui.text(this, "资源 0", 12, Ui.ACCENT); mediaButton.setGravity(Gravity.CENTER); mediaButton.setOnClickListener(v -> showMedia()); second.addView(mediaButton, new LinearLayout.LayoutParams(Ui.dp(this, 82), Ui.dp(this, 38)));
        TextView studio = Ui.text(this, "工作台", 12, Ui.ACCENT); studio.setGravity(Gravity.CENTER); studio.setOnClickListener(v -> { Intent i = new Intent(this, StudioActivity.class); i.putExtra("pickPrompt", true); startActivityForResult(i, 8102); }); second.addView(studio, new LinearLayout.LayoutParams(Ui.dp(this, 82), Ui.dp(this, 38)));
        root.addView(second);
        status = Ui.text(this, "正在准备网页…", 11, Ui.MUTED); status.setSingleLine(true); status.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE); status.setMarqueeRepeatLimit(1); root.addView(status);
        webView = new WebView(this); configureWebView(webView); root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        installProbe();
    }

    private void configureWebView(WebView view) {
        WebSettings s = view.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setDatabaseEnabled(true); s.setAllowFileAccess(false); s.setAllowContentAccess(true); s.setSupportMultipleWindows(false); s.setJavaScriptCanOpenWindowsAutomatically(false); s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); s.setSafeBrowsingEnabled(true); s.setUserAgentString(s.getUserAgentString() + " DoulalaAndroid/0.1");
        CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);
        view.setOverScrollMode(View.OVER_SCROLL_NEVER);
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) { String scheme=request.getUrl().getScheme(); if(!request.isForMainFrame()) return !"https".equalsIgnoreCase(scheme); return openOnlyWebScheme(request.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return openOnlyWebScheme(Uri.parse(url)); }
            @Override public void onPageStarted(WebView v, String url, Bitmap icon) { navigationId++; media.clear(); mediaKeys.clear(); updateMediaLabel(); address.setText(url); status.setText("正在加载…"); }
            @Override public void onPageFinished(WebView v, String url) { address.setText(url); status.setText(probeInstalled ? "已就绪 · 资源观察只在白名单站点启用" : "已就绪 · 当前 WebView 不支持早期资源观察"); scan(); }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) { if (r.isForMainFrame()) status.setText("加载失败：" + e.getDescription()); }
        });
        view.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int n) { if (n < 100) status.setText("加载 " + n + "%"); }
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) { if (fileCallback != null) fileCallback.onReceiveValue(null); fileCallback = cb; try { Intent i = p.createIntent(); i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i, FILE_CHOOSER); } catch (Exception e) { fileCallback = null; cb.onReceiveValue(null); status.setText("无法打开文件选择器"); } return true; }
        });
    }

    private void installProbe() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) { status.setText("当前 WebView 不支持早期资源观察；可正常浏览"); return; }
        try {
            String script; try (java.io.InputStream in = getAssets().open("media-probe.js"); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) { byte[] b=new byte[8192]; int n; while((n=in.read(b))!=-1) out.write(b,0,n); script = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8); }
            WebViewCompat.addDocumentStartJavaScript(webView, script, new HashSet<>(Arrays.asList("https://doubao.com","https://*.doubao.com","https://dola.com","https://*.dola.com","https://qianwen.com","https://*.qianwen.com","https://jianying.com","https://*.jianying.com")));
            probeInstalled = true;
        } catch (Exception e) { status.setText("资源观察脚本不可用；可正常浏览"); }
    }

    private boolean openOnlyWebScheme(Uri uri) {
        if (uri == null) return true; String scheme = uri.getScheme();
        if ("https".equalsIgnoreCase(scheme)) return false;
        if ("mailto".equalsIgnoreCase(scheme) || "tel".equalsIgnoreCase(scheme)) {
            new AlertDialog.Builder(this).setTitle("打开系统应用？").setMessage(uri.toString())
                .setNegativeButton("取消", null).setPositiveButton("打开", (d,w)->{
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) { status.setText("此链接无法在系统中打开"); }
                }).show();
        } else status.setText("已阻止非 HTTPS 或不支持的链接");
        return true;
    }
    private void loadAddress() { String normalized = UrlRules.normalize(address.getText().toString()); if (normalized == null || !normalized.startsWith("https://")) { address.setError("请输入有效的 HTTPS 地址"); return; } webView.loadUrl(normalized); }
    private void goBack() { if (webView.canGoBack()) webView.goBack(); else finish(); }
    private void goForward() { if (webView.canGoForward()) webView.goForward(); }
    private void scan() {
        if (destroyed || webView == null || !UrlRules.trusted(webView.getUrl())) return;
        final int currentNavigation=navigationId;
        webView.evaluateJavascript("(window.__dolaAndroidDrain?window.__dolaAndroidDrain():[])", value -> {
            if(destroyed || currentNavigation!=navigationId || !UrlRules.trusted(webView.getUrl()) || value==null || value.length()>4*1024*1024) return;
            try { JSONArray a = new JSONArray(value); int added=0; for(int i=0;i<a.length() && media.size()<MAX_MEDIA;i++){
                JSONObject o=a.optJSONObject(i); if(o==null)continue;
                String raw=o.optString("url",null), u=UrlRules.normalize(raw), type=o.optString("type", "");
                if(u==null || !u.startsWith("https://") || !(type.equals("image")||type.equals("video")))continue;
                String key=type+"|"+u; if(mediaKeys.add(key)){String label=o.optString("title","资源"); media.add(new MediaItem(raw,type,label.substring(0,Math.min(label.length(),120))));added++;}
            } if(added>0) updateMediaLabel(); } catch(Exception ignored){}
        });
    }
    private void updateMediaLabel() { mediaButton.setText("资源 " + media.size()); }
    private void showMedia() {
        if (media.isEmpty()) { new AlertDialog.Builder(this).setTitle("资源列表").setMessage("尚未观察到图片或视频。请在白名单站点打开创作页面并完成一次加载。\n\n本版本只观察页面已经暴露的 URL，不请求 fallback_api，不保证原图或无水印结果。").setPositiveButton("知道了", null).show(); return; }
        LinearLayout list=Ui.column(this); ScrollView scroll=new ScrollView(this); scroll.addView(list);
        for (int i=0;i<media.size();i++) { final MediaItem item=media.get(i); LinearLayout row=Ui.row(this); row.setPadding(Ui.dp(this,8),Ui.dp(this,4),Ui.dp(this,8),Ui.dp(this,4)); TextView t=Ui.text(this,(i+1)+"  "+item.type+"  "+item.title,13,Ui.INK); row.addView(t,new LinearLayout.LayoutParams(0,-2,1)); row.addView(Ui.button(this,"下载",()->download(item)),new LinearLayout.LayoutParams(Ui.dp(this,76),-2)); list.addView(row); }
        new AlertDialog.Builder(this).setTitle("已观察资源（"+media.size()+"）").setView(scroll).setPositiveButton("关闭",null).show();
    }
    private void download(MediaItem item) {
        String url=UrlRules.normalize(item.url); if(url==null || !url.startsWith("https://")){status.setText("已拒绝不安全下载地址");return;}
        try { DownloadManager dm=(DownloadManager)getSystemService(DOWNLOAD_SERVICE); DownloadManager.Request r=new DownloadManager.Request(Uri.parse(url)); r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED); r.setTitle(UrlRules.safeFilename(item.title, "doulala-resource")); r.setDescription("豆啦啦资源"); r.setAllowedOverMetered(true); r.setAllowedOverRoaming(false); r.setMimeType(item.type.equals("video")?"video/mp4":"image/*"); dm.enqueue(r); status.setText("已交给系统下载"); } catch(Exception e){status.setText("下载失败：请检查系统下载服务");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==FILE_CHOOSER && fileCallback!=null){
            Uri[] items=result==RESULT_OK && data!=null?WebChromeClient.FileChooserParams.parseResult(result,data):null;
            if(items!=null){for(Uri uri:items) if(uri==null || !"content".equalsIgnoreCase(uri.getScheme())) {items=null;break;}}
            fileCallback.onReceiveValue(items);fileCallback=null;
        } else if(request==8102 && result==RESULT_OK && data!=null){
            String prompt=data.getStringExtra("prompt");
            if(prompt!=null && prompt.length()<=30000 && UrlRules.trusted(webView.getUrl())){
                webView.evaluateJavascript("(function(){var e=document.activeElement;var ok=function(x){return x&&x.offsetParent!==null&&!x.disabled&&!x.readOnly&&((x.tagName==='TEXTAREA')||x.isContentEditable);};if(!ok(e))e=Array.from(document.querySelectorAll('textarea,[contenteditable=true]')).find(ok);if(!e)return false;e.focus();if(e.tagName==='TEXTAREA'){Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(e,"+JSONObject.quote(prompt)+");e.dispatchEvent(new Event('input',{bubbles:true}));}else{if(!document.execCommand('insertText',false,"+JSONObject.quote(prompt)+"))return false;}return true;})()",v->status.setText("true".equals(v)?"内容已填入；请检查后手动发送":"未找到可用输入框，请从工作台复制并手动粘贴"));
            } else status.setText("当前页面不支持安全填入；请从工作台复制并手动粘贴");
        }
    }
    @Override protected void onResume(){super.onResume();if(webView!=null)webView.onResume();handler.removeCallbacks(scanLoop);handler.postDelayed(scanLoop,1200L);}
    @Override protected void onPause(){handler.removeCallbacks(scanLoop);if(webView!=null)webView.onPause();CookieManager.getInstance().flush();super.onPause();}
    @Override public void onBackPressed(){goBack();}
    @Override protected void onSaveInstanceState(Bundle out){if(webView!=null)webView.saveState(out);super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){destroyed=true;handler.removeCallbacksAndMessages(null);if(fileCallback!=null){fileCallback.onReceiveValue(null);fileCallback=null;}if(webView!=null){webView.stopLoading();webView.destroy();}super.onDestroy();}
    private static final class MediaItem {final String url,type,title;MediaItem(String u,String t,String n){url=u;type=t;title=n;}}
    public static class Space0 extends BrowserActivity{} public static class Space1 extends BrowserActivity{} public static class Space2 extends BrowserActivity{} public static class Space3 extends BrowserActivity{} public static class Space4 extends BrowserActivity{} public static class Space5 extends BrowserActivity{} public static class Space6 extends BrowserActivity{} public static class Space7 extends BrowserActivity{}
}
