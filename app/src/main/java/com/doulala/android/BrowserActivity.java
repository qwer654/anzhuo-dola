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
import java.net.URI;
import java.net.URLDecoder;
import java.util.*;

/** Each Space runs in its own process/data directory. Tabs within a Space share its login. */
public class BrowserActivity extends Activity {
    private static final int MAX_TABS=6, MAX_MEDIA=300, MAX_BOOKMARKS=200, MAX_SAVED_TABS_CHARS=512*1024;
    private static final int FILE_CHOOSER=8101, PICK_PROMPT=8102;
    private final ArrayList<Tab> tabs=new ArrayList<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService previewExecutor=java.util.concurrent.Executors.newFixedThreadPool(2);
    private SharedPreferences prefs;
    private Tab active, fileTab, promptTab;
    private int fileNavigation, promptNavigation;
    private FrameLayout viewport;
    private EditText address;
    private TextView status,mediaButton,tabsButton,caption,exitIp;
    private boolean ipLookupRunning;
    private Switch pageScriptsSwitch;
    private boolean syncingScriptSwitch;
    private String home,title,skillPackScript,mediaUploadScript;
    private ValueCallback<Uri[]> fileCallback;
    private DownloadCenter downloads;
    private CookieTransfer cookies;
    private AlertDialog mediaDialog;
    private LinearLayout mediaDialogList;
    private Tab mediaDialogOwner;
    private int mediaDialogNavigation=-1;
    private boolean destroyed,resumed;
    private final Runnable scanLoop=new Runnable(){public void run(){scan(active);if(resumed&&!destroyed)handler.postDelayed(this,3000);}};

    private static final class Tab {
        WebView view;
        String url,title="新标签",message="等待加载",savedState;
        int navigation;
        boolean probe,androidTools,pageScriptsRegistered,closed,failed;
        final ArrayList<MediaItem> media=new ArrayList<>();
        final Set<String> mediaKeys=new HashSet<>();
        Tab(String u){url=u;}
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(DolaApplication.isSpaceResetPending(this,spaceSlot())){finish();return;}
        Ui.setup(this);readLaunch(getIntent());
        prefs=getSharedPreferences(DolaPageScripts.preferenceName(getClass().getSimpleName()),MODE_PRIVATE);
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

