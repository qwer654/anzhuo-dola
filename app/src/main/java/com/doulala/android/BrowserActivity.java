package com.doulala.android;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.webkit.*;
import android.widget.*;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.*;
import java.util.*;

/** Each Space runs in its own process/data directory. Tabs within a Space share its login. */
public class BrowserActivity extends Activity {
    private static final int MAX_TABS=6, MAX_MEDIA=300, MAX_BOOKMARKS=200;
    private static final int FILE_CHOOSER=8101, PICK_PROMPT=8102;
    private final ArrayList<Tab> tabs=new ArrayList<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private Tab active, fileTab, promptTab;
    private int fileNavigation, promptNavigation;
    private FrameLayout viewport;
    private EditText address;
    private TextView status,mediaButton,tabsButton,caption;
    private String home,title,probeScript,promptScript,androidDolaScript,originalDolaScript;
    private ValueCallback<Uri[]> fileCallback;
    private DownloadCenter downloads;
    private CookieTransfer cookies;
    private boolean destroyed,resumed;
    private final Runnable scanLoop=new Runnable(){public void run(){scan(active);if(resumed&&!destroyed)handler.postDelayed(this,3000);}};

    private static final class Tab {
        WebView view;
        String url,title="新标签",message="等待加载";
        int navigation;
        boolean probe,androidTools,closed,failed;
        final ArrayList<MediaItem> media=new ArrayList<>();
        final Set<String> mediaKeys=new HashSet<>();
        Tab(String u){url=u;}
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);Ui.setup(this);readLaunch(getIntent());
        prefs=getSharedPreferences("browser_"+getClass().getSimpleName(),MODE_PRIVATE);
        downloads=new DownloadCenter(this,prefs,getClass().getSimpleName());
        cookies=new CookieTransfer(this,()->active==null?null:active.url);
        build();restoreTabs();
    }
    private void readLaunch(Intent i) {
        title=i.getStringExtra("title");if(title==null||title.trim().isEmpty())title="创作空间";
        home=webUrl(i.getStringExtra("home"));if(home==null)home=MainActivity.URLS[0];
    }
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);readLaunch(i);if(caption!=null)caption.setText(title);}
    private static String webUrl(String raw){String u=UrlRules.normalize(raw);return u!=null&&u.startsWith("https://")?u:null;}

    private void build() {
        LinearLayout root=Ui.column(this);root.setBackgroundColor(Ui.BG);
        LinearLayout bar=Ui.row(this);Ui.pad(bar,4);
        addAction(bar,"‹","后退",36,()->goBack());
        address=Ui.edit(this,"输入 HTTPS 地址",home);address.setSingleLine(true);address.setTextSize(14);address.setSelectAllOnFocus(true);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        address.setOnEditorActionListener((v,action,event)->{
            if(action==android.view.inputmethod.EditorInfo.IME_ACTION_GO || (event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER&&event.getAction()==KeyEvent.ACTION_UP)) {
                String u=webUrl(address.getText().toString());
                if(u==null)address.setError("请输入有效的 HTTPS 地址");else{
                    load(u);address.clearFocus();
                    ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(),0);
                }return true;
            }return false;
        });
        bar.addView(address,new LinearLayout.LayoutParams(0,Ui.dp(this,48),1));
        addAction(bar,"↻","刷新",40,()->{if(active.view==null)select(active);else active.view.reload();});root.addView(bar);
        LinearLayout actions=Ui.row(this);Ui.pad(actions,4);
        caption=Ui.text(this,title,12,Ui.MUTED);caption.setSingleLine(true);caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        actions.addView(caption,new LinearLayout.LayoutParams(0,-2,1));
        tabsButton=addAction(actions,"标签 1","管理标签",66,this::showTabs);
        mediaButton=addAction(actions,"资源 0","观察到的资源",66,this::showMedia);
        addAction(actions,"工作台","创作工作台",60,()->{
            promptTab=active;promptNavigation=active.navigation;
            startActivityForResult(new Intent(this,StudioActivity.class).putExtra("pickPrompt",true),PICK_PROMPT);
        });
        addAction(actions,"菜单","浏览器菜单",50,this::showMenu);root.addView(actions);
        status=Ui.text(this,"正在准备网页…",11,Ui.MUTED);status.setSingleLine(true);root.addView(status);
        viewport=new FrameLayout(this);root.addView(viewport,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
    }
    private TextView addAction(LinearLayout row,String text,String description,int width,Runnable action) {
        TextView v=Ui.text(this,text,14,Ui.ACCENT);v.setGravity(Gravity.CENTER);v.setContentDescription(description);
        v.setOnClickListener(w->action.run());row.addView(v,new LinearLayout.LayoutParams(Ui.dp(this,width),Ui.dp(this,48)));return v;
    }
    private void restoreTabs() {
        try {
            String saved=prefs.getString("tabs","[]");if(saved.length()>128*1024)throw new JSONException("too large");
            JSONArray array=new JSONArray(saved);
            for(int i=0;i<array.length()&&tabs.size()<MAX_TABS;i++){String u=webUrl(array.optString(i));if(u!=null)tabs.add(new Tab(u));}
        }catch(Exception ignored){}
        if(tabs.isEmpty())tabs.add(new Tab(home));
        select(tabs.get(Math.max(0,Math.min(prefs.getInt("selected",0),tabs.size()-1))));
    }
    private void saveTabs() {
        if(prefs==null)return;JSONArray a=new JSONArray();for(Tab t:tabs)a.put(t.url);
        prefs.edit().putString("tabs",a.toString()).putInt("selected",Math.max(0,tabs.indexOf(active))).apply();
    }
    private void select(Tab tab) {
        if(tab==null||tab.closed)return;
        if(active!=null&&active.view!=null)active.view.onPause();viewport.removeAllViews();active=tab;
        if(tab.view==null){tab.view=new WebView(this);configure(tab);installProbe(tab);tab.view.loadUrl(tab.url);}
        viewport.addView(tab.view,new FrameLayout.LayoutParams(-1,-1));
        if(resumed)tab.view.onResume();else tab.view.onPause();
        updateUi();saveTabs();
    }
    private void newTab(String url) {
        if(tabs.size()>=MAX_TABS){Ui.toast(this,"每个空间最多 6 个标签，请先关闭一个");return;}
        String u=webUrl(url);if(u==null){Ui.toast(this,"只支持 HTTPS 标签页");return;}
        Tab t=new Tab(u);tabs.add(t);select(t);
    }
    private void load(String url){if(active.view==null){active.url=url;select(active);}else active.view.loadUrl(url);}
    private void closeTab(Tab t) {
        if(!tabs.contains(t))return;
        if(fileTab==t)cancelFile();if(promptTab==t)promptTab=null;
        int index=tabs.indexOf(t);tabs.remove(t);t.closed=true;
        if(t==active){viewport.removeAllViews();active=null;}
        if(t.view!=null){t.view.stopLoading();t.view.destroy();t.view=null;}
        if(tabs.isEmpty())tabs.add(new Tab(home));
        if(active==null)select(tabs.get(Math.min(index,tabs.size()-1)));else{updateUi();saveTabs();}
    }
    private void showTabs() {
        LinearLayout list=Ui.column(this);Ui.pad(list,12);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("标签（同一空间共享登录）").setView(Ui.scroll(this,list))
            .setNegativeButton("关闭",null).setPositiveButton("＋ 新标签",(x,w)->newTab(home)).create();
        for(Tab t:new ArrayList<>(tabs)) {
            LinearLayout row=Ui.row(this);TextView text=Ui.text(this,(t==active?"● ":"")+t.title+"\n"+t.url,13,Ui.INK);
            text.setMaxLines(3);text.setEllipsize(android.text.TextUtils.TruncateAt.END);text.setOnClickListener(v->{d.dismiss();select(t);});
            row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
            row.addView(Ui.button(this,"关闭",()->new AlertDialog.Builder(this).setTitle("关闭此标签？")
                .setMessage("网页中未提交的输入可能丢失。").setNegativeButton("保留",null)
                .setPositiveButton("关闭",(x,w)->{d.dismiss();closeTab(t);}).show()));list.addView(row);
        }d.show();
    }
    private void updateUi(){if(active==null)return;address.setText(active.url);status.setText(active.message);tabsButton.setText("标签 "+tabs.size());mediaButton.setText("资源 "+active.media.size());}
    private void message(Tab t,String text){t.message=text;if(t==active&&!destroyed)status.setText(text);}

    private void configure(Tab t) {
        WebView view=t.view;WebSettings s=view.getSettings();
        s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);s.setAllowContentAccess(true);s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        boolean cleanPrivacy=prefs.getBoolean("privacyCleanMode",false);
        s.setSafeBrowsingEnabled(true);s.setUserAgentString(userAgentForPrivacyMode(s.getUserAgentString(),cleanPrivacy));
        CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(view,acceptThirdPartyCookiesInPrivacyMode(cleanPrivacy));
        view.setOverScrollMode(View.OVER_SCROLL_NEVER);
        view.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){
                if(!r.isForMainFrame())return !"https".equalsIgnoreCase(r.getUrl().getScheme());
                return external(t,r.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v,String u){return external(t,Uri.parse(u));}
            @Override public void onPageStarted(WebView v,String u,Bitmap icon){
                t.navigation++;t.failed=false;t.media.clear();t.mediaKeys.clear();String valid=webUrl(u);if(valid!=null)t.url=valid;
                t.message="正在加载…";if(t==active)updateUi();saveTabs();
            }
            @Override public void doUpdateVisitedHistory(WebView v,String u,boolean reload){String valid=webUrl(u);if(valid!=null){t.url=valid;if(t==active&&!address.hasFocus())address.setText(valid);saveTabs();}}
            @Override public void onPageFinished(WebView v,String u){
                if(!t.failed)message(t,t.probe?"已就绪 · 30S 本地工具与资源观察可用":(t.androidTools?"已就绪 · 30S 本地工具可用；资源观察未启用":"已就绪 · 当前 WebView 不支持早期脚本"));scan(t);
            }
            @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e){if(r.isForMainFrame()){t.failed=true;message(t,"加载失败："+e.getDescription());}}
            @Override public void onReceivedHttpError(WebView v,WebResourceRequest r,WebResourceResponse response){if(r.isForMainFrame()){t.failed=true;message(t,"网页返回 HTTP "+response.getStatusCode());}}
            @Override public boolean onRenderProcessGone(WebView v,RenderProcessGoneDetail detail){
                // A renderer can be shared by several tabs: invalidate every affected view via callbacks.
                if(t.view==v){t.view=null;t.navigation++;if(t==active)viewport.removeAllViews();}
                v.destroy();message(t,"网页进程已回收，点刷新重试");return true;
            }
        });
        view.setWebChromeClient(new WebChromeClient(){
            @Override public void onReceivedTitle(WebView v,String text){if(text!=null)t.title=text.substring(0,Math.min(120,text.length()));}
            @Override public void onProgressChanged(WebView v,int n){if(n<100&&!t.failed)message(t,"加载 "+n+"%");}
            @Override public boolean onShowFileChooser(WebView v,ValueCallback<Uri[]> cb,FileChooserParams p){
                if(t!=active){cb.onReceiveValue(null);return true;}cancelFile();fileCallback=cb;fileTab=t;fileNavigation=t.navigation;
                try{Intent i=p.createIntent();i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,FILE_CHOOSER);}
                catch(Exception e){cancelFile();message(t,"无法打开文件选择器");}return true;
            }
        });
        view.setDownloadListener((url,ua,disposition,mime,length)->{if(t==active&&!destroyed)downloads.confirm(url,disposition,mime);});
        view.setOnLongClickListener(v->{
            WebView.HitTestResult hit=view.getHitTestResult();
            if(hit!=null&&(hit.getType()==WebView.HitTestResult.SRC_ANCHOR_TYPE||hit.getType()==WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)){
                String url=webUrl(hit.getExtra());if(url!=null){new AlertDialog.Builder(this).setTitle("链接")
                    .setItems(new String[]{"在新标签打开","复制链接"},(d,w)->{
                        if(w==0)newTab(url);else{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("链接",url));Ui.toast(this,"已复制链接");}
                    }).show();return true;}
            }return false;
        });
    }
    static String userAgentForPrivacyMode(String nativeUserAgent,boolean cleanPrivacy) {
        if(nativeUserAgent==null)return "";
        return cleanPrivacy?nativeUserAgent:nativeUserAgent+" DoulalaAndroid/0.2";
    }
    static boolean acceptThirdPartyCookiesInPrivacyMode(boolean cleanPrivacy) { return !cleanPrivacy; }
    private boolean external(Tab t,Uri uri) {
        if(webUrl(uri.toString())!=null)return false;
        String scheme=uri.getScheme();
        if(t==active&&("tel".equalsIgnoreCase(scheme)||"mailto".equalsIgnoreCase(scheme))) {
            new AlertDialog.Builder(this).setTitle("打开系统应用？").setMessage(uri.toString())
                .setNegativeButton("取消",null).setPositiveButton("打开",(d,w)->{
                    try{startActivity(new Intent(Intent.ACTION_VIEW,uri));}catch(Exception e){message(t,"此链接无法在系统中打开");}
                }).show();
        }else message(t,"已阻止非 HTTPS 或不支持的链接");return true;
    }
    private void installProbe(Tab t) {
        t.probe=false;t.androidTools=false;if(!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))return;
        Set<String> origins=new HashSet<>(Arrays.asList(
            "https://doubao.com","https://*.doubao.com","https://dola.com","https://*.dola.com",
            "https://qianwen.com","https://*.qianwen.com","https://jianying.com","https://*.jianying.com"));
        try {
            if(probeScript==null)probeScript=readAsset("media-probe.js");
            WebViewCompat.addDocumentStartJavaScript(t.view,probeScript,origins);t.probe=true;
        }catch(Exception ignored){}
        // The supplied duration script remains byte-for-byte preserved. It is
        // explicitly opt-in because it can rewrite outgoing generation prompts.
        try {
            if(promptScript==null)promptScript=readAsset("prompt-tools.js");
            if(androidDolaScript==null)androidDolaScript=readAsset("dola30-android-adapter.js");
            WebViewCompat.addDocumentStartJavaScript(t.view,promptScript,origins);
            boolean originalEnabled=prefs.getBoolean("originalDola30",false);
            if(originalDolaScript==null)originalDolaScript=readAsset("dola30-original/duration-main.js");
            String originalBootstrap="window.__dola30ExtOff="+(originalEnabled?"false":"true")+";\n"+originalDolaScript;
            WebViewCompat.addDocumentStartJavaScript(t.view,originalBootstrap,
                new HashSet<>(Arrays.asList("https://doubao.com","https://*.doubao.com","https://dola.com","https://*.dola.com")));
            WebViewCompat.addDocumentStartJavaScript(t.view,androidDolaScript,origins);t.androidTools=true;
        }catch(Exception ignored){}
    }
    private String readAsset(String name)throws java.io.IOException{
        try(java.io.InputStream in=getAssets().open(name);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
            return new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    private void scan(Tab t) {
        if(destroyed||t==null||t.closed||t.view==null||!UrlRules.trusted(t.view.getUrl()))return;
        final int navigation=t.navigation;
        t.view.evaluateJavascript("(window.__dolaAndroidDrain?window.__dolaAndroidDrain():[])",value->{
            if(destroyed||t.closed||t.view==null||navigation!=t.navigation||!UrlRules.trusted(t.view.getUrl())||value==null||value.length()>4*1024*1024)return;
            try {
                JSONArray a=new JSONArray(value);
                for(int i=0;i<a.length()&&t.media.size()<MAX_MEDIA;i++){
                    JSONObject o=a.optJSONObject(i);if(o==null)continue;
                    String u=webUrl(o.optString("url",null)),type=o.optString("type","");
                    if(u==null||!(type.equals("image")||type.equals("video")))continue;
                    if(t.mediaKeys.add(type+"|"+u)){String label=o.optString("title","资源");t.media.add(new MediaItem(u,type,label.substring(0,Math.min(label.length(),120))));}
                }
                if(t==active)mediaButton.setText("资源 "+t.media.size());
            }catch(Exception ignored){}
        });
    }
    private void showMedia() {
        if(active.media.isEmpty()){
            new AlertDialog.Builder(this).setTitle("当前标签资源").setMessage("尚未观察到图片或视频。请在白名单站点完成一次加载。只观察页面已经暴露的 URL，不请求 fallback_api，不保证原图或无水印结果。").setPositiveButton("知道了",null).show();return;
        }
        LinearLayout list=Ui.column(this);Ui.pad(list,8);
        for(MediaItem item:new ArrayList<>(active.media)){
            LinearLayout row=Ui.row(this);TextView label=Ui.text(this,item.type+" · "+item.title,13,Ui.INK);
            row.addView(label,new LinearLayout.LayoutParams(0,-2,1));row.addView(Ui.button(this,"复制链接",()->copyMediaUrl(item.url)));row.addView(Ui.button(this,"打开",()->newTab(item.url)));row.addView(Ui.button(this,"下载",()->downloads.confirm(item.url,null,null)));list.addView(row);
        }
        new AlertDialog.Builder(this).setTitle("当前标签资源（"+active.media.size()+"）").setView(Ui.scroll(this,list)).setPositiveButton("关闭",null).show();
    }
    static boolean writeMediaUrlToClipboard(Context context,String url) {
        if(context==null||url==null||url.trim().isEmpty())return false;
        ClipboardManager clipboard=(ClipboardManager)context.getSystemService(CLIPBOARD_SERVICE);
        if(clipboard==null)return false;
        clipboard.setPrimaryClip(ClipData.newPlainText("媒体链接",url));
        return true;
    }
    private void copyMediaUrl(String url) {
        Ui.toast(this,writeMediaUrlToClipboard(this,url)?"资源链接已复制":"资源链接复制失败");
    }
    private void showDurationTool() {
        if(active==null||active.view==null||!UrlRules.trusted(active.view.getUrl())){
            Ui.toast(this,"30S 工具仅在白名单网页可用");return;
        }
        final Tab t=active;final int navigation=t.navigation;
        t.view.evaluateJavascript("window.getDola30sStatus?window.getDola30sStatus():null",value->{
            if(destroyed||t.closed||t.view==null||t.navigation!=navigation||t!=active)return;
            JSONObject state=parseJsObject(value);
            if(state==null){Ui.toast(this,"当前 WebView 未加载 30S 工具");return;}
            boolean on=state.optBoolean("on",false);
            boolean originalEnabled=prefs.getBoolean("originalDola30",false);
            String message="当前页面目标时长："+state.optInt("duration",15)+" 秒\n"+
                (originalEnabled?"原版 Dola30 JS 已启用：按原脚本逻辑可能更改页面状态、提示词或匹配请求中的用户文本；应用不会自动提交。\n":
                    "Android 默认适配只保存页面状态并提供本地提示词工具，不改写网页请求。\n")+
                "不会伪造平台能力；30 秒能力由页面选项与平台决定。";
            new AlertDialog.Builder(this).setTitle("30S 工具（Android WebView）")
                .setMessage(message).setNegativeButton("关闭",null)
                .setNeutralButton("转换提示词时间轴",(d,w)->showTimelineRewrite())
                .setPositiveButton(on?"切换为 15S":"启用 30S",(d,w)->setDurationState(!on)).show();
        });
    }
    private JSONObject parseJsObject(String value) {
        if(value==null||"null".equals(value))return null;
        try {
            Object decoded=new JSONTokener(value).nextValue();
            if(decoded instanceof String)decoded=new JSONTokener((String)decoded).nextValue();
            return decoded instanceof JSONObject?(JSONObject)decoded:null;
        }catch(Exception ignored){return null;}
    }
    private String decodeJsString(String value) {
        if(value==null||"null".equals(value))return null;
        try {Object decoded=new JSONTokener(value).nextValue();return decoded==JSONObject.NULL?null:String.valueOf(decoded);}
        catch(Exception ignored){return value;}
    }
    private void setDurationState(boolean enabled) {
        if(active==null||active.view==null)return;
        final Tab t=active;final int navigation=t.navigation;
        String script="window.setDola30s("+(enabled?"true":"false")+")";
        t.view.evaluateJavascript(script,value->{
            if(!destroyed&&!t.closed&&t==active&&t.navigation==navigation)
                message(t,enabled?"已启用 30S 本地状态，请检查网页后手动继续":"已切换为 15S 本地状态");
        });
    }
    private void showTimelineRewrite() {
        if(active==null||active.view==null||!UrlRules.trusted(active.view.getUrl())){Ui.toast(this,"当前页面不可用");return;}
        LinearLayout form=Ui.column(this);Ui.pad(form,8);
        TextView hint=Ui.text(this,"仅处理本地提示词，不会改写网页请求或自动提交。",12,Ui.MUTED);form.addView(hint);
        EditText prompt=Ui.edit(this,"输入提示词，例如：00:00-00:05 开场","");
        prompt.setSingleLine(false);prompt.setMinLines(5);prompt.setGravity(Gravity.TOP|Gravity.START);
        prompt.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        form.addView(prompt,new LinearLayout.LayoutParams(-1,Ui.dp(this,130)));
        EditText seconds=Ui.edit(this,"总时长（秒）","30");
        seconds.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);form.addView(seconds);
        CheckBox guards=new CheckBox(this);guards.setText("加入原版控制提示段（人工检查后再复制/填入）");form.addView(guards);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("转换提示词时间轴").setView(Ui.scroll(this,form))
            .setNegativeButton("取消",null).setPositiveButton("转换",null).create();
        dialog.setOnShowListener(x->dialog.getButton(-1).setOnClickListener(v->{
            String input=prompt.getText().toString();if(input.length()>100000){prompt.setError("提示词最多 100000 字");return;}
            double total;try{total=Double.parseDouble(seconds.getText().toString().trim());}catch(Exception e){total=0;}
            if(!(total>0&&total<=86400)){seconds.setError("请输入 0–86400 之间的秒数");return;}
            final Tab t=active;final int navigation=t==null?0:t.navigation;
            if(t==null||t.view==null||!UrlRules.trusted(t.view.getUrl())){dialog.dismiss();Ui.toast(this,"页面已变化，请重试");return;}
            String js="window.rewriteDolaTimeline?window.rewriteDolaTimeline("+JSONObject.quote(input)+","+Double.toString(total)+","+(guards.isChecked()?"true":"false")+"):null";
            t.view.evaluateJavascript(js,value->{
                if(destroyed||t.closed||t!=active||t.navigation!=navigation){Ui.toast(this,"页面已变化，未填入结果");return;}
                String result=decodeJsString(value);if(result==null){Ui.toast(this,"提示词工具未加载");return;}
                dialog.dismiss();showTimelineResult(result);
            });
        }));dialog.show();
    }
    private void showTimelineResult(String result) {
        TextView output=Ui.text(this,result,14,Ui.INK);output.setTextIsSelectable(true);output.setGravity(Gravity.TOP|Gravity.START);
        new AlertDialog.Builder(this).setTitle("转换结果（请手动检查）").setView(Ui.scroll(this,output))
            .setNegativeButton("关闭",null).setPositiveButton("复制",(d,w)->{
                ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("提示词",result));Ui.toast(this,"结果已复制");
            }).show();
    }
    private void showMenu() {
        boolean originalEnabled=prefs.getBoolean("originalDola30",false);
        boolean cleanPrivacy=prefs.getBoolean("privacyCleanMode",false);
        new AlertDialog.Builder(this).setTitle(title).setItems(new String[]{"前进","回到首页","新建标签","添加当前页书签","打开书签","下载中心","30S 工具","Cookie 手动迁移","原版 Dola30 请求适配："+(originalEnabled?"已启用":"关闭"),"隐私洁净模式："+(cleanPrivacy?"已启用":"关闭"),"回到空间列表"},(d,n)->{
            switch(n){case 0:if(active.view!=null&&active.view.canGoForward())active.view.goForward();break;
                case 1:load(home);break;case 2:newTab(home);break;case 3:addBookmark();break;
                case 4:showBookmarks();break;case 5:downloads.show();break;case 6:showDurationTool();break;case 7:cookies.show();break;case 8:confirmOriginalDolaMode(!originalEnabled);break;case 9:confirmPrivacyCleanMode(!cleanPrivacy);break;case 10:returnToCatalog();break;}
        }).show();
    }
    private void confirmPrivacyCleanMode(boolean enabled) {
        String detail=enabled
            ?"开启后使用 Android WebView 原生 UA（不伪造机型/系统身份），并阻止第三方 Cookie。可能导致依赖跨站 Cookie 的登录或嵌入内容失效；不会保证阻止定向广告或实现匿名。确认后所有标签重载，未提交输入会丢失。"
            :"关闭后恢复应用标识 UA 并允许第三方 Cookie。所有标签重载，未提交输入会丢失。";
        new AlertDialog.Builder(this).setTitle(enabled?"开启隐私洁净模式？":"关闭隐私洁净模式？")
            .setMessage(detail).setNegativeButton("取消",null).setPositiveButton(enabled?"开启并重载":"关闭并重载",(d,w)->{
                if(!prefs.edit().putBoolean("privacyCleanMode",enabled).commit()){Ui.toast(this,"设置未保存");return;}
                reloadTabsForScriptMode();
            }).show();
    }
    private void confirmOriginalDolaMode(boolean enabled) {
        String detail=enabled
            ?"将原样注入 assets/dola30-original/duration-main.js（不改源码），仅匹配 Dola/Doubao HTTPS 页面。开启后由原脚本自行判断是否修改页面状态、提示词或匹配请求中的用户文本（可能改变生成请求行为）；应用不会自动提交。不会承诺平台接受请求或去除水印。重新加载会丢失未提交的网页输入。"
            :"关闭原版请求适配并恢复 Android 默认无请求改写模式。为移除 document-start 脚本，所有打开标签会重新加载，未提交的网页输入将丢失。";
        new AlertDialog.Builder(this).setTitle(enabled?"启用原版 Dola30 JS？":"关闭原版 Dola30 JS？")
            .setMessage(detail).setNegativeButton("取消",null).setPositiveButton(enabled?"启用并重新加载":"关闭并重新加载",(d,w)->{
                if(!prefs.edit().putBoolean("originalDola30",enabled).commit()){Ui.toast(this,"设置未保存");return;}
                reloadTabsForScriptMode();
            }).show();
    }
    private void reloadTabsForScriptMode() {
        if(viewport!=null)viewport.removeAllViews();
        if(fileCallback!=null)cancelFile();
        promptTab=null;
        for(Tab tab:tabs){
            if(tab.view!=null){try{tab.view.stopLoading();tab.view.destroy();}catch(Exception ignored){}tab.view=null;}
            tab.navigation++;tab.probe=false;tab.androidTools=false;tab.failed=false;tab.media.clear();tab.mediaKeys.clear();
        }
        if(active!=null&&!active.closed)select(active);
        Ui.toast(this,prefs.getBoolean("privacyCleanMode",false)?"隐私洁净模式已开启（原生 UA / 阻止第三方 Cookie）":
            (prefs.getBoolean("originalDola30",false)?"原版 Dola30 JS 已开启":"已恢复 Android 默认适配"));
    }
    private JSONArray bookmarks(){try{return new JSONArray(prefs.getString("bookmarks","[]"));}catch(Exception e){return new JSONArray();}}
    private void addBookmark() {
        final String url=webUrl(active.url);if(url==null)return;EditText name=Ui.edit(this,"书签名称",active.title);name.setContentDescription("书签名称");
        AlertDialog d=new AlertDialog.Builder(this).setTitle("收藏当前网页").setView(name).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        d.setOnShowListener(x->d.getButton(-1).setOnClickListener(v->{
            String label=name.getText().toString().trim();if(label.isEmpty()||label.length()>120){name.setError("名称需为 1–120 字");return;}
            try {
                JSONArray a=bookmarks();int index=-1;for(int i=0;i<a.length();i++)if(url.equals(a.getJSONObject(i).getString("url"))){index=i;break;}
                if(index<0&&a.length()>=MAX_BOOKMARKS){name.setError("最多 200 个书签");return;}
                JSONObject b=new JSONObject().put("title",label).put("url",url);if(index>=0)a.put(index,b);else a.put(b);
                if(!prefs.edit().putString("bookmarks",a.toString()).commit())throw new Exception();d.dismiss();Ui.toast(this,"书签已保存（仅本空间）");
            }catch(Exception e){name.setError("保存失败，未完成收藏");}
        }));d.show();
    }
    private void showBookmarks() {
        JSONArray a=bookmarks();if(a.length()==0){Ui.toast(this,"本空间还没有书签");return;}
        LinearLayout list=Ui.column(this);Ui.pad(list,12);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("本空间书签").setView(Ui.scroll(this,list)).setPositiveButton("关闭",null).create();
        for(int i=0;i<a.length();i++){
            JSONObject b=a.optJSONObject(i);if(b==null)continue;String url=webUrl(b.optString("url"));if(url==null)continue;
            LinearLayout card=Ui.card(this);card.addView(Ui.text(this,b.optString("title","书签"),16,Ui.INK));
            TextView link=Ui.text(this,url,12,Ui.MUTED);link.setMaxLines(2);card.addView(link);
            LinearLayout row=Ui.row(this);Ui.equal(row,Ui.button(this,"打开",()->{d.dismiss();load(url);}));
            Ui.equal(row,Ui.button(this,"新标签",()->{d.dismiss();newTab(url);}));
            Ui.equal(row,Ui.button(this,"删除",()->new AlertDialog.Builder(this).setTitle("删除书签？").setNegativeButton("取消",null)
                .setPositiveButton("删除",(x,w)->{
                    JSONArray all=bookmarks(),keep=new JSONArray();for(int n=0;n<all.length();n++){JSONObject item=all.optJSONObject(n);if(item!=null&&!url.equals(item.optString("url")))keep.put(item);}
                    if(prefs.edit().putString("bookmarks",keep.toString()).commit()){d.dismiss();showBookmarks();}else Ui.toast(this,"删除失败");
                }).show()));card.addView(row);list.addView(card);
        }d.show();
    }
    private void returnToCatalog(){
        // Each profile has its own task affinity/process. Explicitly bring the dashboard
        // task back instead of relying on task history, which may be empty after process isolation.
        Intent i=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);finish();
    }
    private void goBack(){if(active!=null&&active.view!=null&&active.view.canGoBack())active.view.goBack();else finish();}
    private void cancelFile(){if(fileCallback!=null)fileCallback.onReceiveValue(null);fileCallback=null;fileTab=null;}
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(cookies.onResult(request,result,data)||downloads.onResult(request,result,data))return;
        if(request==FILE_CHOOSER&&fileCallback!=null){
            Uri[] items=result==RESULT_OK&&data!=null?WebChromeClient.FileChooserParams.parseResult(result,data):null;
            if(fileTab==null||fileTab.closed||fileTab.navigation!=fileNavigation)items=null;
            if(items!=null)for(Uri uri:items)if(uri==null||!"content".equalsIgnoreCase(uri.getScheme())){items=null;break;}
            fileCallback.onReceiveValue(items);fileCallback=null;fileTab=null;
        }else if(request==PICK_PROMPT){
            Tab t=promptTab;promptTab=null;
            if(result!=RESULT_OK||data==null)return;String prompt=data.getStringExtra("prompt");
            if(t==null||t!=active||t.closed||t.view==null||t.navigation!=promptNavigation||prompt==null||prompt.length()>30000||!UrlRules.trusted(t.view.getUrl())){
                Ui.toast(this,"页面已变化或不支持安全填入，请从工作台复制并手动粘贴");return;
            }
            final int navigation=t.navigation;
            t.view.evaluateJavascript("(function(){if(location.href!=="+JSONObject.quote(t.view.getUrl())+")return false;var e=document.activeElement;var ok=function(x){return x&&x.offsetParent!==null&&!x.disabled&&!x.readOnly&&((x.tagName==='TEXTAREA')||x.isContentEditable);};if(!ok(e))e=Array.from(document.querySelectorAll('textarea,[contenteditable=true]')).find(ok);if(!e)return false;e.focus();if(e.tagName==='TEXTAREA'){Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(e,"+JSONObject.quote(prompt)+");e.dispatchEvent(new Event('input',{bubbles:true}));}else{if(!document.execCommand('insertText',false,"+JSONObject.quote(prompt)+"))return false;}return true;})()",
                v->{if(!destroyed&&!t.closed&&t.navigation==navigation)message(t,"true".equals(v)?"内容已填入，请检查后手动发送":"未找到可用输入框，请从工作台复制并手动粘贴");});
        }
    }
    @Override protected void onResume(){super.onResume();resumed=true;if(active!=null&&active.view!=null)active.view.onResume();downloads.resume();handler.removeCallbacks(scanLoop);handler.postDelayed(scanLoop,1200);}
    @Override protected void onPause(){resumed=false;handler.removeCallbacks(scanLoop);downloads.pause();if(active!=null&&active.view!=null)active.view.onPause();saveTabs();CookieManager.getInstance().flush();super.onPause();}
    @Override public void onBackPressed(){goBack();}
    @Override protected void onSaveInstanceState(Bundle out){saveTabs();super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){
        destroyed=true;handler.removeCallbacksAndMessages(null);cancelFile();cookies.close();downloads.close();viewport.removeAllViews();
        for(Tab t:tabs){t.closed=true;if(t.view!=null){t.view.stopLoading();t.view.destroy();t.view=null;}}super.onDestroy();
    }
    private static final class MediaItem{final String url,type,title;MediaItem(String u,String t,String n){url=u;type=t;title=n;}}
    public static class Space0 extends BrowserActivity{} public static class Space1 extends BrowserActivity{}
    public static class Space2 extends BrowserActivity{} public static class Space3 extends BrowserActivity{}
    public static class Space4 extends BrowserActivity{} public static class Space5 extends BrowserActivity{}
    public static class Space6 extends BrowserActivity{} public static class Space7 extends BrowserActivity{}
}
