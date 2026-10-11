package com.doulala.android;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.webkit.CookieManager;
import org.json.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** User-initiated, origin-bound SAF transfers. Secrets never enter logs, prefs or Bundles. */
final class CookieTransfer {
    private static final int EXPORT=8210, IMPORT=8211;
    private final Activity activity;
    private final Supplier<String> currentUrl;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private byte[] pendingExport;
    private String importTarget;
    private boolean busy, closed, writing;
    CookieTransfer(Activity a, Supplier<String> url) { activity=a;currentUrl=url; }
    private boolean alive() { return !closed && !activity.isFinishing() && !activity.isDestroyed(); }
    private void tell(String text) { if(alive()) Ui.toast(activity,text); }

    void show() {
        if(busy) {tell("正在处理 Cookie 文件，请稍候");return;}
        final String url=currentUrl.get();
        final String target;
        try {target=CookieRules.target(url);} catch(Exception e){tell("请先打开 HTTPS 网页");return;}
        new AlertDialog.Builder(activity).setTitle("Cookie 手动迁移（有限快照）")
            .setMessage("当前站点："+target+"\n\n只处理你主动选择的文件，不读取电脑数据。快照只有当前 URL 的名称和值，不含原始 Domain、Path、到期时间或安全属性，也不含网页本地存储。\n\n导入支持本应用快照；也支持 CK汇总.json，但每次只能选一个账号且仅限 HTTPS dola.com 页面。CK 文件没有来源站点字段，请确认来源确为 dola.com。只导入 Cookie；UA、设备指纹、代理等字段全部忽略。导入均使用当前主机、根路径、Secure / HttpOnly / SameSite=Lax 会话 Cookie；不能保证恢复登录。")
            .setNegativeButton("取消",null)
            .setNeutralButton("导出快照",(d,w)->confirmExport(url,target))
            .setPositiveButton("导入文件",(d,w)->pickImport(target)).show();
    }