    private int spaceSlot(){return Integer.parseInt(getClass().getSimpleName().substring(5));}
    private void build() {
        LinearLayout root=Ui.column(this);root.setBackgroundColor(Ui.BG);
        caption=Ui.title(this,title,14);caption.setSingleLine(true);caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        caption.setPadding(Ui.dp(this,8),Ui.dp(this,3),Ui.dp(this,8),Ui.dp(this,3));root.addView(caption);
        LinearLayout navigation=toolbarRow(root);
        gridAction(navigation,"‹","后退",this::goBack);
        gridAction(navigation,"›","前进",()->{if(active!=null&&active.view!=null&&active.view.canGoForward())active.view.goForward();});
        gridAction(navigation,"↻","刷新",()->{if(active!=null){if(active.view==null)select(active);else active.view.reload();}});
        gridAction(navigation,"空间","回到空间列表",this::returnToCatalog);
        tabsButton=gridAction(navigation,"标签 1","浏览器菜单",this::showMenu);
        LinearLayout tools=toolbarRow(root);
        gridAction(tools,"Google 登录","Google登录",this::showGoogleLogin);
        gridAction(tools,"代理","代理说明",this::showProxyHelp);
        pageScriptsSwitch=new Switch(this);pageScriptsSwitch.setText("30s");pageScriptsSwitch.setTextSize(12);pageScriptsSwitch.setTextColor(Ui.INK);
        pageScriptsSwitch.setContentDescription("本空间新脚本30s开关");pageScriptsSwitch.setMinHeight(Ui.dp(this,48));
        pageScriptsSwitch.setGravity(Gravity.CENTER);tools.addView(pageScriptsSwitch,new LinearLayout.LayoutParams(0,Ui.dp(this,48),1));
        syncScriptSwitch();pageScriptsSwitch.setOnCheckedChangeListener((button,checked)->{if(syncingScriptSwitch)return;syncScriptSwitch();confirmPageScriptsMode(checked);});
        gridAction(tools,"清理","清理空间",this::showSpaceCleanup);
        LinearLayout addressRow=toolbarRow(root);
        address=Ui.edit(this,"输入 HTTPS 地址",home);address.setSingleLine(true);address.setTextSize(14);address.setSelectAllOnFocus(true);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        address.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_GO||(event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER&&event.getAction()==KeyEvent.ACTION_UP)){openTypedAddress();return true;}return false;});
        addressRow.addView(address,new LinearLayout.LayoutParams(0,Ui.dp(this,48),3));gridAction(addressRow,"打开","打开地址",this::openTypedAddress);
        LinearLayout quick=toolbarRow(root);
        gridAction(quick,"当前 IP","当前IP",this::showExitIp);gridAction(quick,"导入 CK","导入CK",()->cookies.importFileDirect());
        gridAction(quick,"下载媒体","下载媒体",()->downloads.show());
        exitIp=Ui.text(this,"出口 IP：\n点击查询",11,Ui.MUTED);exitIp.setMaxLines(3);exitIp.setTextIsSelectable(true);exitIp.setContentDescription("出口IP显示");
        quick.addView(exitIp,new LinearLayout.LayoutParams(0,Ui.dp(this,56),1));
        status=Ui.text(this,"正在准备网页…",11,Ui.MUTED);status.setSingleLine(true);status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setPadding(Ui.dp(this,8),0,Ui.dp(this,8),Ui.dp(this,3));root.addView(status);
        viewport=new FrameLayout(this);root.addView(viewport,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
    }
    private LinearLayout toolbarRow(LinearLayout root){LinearLayout row=Ui.row(this);row.setPadding(Ui.dp(this,4),Ui.dp(this,2),Ui.dp(this,4),Ui.dp(this,2));root.addView(row);return row;}
    private Button gridAction(LinearLayout row,String label,String description,Runnable action){Button button=Ui.button(this,label,action);button.setTextSize(12);button.setPadding(Ui.dp(this,3),0,Ui.dp(this,3),0);button.setContentDescription(description);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,Ui.dp(this,48),1);lp.setMargins(Ui.dp(this,2),0,Ui.dp(this,2),0);row.addView(button,lp);return button;}
    private void openTypedAddress(){String u=webUrl(address.getText().toString());if(u==null){address.setError("请输入有效的 HTTPS 地址");return;}load(u);address.clearFocus();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(),0);}
    private void showGoogleLogin(){new AlertDialog.Builder(this).setTitle("Google 登录说明").setMessage("该页面只在本空间打开，Cookie 不复制到其它空间。但 Google OAuth 可能拒绝 WebView，本应用不绕过限制，也不保证授权成功。").setNegativeButton("取消",null).setPositiveButton("打开 Google 页面",(d,w)->load("https://accounts.google.com/")).show();}
    private void showProxyHelp(){new AlertDialog.Builder(this).setTitle("代理与出口").setMessage("当前使用手机系统网络或 VPN。本应用尚未提供每空间独立代理配置；此按钮不是代理开关。不同空间不会自动获得不同 IP，按目标分流时 IP 查询结果也可能与 Dola 不同。").setPositiveButton("知道了",null).show();}
    private void showSpaceCleanup(){new AlertDialog.Builder(this).setTitle("管理本空间").setItems(new String[]{"清除数据（保留空间）","删除空间（释放名额）"},(d,n)->{startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(SpaceReset.EXTRA_RESET_SLOT,spaceSlot()).putExtra(SpaceReset.EXTRA_RESET_OPERATION,n==0?SpaceReset.OP_CLEAR:SpaceReset.OP_DELETE));}).setNegativeButton("取消",null).show();}
    private void openSequentialVideo(){startActivity(new Intent(this,SequentialVideoActivity.class).putExtra("spaceId",getClass().getSimpleName()));}
    private void syncScriptSwitch() {
        if(pageScriptsSwitch==null)return;syncingScriptSwitch=true;
        pageScriptsSwitch.setChecked(prefs.getBoolean(DolaPageScripts.PREF_KEY,false));syncingScriptSwitch=false;
    }
    private void openStudio() {
        if(active==null)return;promptTab=active;promptNavigation=active.navigation;
        startActivityForResult(new Intent(this,StudioActivity.class).putExtra("pickPrompt",true),PICK_PROMPT);
    }
    private void showExitIp() {
        if(ipLookupRunning)return;ipLookupRunning=true;exitIp.setText("出口 IP：\n查询中…");
        previewExecutor.execute(()->{String result;try{result=ExitIpClient.lookup();}catch(Exception e){result="查询失败";}final String text=result;
            runOnUiThread(()->{ipLookupRunning=false;if(!destroyed&&exitIp!=null)exitIp.setText("出口 IP：\n"+text);});});
    }
    private TextView addAction(LinearLayout row,String text,String description,int width,Runnable action) {
        TextView v=Ui.text(this,text,14,Ui.ACCENT);v.setGravity(Gravity.CENTER);v.setContentDescription(description);
        v.setOnClickListener(w->action.run());row.addView(v,new LinearLayout.LayoutParams(Ui.dp(this,width),Ui.dp(this,48)));return v;
    }
    private void restoreTabs() {
        try {
            String saved=prefs.getString("tabs","[]");if(saved.length()>MAX_SAVED_TABS_CHARS)throw new JSONException("too large");
            JSONArray array=new JSONArray(saved);
            for(int i=0;i<array.length()&&tabs.size()<MAX_TABS;i++){
                Object entry=array.opt(i);String u=entry instanceof JSONObject?((JSONObject)entry).optString("url",null):String.valueOf(entry);
                u=webUrl(u);if(u==null)continue;
                Tab tab=new Tab(u);if(entry instanceof JSONObject)tab.savedState=TabStateCodec.validEncoded(((JSONObject)entry).optString("state",null));tabs.add(tab);
            }
        }catch(Exception ignored){}
        if(tabs.isEmpty())tabs.add(new Tab(home));
        select(tabs.get(Math.max(0,Math.min(prefs.getInt("selected",0),tabs.size()-1))));
    }
    private void saveTabs() {
        if(prefs==null)return;JSONArray a=new JSONArray();int stateBudget=TabStateCodec.TOTAL_LIMIT;
        for(Tab t:tabs){
            JSONObject item=new JSONObject();try{item.put("url",t.url);}catch(JSONException ignored){}
            if(t.view!=null&&stateBudget>0){
                Bundle state=new Bundle();try{
                    if(t.view.saveState(state)!=null){String encoded=TabStateCodec.encode(state,stateBudget);if(encoded!=null){item.put("state",encoded);stateBudget-=encoded.length();}}
                }catch(Exception ignored){}
            }else if(t.savedState!=null&&stateBudget>0){try{item.put("state",t.savedState);stateBudget-=t.savedState.length();}catch(JSONException ignored){}}
            a.put(item);
        }
        prefs.edit().putString("tabs",a.toString()).putInt("selected",Math.max(0,tabs.indexOf(active))).apply();
    }
    private void select(Tab tab) {
        if(tab==null||tab.closed)return;
        if(active!=tab)dismissMediaDialog();
        if(active!=null&&active.view!=null)active.view.onPause();viewport.removeAllViews();active=tab;
        if(tab.view==null){
            tab.view=new WebView(this);configure(tab);installProbe(tab);
            Bundle restored=TabStateCodec.decode(tab.savedState);
            WebBackForwardList history=null;try{if(restored!=null)history=tab.view.restoreState(restored);}catch(RuntimeException ignored){}
            if(history==null||history.getSize()==0)tab.view.loadUrl(tab.url);
            else {String restoredUrl=webUrl(tab.view.getUrl());if(restoredUrl!=null)tab.url=restoredUrl;}
            tab.savedState=null;
        }
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
    private void updateUi(){if(active==null)return;address.setText(active.url);status.setText(active.message);if(tabsButton!=null)tabsButton.setText("标签 "+tabs.size());if(mediaButton!=null)mediaButton.setText("资源 "+active.media.size());}
    private void message(Tab t,String text){t.message=text;if(t==active&&!destroyed)status.setText(text);}

    private void configure(Tab t) {
        WebView view=t.view;WebSettings s=view.getSettings();
        s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);s.setAllowContentAccess(true);s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        boolean cleanPrivacy=prefs.getBoolean("privacyCleanMode",false);
        s.setSafeBrowsingEnabled(true);s.setUserAgentString(userAgentForPrivacyMode(s.getUserAgentString(),cleanPrivacy));
        s.setGeolocationEnabled(geolocationEnabledInPrivacyMode(cleanPrivacy));
        CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(view,acceptThirdPartyCookiesInPrivacyMode(cleanPrivacy));
        view.setOverScrollMode(View.OVER_SCROLL_NEVER);
        view.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){
                if(!r.isForMainFrame())return !"https".equalsIgnoreCase(r.getUrl().getScheme());
                return external(t,r.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v,String u){return external(t,Uri.parse(u));}
            @Override public void onPageStarted(WebView v,String u,Bitmap icon){
                if(t==mediaDialogOwner)dismissMediaDialog();
                t.navigation++;t.failed=false;t.media.clear();t.mediaKeys.clear();String valid=webUrl(u);if(valid!=null)t.url=valid;
                t.message="正在加载…";if(t==active)updateUi();saveTabs();
            }
            @Override public void doUpdateVisitedHistory(WebView v,String u,boolean reload){String valid=webUrl(u);if(valid!=null){t.url=valid;if(t==active&&!address.hasFocus())address.setText(valid);saveTabs();}}
            @Override public void onPageFinished(WebView v,String u){
                if(!t.failed)message(t,prefs.getBoolean(DolaPageScripts.PREF_KEY,false)?"新附件 30s 设置已开启；执行状态见 30S 工具":"新附件 30s：关闭；旧 JS 仅备份不执行");
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
    static boolean geolocationEnabledInPrivacyMode(boolean cleanPrivacy) { return !cleanPrivacy; }
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
        // Runtime source policy: every previous external JS is backup-only.
        // No media probe, prompt adapter, old duration or fingerprint JS is registered.
        t.probe=false;t.androidTools=false;t.pageScriptsRegistered=false;
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))return;
        try {
            boolean enabled=prefs.getBoolean(DolaPageScripts.PREF_KEY,false);
            if(enabled){
                if(skillPackScript==null)skillPackScript=readAsset(DolaPageScripts.SKILL_ASSET);
                if(mediaUploadScript==null)mediaUploadScript=readAsset(DolaPageScripts.UPLOAD_ASSET);
            }
            WebViewCompat.addDocumentStartJavaScript(t.view,
                DolaPageScripts.bootstrap(enabled,prefs.contains(DolaPageScripts.PREF_KEY),skillPackScript,mediaUploadScript),
                new HashSet<>(Arrays.asList("https://dola.com","https://*.dola.com")));
            t.pageScriptsRegistered=true;
        }catch(Exception ignored){t.pageScriptsRegistered=false;}
    }
    private String readAsset(String name)throws java.io.IOException{
        try(java.io.InputStream in=getAssets().open(name);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
            return new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    // Historical compatibility helper kept inert; old privacy JS is backup-only.
    static String privacyFingerprintBootstrap(String script,boolean enabled) { return null; }
    private void scan(Tab t) { /* Previous resource-observer JS is no longer executed. */ }
    private void collectMediaResult(Tab t,int navigation,String value) {
        if(destroyed||t.closed||t.view==null||navigation!=t.navigation||!UrlRules.trusted(t.view.getUrl())||value==null||value.length()>4*1024*1024)return;
        try {
            JSONArray a=new JSONArray(value);
            boolean changed=false;
            for(int i=0;i<a.length()&&i<1200;i++){
                JSONObject o=a.optJSONObject(i);if(o==null)continue;
                String u=webUrl(o.optString("url",null)),type=o.optString("type","");
                if(u==null||!(type.equals("image")||type.equals("video")))continue;
                String posterUrl=type.equals("video")?webUrl(o.optString("posterUrl",null)):null;
                List<String> channels=type.equals("video")?normalizeMediaChannels(o.optJSONArray("channels"),u):Collections.emptyList();
                String label=o.optString("title","资源");
                if(mergeObservedMedia(t.media,t.mediaKeys,u,type,label,posterUrl,channels))changed=true;
            }
            if(t==active)if(mediaButton!=null)mediaButton.setText("资源 "+t.media.size());
            if(changed&&t==active&&t==mediaDialogOwner&&navigation==mediaDialogNavigation&&mediaDialog!=null&&mediaDialog.isShowing())
                renderMediaDialog(mediaDialog,t,navigation,mediaDialogList);
        }catch(Exception ignored){}
    }
    private void rescanMedia() { Ui.toast(this,"旧资源观察 JS 仅备份，不执行；Chrome 面板/后台解析不可用"); }
    private void showMedia() {
        if(active.media.isEmpty()){
            new AlertDialog.Builder(this).setTitle("当前标签资源").setMessage("旧资源观察 JS 已停用并保留备份。新上传主脚本是页面事件桥，不是 Chrome 扩展资源面板；此面板不伪装已经生效。")
                .setNegativeButton("关闭",null).show();return;
        }
        Tab owner=active;int navigation=owner.navigation;
        LinearLayout list=Ui.column(this);Ui.pad(list,8);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("当前标签资源（"+owner.media.size()+"）").setView(Ui.scroll(this,list)).setPositiveButton("关闭",null).create();
        mediaDialog=dialog;mediaDialogList=list;mediaDialogOwner=owner;mediaDialogNavigation=navigation;
        dialog.setOnDismissListener(d->{if(mediaDialog==dialog){mediaDialog=null;mediaDialogList=null;mediaDialogOwner=null;mediaDialogNavigation=-1;}});
        dialog.show();renderMediaDialog(dialog,owner,navigation,list);
    }
    private void renderMediaDialog(AlertDialog dialog,Tab owner,int navigation,LinearLayout list) {
        if(dialog==null||list==null||!mediaDialogMayRefresh(dialog.isShowing(),owner==active,owner.navigation==navigation,owner==mediaDialogOwner))return;
        list.removeAllViews();dialog.setTitle("当前标签资源（"+owner.media.size()+"）");
        LinkedHashMap<TextView,String> downloadRequests=new LinkedHashMap<>();
        for(MediaItem item:new ArrayList<>(owner.media)){
            LinearLayout row=Ui.column(this);TextView label=Ui.text(this,item.type+" · "+item.title,13,Ui.INK);
            row.addView(label);
            TextView downloadState=Ui.text(this,"正在读取本空间下载状态…",11,Ui.MUTED);row.addView(downloadState);
            downloadRequests.put(downloadState,item.url);
            LinearLayout actions=Ui.row(this);
            if(MediaPreview.validateImageUrl(item.url,item.type)!=null)
                addMediaAction(actions,"预览",()->previewImage(item));
            if(item.type.equals("video")&&MediaPreview.validateImageUrl(item.posterUrl,"image")!=null)
                addMediaAction(actions,"预览封面",()->previewImage(item.posterUrl,item.title+" · 视频封面"));
            addMediaAction(actions,"复制",()->copyMediaUrl(item.url));
            addMediaAction(actions,"分享",()->shareMediaUrl(item.url));
            addMediaAction(actions,"打开",()->newTab(item.url));
            addMediaAction(actions,"下载",()->confirmMediaDownload(item.url,downloadState));
            addMediaAction(actions,"选择位置下载",()->chooseMediaDownloadLocation(item.url,item.title));
            if(item.type.equals("video")&&item.channels.size()>1)
                addMediaAction(actions,"选择渠道（"+item.channels.size()+"）",()->showMediaChannels(item));
            row.addView(actions);list.addView(row);
        }
        list.addView(Ui.button(this,"复制全部资源链接",()->copyMediaUrls(owner.media)));
        list.addView(Ui.text(this,"图片及视频封面预览仅在点击后请求；不携带 WebView Cookie/授权头/Referer，HTTPS、8 MiB 上限、禁止重定向。封面预览不播放视频。",12,Ui.MUTED));
        list.addView(Ui.button(this,"重扫当前页",this::rescanMedia));
        list.addView(Ui.button(this,"清空当前标签资源",()->{
            clearObservedMedia(owner.media,owner.mediaKeys);
            updateUi();
            dialog.dismiss();
            showMedia();
        }));
        for(Map.Entry<TextView,String> request:downloadRequests.entrySet()){
            TextView stateView=request.getKey();stateView.setTag(dialog);
            downloads.statusForUrl(request.getValue(),value->{Object stateOwner=stateView.getTag();if(stateOwner instanceof AlertDialog&&((AlertDialog)stateOwner).isShowing())stateView.setText(value);});
        }
    }
    static boolean mediaDialogMayRefresh(boolean showing,boolean ownerActive,boolean navigationMatches,boolean sameOwner){
        return showing&&ownerActive&&navigationMatches&&sameOwner;
    }
    static String mediaFingerprint(String rawUrl){
        String normalized=webUrl(rawUrl);if(normalized==null)return rawUrl==null?"":rawUrl.split("#",2)[0];
        try{
            URI uri=new URI(normalized);String rawQuery=uri.getRawQuery();
            ArrayList<String[]> kept=new ArrayList<>();
            if(rawQuery!=null&&!rawQuery.isEmpty())for(String part:rawQuery.split("&",-1)){
                int eq=part.indexOf('=');String rawKey=eq<0?part:part.substring(0,eq);String decoded=URLDecoder.decode(rawKey,"UTF-8");
                if(!isVolatileMediaQueryKey(decoded.toLowerCase(Locale.ROOT)))kept.add(new String[]{decoded,part});
            }
            kept.sort(Comparator.comparing(pair->pair[0]));
            StringBuilder result=new StringBuilder();result.append(uri.getScheme().toLowerCase(Locale.ROOT)).append("://").append(uri.getHost().toLowerCase(Locale.ROOT));
            if(uri.getPort()!=-1&&!("https".equalsIgnoreCase(uri.getScheme())&&uri.getPort()==443))result.append(':').append(uri.getPort());
            result.append(uri.getRawPath()==null||uri.getRawPath().isEmpty()?"/":uri.getRawPath());
            if(!kept.isEmpty()){result.append('?');for(int i=0;i<kept.size();i++){if(i>0)result.append('&');result.append(kept.get(i)[1]);}}
            return result.toString();
        }catch(Exception ignored){return normalized.split("#",2)[0];}
    }
    private static boolean isVolatileMediaQueryKey(String key){
        return key.startsWith("x-amz-")||key.startsWith("x-oss-")||key.startsWith("x-cos-")||key.startsWith("x-qn-")||
            Arrays.asList("sign","signature","token","access_token","auth","auth_key","authorization","expire","expires","expire_at","timestamp","ts","wssecret","wstime","policy","key-pair-id").contains(key);
    }
    static boolean mergeObservedMedia(List<MediaItem> items,Set<String> keys,String rawUrl,String type,String title,String posterUrl,List<String> channels){
        String url=webUrl(rawUrl);if(url==null||items==null||keys==null||!("image".equals(type)||"video".equals(type)))return false;
        String fingerprint=mediaFingerprint(url),key=type+"|"+fingerprint;MediaItem existing=null;
        for(MediaItem item:items)if(item.type.equals(type)&&mediaFingerprint(item.url).equals(fingerprint)){existing=item;break;}
        if(existing==null){if(items.size()>=MAX_MEDIA)return false;String label=title==null?"资源":title;items.add(new MediaItem(url,type,label.substring(0,Math.min(label.length(),120)),posterUrl,channels));keys.add(key);return true;}
        boolean changed=!existing.url.equals(url);String oldUrl=existing.url;existing.url=url;
        if("video".equals(type)){
            if(posterUrl!=null&&!Objects.equals(existing.posterUrl,posterUrl)){existing.posterUrl=posterUrl;changed=true;}
            ArrayList<String> merged=new ArrayList<>();addMediaChannel(merged,url);
            if(channels!=null)for(String value:channels)addMediaChannel(merged,value);
            for(String value:existing.channels)if(!oldUrl.equals(value))addMediaChannel(merged,value);
            if(!existing.channels.equals(merged)){existing.channels.clear();existing.channels.addAll(merged);changed=true;}
        }
        keys.remove(type+"|"+mediaFingerprint(oldUrl));keys.add(key);return changed;
    }
    private void dismissMediaDialog(){AlertDialog current=mediaDialog;if(current!=null)current.dismiss();}
    private void showMediaChannels(MediaItem item) {
        if(item==null||item.channels.size()<2)return;
        String[] labels=new String[item.channels.size()];
        for(int i=0;i<labels.length;i++)labels[i]="渠道 "+(i+1)+" · "+Uri.parse(item.channels.get(i)).getHost();
        new AlertDialog.Builder(this).setTitle("选择视频渠道").setItems(labels,(d,index)->showMediaChannelActions(item,index)).setNegativeButton("取消",null).show();
    }
    private void showMediaChannelActions(MediaItem item,int index) {
        String url=mediaChannelAt(item.channels,index);if(url==null)return;
        new AlertDialog.Builder(this).setTitle("渠道 "+(index+1)).setItems(new String[]{"复制链接","分享链接","在新标签打开","下载","选择位置下载"},(d,action)->{
            if(action==0)copyMediaUrl(url);
            else if(action==1)shareMediaUrl(url);
            else if(action==2)newTab(url);
            else if(action==3)confirmMediaDownload(url,null);
            else chooseMediaDownloadLocation(url,item.title);
        }).setNegativeButton("取消",null).show();
    }
    private void chooseMediaDownloadLocation(String url,String title) {
        new AlertDialog.Builder(this).setTitle("选择保存位置？")
            .setMessage("直接请求 HTTPS 资源并写入你选择的位置。不附带 WebView Cookie、授权头或 Referer；不跟随重定向，最大 512 MiB。需登录的链接通常无法保存。")
            .setNegativeButton("取消",null).setPositiveButton("选择位置",(d,w)->downloads.chooseLocation(url,null,title)).show();
    }
    private void confirmMediaDownload(String url,TextView statusView) {
        downloads.confirm(url,null,null,state->{if(statusView!=null){Object owner=statusView.getTag();if(owner instanceof AlertDialog&&((AlertDialog)owner).isShowing())statusView.setText(state);}});
    }
    static List<String> normalizeMediaChannels(JSONArray raw,String primaryUrl) {
        ArrayList<String> result=new ArrayList<>();
        addMediaChannel(result,primaryUrl);
        if(raw!=null)for(int i=0;i<raw.length()&&result.size()<2;i++)addMediaChannel(result,raw.optString(i,null));
        return result;
    }
    private static void addMediaChannel(List<String> result,String raw) {
        String url=webUrl(raw);if(url!=null&&!result.contains(url)&&result.size()<2)result.add(url);
    }
    static String mediaChannelAt(List<String> channels,int index) {
        if(channels==null||index<0||index>=channels.size())return null;
        return webUrl(channels.get(index));
    }
    static void clearObservedMedia(Collection<?> items,Set<String> keys) {
        if(items!=null)items.clear();
        if(keys!=null)keys.clear();
    }
    static boolean writeMediaUrlToClipboard(Context context,String url) {
        if(context==null||url==null||url.trim().isEmpty())return false;
        ClipboardManager clipboard=(ClipboardManager)context.getSystemService(CLIPBOARD_SERVICE);
        if(clipboard==null)return false;
        clipboard.setPrimaryClip(mediaUrlClip(url));
        return true;
    }
    static ClipData mediaUrlClip(String url) {
        if(url==null||url.trim().isEmpty())return null;
        return ClipData.newPlainText("媒体链接",url);
    }
    private void copyMediaUrl(String url) {
        Ui.toast(this,writeMediaUrlToClipboard(this,url)?"资源链接已复制":"资源链接复制失败");
    }
    private void addMediaAction(LinearLayout row,String label,Runnable action) {
        Button button=Ui.button(this,label,action);
        row.addView(button,new LinearLayout.LayoutParams(0,Ui.dp(this,48),1));
    }
    private void previewImage(MediaItem item) {
        previewImage(item.url,item.title);
    }
    private void previewImage(String imageUrl,String imageTitle) {
        java.net.URL url=MediaPreview.validateImageUrl(imageUrl,"image");
        if(url==null){Ui.toast(this,"仅支持 HTTPS 图片预览");return;}
        LinearLayout body=Ui.column(this);Ui.pad(body,12);
        ProgressBar progress=new ProgressBar(this);body.addView(progress);
        ImageView image=new ImageView(this);image.setAdjustViewBounds(true);image.setMaxHeight(Ui.dp(this,420));image.setScaleType(ImageView.ScaleType.FIT_CENTER);body.addView(image);
        TextView message=Ui.text(this,"正在获取公开图片…",13,Ui.MUTED);body.addView(message);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(imageTitle+" · 不使用页面凭据")
                .setView(body).setNegativeButton("关闭",null).setPositiveButton("在网页打开",(d,w)->newTab(imageUrl)).create();
        dialog.show();
        previewExecutor.execute(()->{
            android.graphics.Bitmap bitmap=null;String error=null;
            try { bitmap=MediaPreview.fetch(url); } catch(Exception e) { error="预览失败："+e.getMessage(); }
            final android.graphics.Bitmap result=bitmap;final String failure=error;
            handler.post(()->{
                if(destroyed||isFinishing()||!dialog.isShowing()) { if(result!=null)result.recycle();return; }
                progress.setVisibility(View.GONE);
                if(result!=null){image.setImageBitmap(result);message.setText("预览完成（缩放至最长边 512 px）");}
                else message.setText(failure==null?"预览失败":failure);
            });
        });
    }
    static String validatedMediaShareUrl(String rawUrl) {
        if(rawUrl==null||!rawUrl.equals(rawUrl.trim())||!rawUrl.regionMatches(true,0,"https://",0,8))return null;
        return webUrl(rawUrl);
    }
    static Intent mediaShareIntent(String rawUrl) {
        String url=validatedMediaShareUrl(rawUrl);
        if(url==null)return null;
        Intent intent=new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT,url);
        intent.setClipData(ClipData.newPlainText("媒体链接",url));
        return intent;
    }
    private void shareMediaUrl(String url) {
        Intent intent=mediaShareIntent(url);
        if(intent==null){Ui.toast(this,"仅可分享有效的 HTTPS 资源链接");return;}
        try { startActivity(Intent.createChooser(intent,"分享资源链接")); }
        catch(ActivityNotFoundException e) { Ui.toast(this,"没有可用的分享应用"); }
    }
    static String joinMediaUrls(Collection<String> urls) {
        StringBuilder result=new StringBuilder();
        if(urls==null)return "";
        for(String raw:urls) {
            String url=webUrl(raw);
            if(url==null)continue;
            if(result.length()>0)result.append('\n');
            result.append(url);
        }
        return result.toString();
    }
    private void copyMediaUrls(List<MediaItem> items) {
        ArrayList<String> urls=new ArrayList<>();for(MediaItem item:items)urls.add(item.url);
        String text=joinMediaUrls(urls);
        Ui.toast(this,writeMediaUrlToClipboard(this,text)?"已复制全部 HTTPS 资源链接":"没有可复制的资源链接");
    }
    private void showDurationTool() {
        boolean enabled=prefs.getBoolean(DolaPageScripts.PREF_KEY,false);
        if(active==null||active.view==null){Ui.toast(this,"页面尚未加载");return;}
        final Tab t=active;final int navigation=t.navigation;
        // Diagnostics are read-only; never fall back to legacy extension APIs.
        t.view.evaluateJavascript("JSON.stringify(window.__dolaWebViewPageScripts||null)",value->{
            if(destroyed||t.closed||t.view==null||t.navigation!=navigation||t!=active)return;
            JSONObject state=parseJsObject(value);
            String text=DolaPageScripts.summary(enabled,t.pageScriptsRegistered,
                state!=null&&state.optBoolean("skillPack",false),state!=null&&state.optBoolean("mediaUpload",false));
            new AlertDialog.Builder(this).setTitle("30s（新附件 v1.6.14 / 本空间）")
                .setMessage(text+"\n"+durationToolCapabilitySummary(false)+
                    "\n仅执行新附件两个页面主脚本；所有旧 JS 仅备份。配置显示、上传桥安装不代表实际上传或服务端接受 30 秒。")
                .setNegativeButton("关闭",null)
                .setPositiveButton(enabled?"关闭本空间 30s":"开启本空间 30s",(d,w)->confirmPageScriptsMode(!enabled)).show();
        });
    }
    static String platformSelectionSummary(String status) {
        if("selection_matches_30s".equals(status))return "页面控件检测：显示 Seedance 2.5 + 30s（只读；不代表服务端接受或实际生成时长）";
        if("detected_not_30s".equals(status))return "页面控件检测：已找到视频模型/时长控件，但当前选择不是 Seedance 2.5 + 30s";
        if("not_applicable".equals(status))return "页面控件检测：此功能仅适用于 Dola 页面";
        return "页面控件检测：未检测到可见的视频模型/时长控件；不推断平台能力";
    }
    static String durationToolCapabilitySummary(boolean ignoredLegacyProbe) {
        return "页面资源观察：未启用（旧 JS 仅备份，不执行）\n"+
            "Debugger 深度/自动嗅探：不可用（Android WebView 不提供 Chrome MV3 Debugger）\n"+
            "Chrome 扩展面板/后台接口：不可注入，未生效\n"+
            "fallback API 解析：未实现；旧时间轴工具与隐私页面 JS 不执行";
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
    private void setDurationState(boolean enabled) { confirmPageScriptsMode(enabled); }
    private void showTimelineRewrite() { Ui.toast(this,"旧提示词工具 JS 仅备份，不执行"); }
    private void showOriginalScriptOptions() { Ui.toast(this,"旧版脚本及选项仅备份，不执行"); }
    private void showTimelineResult(String result) {
        TextView output=Ui.text(this,result,14,Ui.INK);output.setTextIsSelectable(true);output.setGravity(Gravity.TOP|Gravity.START);
        new AlertDialog.Builder(this).setTitle("转换结果（请手动检查）").setView(Ui.scroll(this,output))
            .setNegativeButton("关闭",null).setPositiveButton("复制",(d,w)->{
                ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("提示词",result));Ui.toast(this,"结果已复制");
            }).show();
    }
    private void showMenu() {
        new AlertDialog.Builder(this).setTitle(title).setItems(new String[]{"30S 工具","创作工作台","新建标签","前进","回到首页","添加当前页书签","打开书签","下载中心","Cookie / CK 导入导出","登录与空间说明","回到空间列表","标签管理（"+tabs.size()+"）","顺序视频"},(d,n)->{
            switch(n){case 0:showDurationTool();break;case 1:openStudio();break;case 2:newTab(home);break;
                case 3:if(active.view!=null&&active.view.canGoForward())active.view.goForward();break;
                case 4:load(home);break;case 5:addBookmark();break;case 6:showBookmarks();break;
                case 7:downloads.show();break;case 8:cookies.show();break;case 9:showLoginHelp();break;case 10:returnToCatalog();break;case 11:showTabs();break;case 12:openSequentialVideo();break;}
        }).show();
    }
    private void showLoginHelp() {
        new AlertDialog.Builder(this).setTitle("独立空间与登录")
            .setMessage("本空间使用独立进程、WebView 数据及缓存目录，Cookie/网站存储只在同一空间标签之间共享，不复制到其他空间。64 是独立档案数量，不是同时常驻 64 个进程；Android 会回收后台。\n\nGoogle OAuth 不允许嵌入式 WebView 授权，可能返回 disallowed_useragent。本应用不伪装浏览器、不复制外部浏览器 Google Cookie，也不能保证在 WebView 完成 Google 登录。请使用站点允许的其他登录方式。外部浏览器登录不会自动导入本空间。")
            .setPositiveButton("知道了",null).show();
    }
    private void confirmPageScriptsMode(boolean enabled) {
        if(enabled&&!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)){
            Ui.toast(this,"当前 WebView 不支持文档开始注入，未开启 30s");return;
        }
        String detail=enabled
            ?"仅为本空间开启。Dola HTTPS 文档开始时，按附件顺序原样执行 skill-pack-main.js、media-upload-main.js，默认页面时长设为 30s。新附件版本 v1.6.14；全部旧 JS 不执行。前者会调整本地能力配置显示，后者安装上传桥/地址规范化；仍依赖站点运行时，不保证实际上传、30 秒生成或平台接受请求。Chrome 调试器、后台及扩展面板接口不可注入，不会伪装可用。旧版 duration、适配器、资源观察、提示词和隐私页面 JS 均只备份，不使用。"
            :"仅关闭本空间两个页面主脚本，并将下次 Dola 页面本地时长设为 15s。不会删除 Cookie/账号数据，也不会改变其他空间；旧 JS 始终保持仅备份。";
        new AlertDialog.Builder(this).setTitle(enabled?"开启本空间 30s？":"关闭本空间 30s？")
            .setMessage(detail+"\n为移除/安装脚本钩子，本空间所有打开标签将重新加载；未提交输入会丢失。")
            .setNegativeButton("取消",null).setPositiveButton(enabled?"开启并重载":"关闭并重载",(d,w)->{
                if(!prefs.edit().putBoolean(DolaPageScripts.PREF_KEY,enabled).commit()){Ui.toast(this,"设置未保存");return;}
                syncScriptSwitch();reloadTabsForScriptMode();
            }).show();
    }
    private void confirmPrivacyCleanMode(boolean enabled) {
        String detail=enabled
            ?"开启后使用 Android WebView 原生 UA（不伪造机型/系统身份），阻止第三方 Cookie、关闭网页定位 API；旧隐私页面 JS 已停用，所以不再宣称 Canvas/WebGL/音频/WebRTC/设备枚举保护。不是匿名保证，也不保证阻止定向广告。依赖第三方 Cookie/定位的功能可能失效；确认后所有标签重载，未提交输入会丢失。"
            :"关闭后恢复应用标识 UA 并允许第三方 Cookie。所有标签重载，未提交输入会丢失。";
        new AlertDialog.Builder(this).setTitle(enabled?"开启隐私洁净模式？":"关闭隐私洁净模式？")
            .setMessage(detail).setNegativeButton("取消",null).setPositiveButton(enabled?"开启并重载":"关闭并重载",(d,w)->{
                if(!prefs.edit().putBoolean("privacyCleanMode",enabled).commit()){Ui.toast(this,"设置未保存");return;}
                syncScriptSwitch();reloadTabsForScriptMode();
            }).show();
    }
    private void confirmOriginalDolaMode(boolean ignored) { showOriginalScriptOptions(); }
    private void reloadTabsForScriptMode() {
        if(viewport!=null)viewport.removeAllViews();
        if(fileCallback!=null)cancelFile();
        promptTab=null;
        for(Tab tab:tabs){
            if(tab.view!=null){try{tab.view.stopLoading();tab.view.destroy();}catch(Exception ignored){}tab.view=null;}
            tab.navigation++;tab.probe=false;tab.androidTools=false;tab.pageScriptsRegistered=false;tab.failed=false;tab.media.clear();tab.mediaKeys.clear();
        }
        if(active!=null&&!active.closed)select(active);
        Ui.toast(this,"本空间设置已保存，标签 WebView 已重建；实际脚本状态见 30S 工具");
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
    @Override protected void onResume(){super.onResume();resumed=true;if(active!=null&&active.view!=null)active.view.onResume();if(downloads!=null)downloads.resume();handler.removeCallbacks(scanLoop);handler.postDelayed(scanLoop,1200);}
    @Override protected void onPause(){resumed=false;handler.removeCallbacks(scanLoop);if(downloads!=null)downloads.pause();if(active!=null&&active.view!=null)active.view.onPause();saveTabs();if(prefs!=null)CookieManager.getInstance().flush();super.onPause();}
    @Override public void onBackPressed(){goBack();}
    @Override protected void onSaveInstanceState(Bundle out){saveTabs();super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){
        destroyed=true;dismissMediaDialog();handler.removeCallbacksAndMessages(null);previewExecutor.shutdownNow();cancelFile();if(cookies!=null)cookies.close();if(downloads!=null)downloads.close();if(viewport!=null)viewport.removeAllViews();
        for(Tab t:tabs){t.closed=true;if(t.view!=null){t.view.stopLoading();t.view.destroy();t.view=null;}}super.onDestroy();
    }
    static final class MediaItem{String url;final String type,title;String posterUrl;final ArrayList<String> channels=new ArrayList<>();MediaItem(String u,String t,String n){this(u,t,n,null,Collections.emptyList());}MediaItem(String u,String t,String n,String p){this(u,t,n,p,Collections.emptyList());}MediaItem(String u,String t,String n,String p,List<String> c){url=u;type=t;title=n;posterUrl=p;mergeChannels(c);}void mergeChannels(Collection<String> values){if(values==null)return;for(String value:values)addMediaChannel(channels,value);}}
    public static class Space0 extends BrowserActivity{}
    public static class Space1 extends BrowserActivity{}
    public static class Space2 extends BrowserActivity{}
    public static class Space3 extends BrowserActivity{}
    public static class Space4 extends BrowserActivity{}
    public static class Space5 extends BrowserActivity{}
    public static class Space6 extends BrowserActivity{}
    public static class Space7 extends BrowserActivity{}
    public static class Space8 extends BrowserActivity{}
    public static class Space9 extends BrowserActivity{}
    public static class Space10 extends BrowserActivity{}
    public static class Space11 extends BrowserActivity{}
    public static class Space12 extends BrowserActivity{}
    public static class Space13 extends BrowserActivity{}
    public static class Space14 extends BrowserActivity{}
    public static class Space15 extends BrowserActivity{}
    public static class Space16 extends BrowserActivity{}
    public static class Space17 extends BrowserActivity{}
    public static class Space18 extends BrowserActivity{}
    public static class Space19 extends BrowserActivity{}
    public static class Space20 extends BrowserActivity{}
    public static class Space21 extends BrowserActivity{}
    public static class Space22 extends BrowserActivity{}
    public static class Space23 extends BrowserActivity{}
    public static class Space24 extends BrowserActivity{}
    public static class Space25 extends BrowserActivity{}
    public static class Space26 extends BrowserActivity{}
    public static class Space27 extends BrowserActivity{}
    public static class Space28 extends BrowserActivity{}
    public static class Space29 extends BrowserActivity{}
    public static class Space30 extends BrowserActivity{}
    public static class Space31 extends BrowserActivity{}
    public static class Space32 extends BrowserActivity{}
    public static class Space33 extends BrowserActivity{}
    public static class Space34 extends BrowserActivity{}
    public static class Space35 extends BrowserActivity{}
    public static class Space36 extends BrowserActivity{}
    public static class Space37 extends BrowserActivity{}
    public static class Space38 extends BrowserActivity{}
    public static class Space39 extends BrowserActivity{}
    public static class Space40 extends BrowserActivity{}
    public static class Space41 extends BrowserActivity{}
    public static class Space42 extends BrowserActivity{}
    public static class Space43 extends BrowserActivity{}
    public static class Space44 extends BrowserActivity{}
    public static class Space45 extends BrowserActivity{}
    public static class Space46 extends BrowserActivity{}
    public static class Space47 extends BrowserActivity{}
    public static class Space48 extends BrowserActivity{}
    public static class Space49 extends BrowserActivity{}
    public static class Space50 extends BrowserActivity{}
    public static class Space51 extends BrowserActivity{}
    public static class Space52 extends BrowserActivity{}
    public static class Space53 extends BrowserActivity{}
    public static class Space54 extends BrowserActivity{}
    public static class Space55 extends BrowserActivity{}
    public static class Space56 extends BrowserActivity{}
    public static class Space57 extends BrowserActivity{}
    public static class Space58 extends BrowserActivity{}
    public static class Space59 extends BrowserActivity{}
    public static class Space60 extends BrowserActivity{}
    public static class Space61 extends BrowserActivity{}
    public static class Space62 extends BrowserActivity{}
    public static class Space63 extends BrowserActivity{}
}