    private void confirmExport(String url,String target) {
        if(!CookieRules.sameSite(url,currentUrl.get())) {tell("页面已变更，请重新打开迁移菜单");return;}
        new AlertDialog.Builder(activity).setTitle("文件包含登录凭证")
            .setMessage("将明文导出 "+target+" 的 Cookie。持有文件的人可能访问你的账号。仅保存在你控制的本地位置，不要发给他人或上传仓库，用完请手动删除。")
            .setNegativeButton("取消",null).setPositiveButton("选择保存位置",(d,w)->{
                if(!CookieRules.sameSite(url,currentUrl.get())) {tell("页面已变更，未导出");return;}
                try {
                    String header=CookieManager.getInstance().getCookie(url);
                    CookieRules.parse(header);
                    JSONObject o=new JSONObject().put("schemaVersion",1).put("kind","doulala-cookie-snapshot")
                        .put("origin",target).put("cookieHeader",header);
                    pendingExport=o.toString(2).getBytes(StandardCharsets.UTF_8);busy=true;
                    Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json").putExtra(Intent.EXTRA_TITLE,"cookie-private-snapshot.json");
                    activity.startActivityForResult(i,EXPORT);
                } catch(Exception e){pendingExport=null;busy=false;tell("无可迁移 Cookie，或格式不受支持；未导出");}
            }).show();
    }
    private void pickImport(String target) {
        importTarget=target;busy=true;
        try {activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),IMPORT);}
        catch(Exception e){busy=false;importTarget=null;tell("无法打开系统文件选择器");}
    }

    boolean onResult(int request,int result,Intent data) {
        if(request!=EXPORT && request!=IMPORT)return false;
        Uri uri=data==null?null:data.getData();
        if(result!=Activity.RESULT_OK || uri==null || !"content".equals(uri.getScheme())) {
            pendingExport=null;importTarget=null;busy=false;return true;
        }
        if(request==EXPORT) {
            final byte[] bytes=pendingExport;pendingExport=null;
            if(bytes==null){busy=false;tell("操作已中断，请重新导出");return true;}
            io.execute(()->{
                boolean ok=false;
                try(OutputStream out=activity.getContentResolver().openOutputStream(uri,"wt")) {
                    if(out==null)throw new IOException();out.write(bytes);ok=true;
                } catch(Exception ignored) {} finally {Arrays.fill(bytes,(byte)0);}
                final boolean success=ok;
                activity.runOnUiThread(()->{busy=false;tell(success?"快照已保存，请妥善保管":"写入失败，可能留下不完整文件，请手动删除");});
            });
        } else {
            final String target=importTarget;importTarget=null;
            if(target==null){busy=false;tell("操作已中断，请重新导入");return true;}
            io.execute(()->{
                try {
                    String text;
                    try(InputStream in=activity.getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                        if(in==null)throw new IOException();byte[] b=new byte[4096];int n;
                        while((n=in.read(b))!=-1){if(out.size()+n>128*1024)throw new IOException();out.write(b,0,n);}
                        text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(out.toByteArray())).toString();
                    }
                    JSONObject o=new JSONObject(text);
                    if(o.has("accounts")) {
                        if(!CkCookieImport.isDolaTarget(target))throw new IOException();
                        List<CkCookieImport.Entry> entries=CkCookieImport.parse(text);
                        activity.runOnUiThread(()->{busy=false;if(alive())chooseCkAccount(target,entries);});
                    } else {
                        if(!Integer.valueOf(1).equals(o.get("schemaVersion")) || !"doulala-cookie-snapshot".equals(o.get("kind"))
                            || !(o.get("origin") instanceof String) || !(o.get("cookieHeader") instanceof String)
                            || !CookieRules.sameSite(o.getString("origin"),target))throw new IOException();
                        List<String> pairs=CookieRules.parse(o.getString("cookieHeader"));
                        activity.runOnUiThread(()->{busy=false;if(alive())confirmImport(target,pairs,false);});
                    }
                } catch(Exception ignored){activity.runOnUiThread(()->{busy=false;tell("导入被拒绝：需同站点、UTF-8、版本 1 的 Cookie 快照，且格式/大小有效");});}
            });
        }
        return true;
    }

    private void chooseCkAccount(String target,List<CkCookieImport.Entry> entries) {
        if(!CkCookieImport.isDolaTarget(target) || !CookieRules.sameSite(target,currentUrl.get())) {
            tell("当前页面不是原始 Dola 站点，未导入 CK");return;
        }
        String[] labels=new String[entries.size()];
        for(int i=0;i<entries.size();i++) labels[i]=entries.get(i).label+"（"+entries.get(i).cookies.size()+" 项 Cookie）";
        new AlertDialog.Builder(activity).setTitle("选择一个 Dola CK 账号")
            .setMessage("每次仅导入一个账号。来源站点由你确认；不读取或应用 UA、指纹、代理字段。")
            .setItems(labels,(dialog,index)->{
                if(index>=0 && index<entries.size())confirmImport(target,entries.get(index).cookies,true);
            }).setNegativeButton("取消",null).show();
    }

    private void confirmImport(String target,List<String> pairs,boolean fromCk) {
        if(!CookieRules.sameSite(target,currentUrl.get())){tell("当前站点已变更，未导入任何 Cookie");return;}
        if(fromCk && !CkCookieImport.isDolaTarget(target)){tell("CK 仅能导入 Dola HTTPS 页面");return;}
        String detail=fromCk
            ?"来源：CK汇总.json（仅所选账号的 Cookie）；UA、设备指纹和代理均不会导入。\n\n导入前请确认这是你自己的 Dola 账号 CK。Cookie 是登录凭证，可能立即访问账号；将覆盖同名根路径 Cookie，其他 Cookie 不删除。"
            :"仅导入自己的账号文件。将覆盖同名根路径 Cookie；其他路径和其他 Cookie 不删除。导入不是完整登录恢复。建议先导出当前快照。";
        new AlertDialog.Builder(activity).setTitle("确认修改本空间登录态？")
            .setMessage("站点："+target+"\n数量："+pairs.size()+"\n\n"+detail)
            .setNegativeButton("取消",null).setPositiveButton("导入",(d,w)->{
                if(!CookieRules.sameSite(target,currentUrl.get())){tell("站点已变更，操作取消");return;}
                busy=true;writing=true;apply(target,pairs,0,0);
            }).show();
    }
    private void apply(String target,List<String> pairs,int index,int accepted) {
        if(index==pairs.size()) {
            // Flush asynchronously; do not claim that accepting a cookie means successful login.
            io.execute(()->{
                boolean ok=true;try{CookieManager.getInstance().flush();}catch(Exception e){ok=false;}
                final boolean flushed=ok;
                activity.runOnUiThread(()->{
                    busy=false;writing=false;
                    tell(flushed?"Cookie 写入接受 "+accepted+" / "+pairs.size()+"；请手动刷新并检查登录。部分失败不会自动回滚。":"Cookie 保存失败；部分值可能已改变，请手动检查登录。");
                    if(closed)io.shutdown();
                });
            });return;
        }
        try {
            CookieManager.getInstance().setCookie(target,CookieRules.restoredCookie(pairs.get(index)),
                ok->apply(target,pairs,index+1,accepted+(Boolean.TRUE.equals(ok)?1:0)));
        } catch(RuntimeException ignored) {
            // A rejected item must not leave the whole transfer permanently busy.
            apply(target,pairs,index+1,accepted);
        }
    }
    void close(){closed=true;if(pendingExport!=null)Arrays.fill(pendingExport,(byte)0);pendingExport=null;importTarget=null;/* Allow an already confirmed import to finish. */if(!writing)io.shutdown();}
}
