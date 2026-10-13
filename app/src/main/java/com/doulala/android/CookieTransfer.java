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
    private final int spaceSlot;
    private final CkImportBindings bindings;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private byte[] pendingExport;
    private String importTarget;
    private boolean busy, closed, writing;
    CookieTransfer(Activity a, Supplier<String> url,int slot) {
        if(slot<0||slot>=64)throw new IllegalArgumentException("invalid space");
        activity=a;currentUrl=url;spaceSlot=slot;
        bindings=new CkImportBindings(a.getNoBackupFilesDir().toPath().resolve("ck-import-bindings"));
    }
    private boolean alive() { return !closed && !activity.isFinishing() && !activity.isDestroyed(); }
    private void tell(String text) { if(alive()) Ui.toast(activity,text); }

    void show() {
        if(busy) {tell("正在处理 Cookie 文件，请稍候");return;}
        final String url=currentUrl.get();
        final String target;
        try {target=CookieRules.target(url);} catch(Exception e){tell("请先打开 HTTPS 网页");return;}
        new AlertDialog.Builder(activity).setTitle("Cookie 手动迁移（有限快照）")
            .setMessage("当前站点："+target+"\n\n只处理你主动选择的文件，不读取电脑数据。快照只有当前 URL 的名称和值，不含原始 Domain、Path、到期时间或安全属性，也不含网页本地存储。\n\n导入支持本应用快照；也支持 DolaFetchCk 的单账号 CK 文件和 CK汇总.json；汇总文件每次只能选一个账号且仅限 HTTPS dola.com 页面。CK 文件没有来源站点字段，请确认来源确为 dola.com。只导入 Cookie；UA、设备指纹、代理等字段全部忽略。普通导入使用当前主机、根路径、Secure / HttpOnly / SameSite=Lax 会话 Cookie。Dola CK 另提供需要二次确认的兼容方式：推定为 dola.com 域 Cookie，仅两个明确的 CSRF 字段允许网页读取；其它凭证保持 HttpOnly。CK 缺失原始属性，两种方式均不能保证恢复登录。")
            .setNegativeButton("取消",null)
            .setNeutralButton("导出快照",(d,w)->confirmExport(url,target))
            .setPositiveButton("导入文件",(d,w)->pickImport(target)).show();
    }

    // One tap opens SAF; account choice and destructive-login confirmation remain mandatory.
    void importFileDirect() {
        if(busy){tell("正在处理 Cookie 文件，请稍候");return;}
        try {pickImport(CookieRules.target(currentUrl.get()));}
        catch(Exception e){tell("请先打开 HTTPS 网页");}
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
                    if(o.has("accounts") || o.has("cookie")) {
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
                } catch(Exception ignored){activity.runOnUiThread(()->{busy=false;tell("导入被拒绝：需有效 UTF-8 快照或 Dola CK 文件/汇总，且来源站点、大小和 Cookie 格式符合限制");});}
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
        if(entries.size()==1) {
            confirmImport(target,entries.get(0).cookies,true);
            return;
        }
        new AlertDialog.Builder(activity).setTitle("选择一个 Dola CK 账号")
            // AlertDialog lists cannot render a message and list together reliably on MIUI.
            .setItems(labels,(dialog,index)->{
                if(index>=0 && index<entries.size())confirmImport(target,entries.get(index).cookies,true);
            }).setNegativeButton("取消",null).show();
    }

    private void confirmImport(String target,List<String> pairs,boolean fromCk) {
        if(!CookieRules.sameSite(target,currentUrl.get())){tell("当前站点已变更，未导入任何 Cookie");return;}
        if(fromCk && !CkCookieImport.isDolaTarget(target)){tell("CK 仅能导入 Dola HTTPS 页面");return;}
        String detail=fromCk
            ?"来源：Dola CK 文件（仅所选账号的 Cookie）；UA、设备指纹和代理均不会导入。\n\n导入前请确认这是你自己的 Dola 账号 CK。Cookie 是登录凭证，可能立即访问账号；将覆盖同名根路径 Cookie，其他 Cookie 不删除。"
            :"仅导入自己的账号文件。将覆盖同名根路径 Cookie；其他路径和其他 Cookie 不删除。导入不是完整登录恢复。建议先导出当前快照。";
        AlertDialog.Builder confirmation=new AlertDialog.Builder(activity).setTitle("确认修改本空间登录态？")
            .setMessage("站点："+target+"\n数量："+pairs.size()+"\n\n"+detail+(CkCookieImport.isDolaTarget(target)?importRiskSummary():""))
            .setNegativeButton("取消",null).setPositiveButton("导入",(d,w)->{
                if(!CookieRules.sameSite(target,currentUrl.get())){tell("站点已变更，操作取消");return;}
                beginConfirmedImport(target,pairs,false);
            });
        if(fromCk)confirmation.setNeutralButton("Dola兼容方式",(d,w)->confirmDolaCompatibility(target,pairs));
        AlertDialog shown=confirmation.show();
        // Framework button themes may transform "Dola" to "DOLA".
        // Keep the visible action identical to the documented/tested label.
        if(fromCk)shown.getButton(AlertDialog.BUTTON_NEUTRAL).setAllCaps(false);
    }

    private void confirmDolaCompatibility(String target,List<String> pairs) {
        if(!CkCookieImport.isDolaTarget(target) || !CookieRules.sameSite(target,currentUrl.get())) {
            tell("当前 Dola 站点已变更，未导入");return;
        }
        try {for(String pair:pairs)CookieRules.restoredDolaCookie(pair,target);}
        catch(IllegalArgumentException invalid) {tell("Cookie 不符合 Dola 兼容格式，未写入");return;}
        new AlertDialog.Builder(activity).setTitle("确认使用 Dola CK 兼容方式？")
            .setMessage("这不是原始 Cookie 属性的完整恢复。只用于你自己的 Dola CK。\n\n"
                +"将先删除当前主机同名根路径 Cookie（不清空空间），再写入 dola.com 域，供其子域使用；__Host- Cookie 仍仅限当前主机。"
                +"仅 passport_csrf_token 和 passport_csrf_token_default 允许网页脚本读取；会话与其它 Cookie 保持 HttpOnly。"
                +"使用 Secure / SameSite=Lax 会话 Cookie，不延长服务端有效期，不导入 UA、指纹或代理。"
                +"\n\n部分失败可能改变旧登录态；建议先导出快照。不同路径旧 Cookie 不清除，仍可能冲突。不能保证不掉线，若失败应在本空间正常登录。"+importRiskSummary())
            .setNegativeButton("取消",null).setPositiveButton("确认兼容导入",(d,w)->{
                if(!CkCookieImport.isDolaTarget(target) || !CookieRules.sameSite(target,currentUrl.get())) {
                    tell("站点已变更，未导入");return;
                }
                beginConfirmedImport(target,pairs,true);
            }).show();
    }

    static String importRiskSummary() {
        return "\n\n风险提示：CK 不是完整网页档案，不含原网页 localStorage、设备语境或全部 Cookie 属性。桌面报告观察到部分导入会话发送后返回 710022002 并登出；安卓根因与固定目录能否解决尚未证实。优先在本空间官网正常登录，保持同一空间，不自动发送、重试、换号或重建目录。\n\n本机只保存带随机密钥的 CK 摘要，阻止完全相同 CK 跨空间重复导入；不是服务端账号/设备绑定，会话轮换或 CK 变化可能无法识别。已确认的失败/部分导入也保留尝试档案，直到明确清除本空间。";
    }

    private void beginConfirmedImport(String target,List<String> pairs,boolean compatibility) {
        if(busy){tell("正在处理 Cookie 文件，请稍候");return;}
        if(!alive()||!CookieRules.sameSite(target,currentUrl.get())){tell("站点已变更，操作取消");return;}
        busy=true;writing=true;
        if(!CkCookieImport.isDolaTarget(target)){apply(target,pairs,0,0,compatibility);return;}
        io.execute(()->{
            CkImportBindings.Decision decision=null;
            try{decision=bindings.reserve(spaceSlot,pairs);}catch(IOException|RuntimeException ignored){}
            final CkImportBindings.Decision result=decision;
            activity.runOnUiThread(()->{
                if(!alive()||!CookieRules.sameSite(target,currentUrl.get())){
                    finishBeforeWrite();tell("操作已中断，未写入 Cookie；已确认的尝试档案保留");return;
                }
                if(result==null){finishBeforeWrite();tell("无法安全保存 CK 导入档案，未写入 Cookie；请使用官网登录，不自动清除或重试");return;}
                if(!result.allowed){
                    finishBeforeWrite();new AlertDialog.Builder(activity).setTitle("拒绝跨空间重复导入 CK")
                        .setMessage("完全相同的 CK 已在 SPACE "+(result.otherSlot+1)+" 有导入尝试。请回到原空间使用；不要在新目录反复试登。本次未写入任何 Cookie。此检测不能识别轮换后的同一账号，也不代表 CK 当前有效。")
                        .setPositiveButton("知道了",null).show();return;
                }
                apply(target,pairs,0,0,compatibility);
            });
        });
    }
    private void finishBeforeWrite(){busy=false;writing=false;if(closed)io.shutdown();}

    private void apply(String target,List<String> pairs,int index,int accepted) {
        apply(target,pairs,index,accepted,false);
    }
    private void apply(String target,List<String> pairs,int index,int accepted,boolean compatibility) {
        if(index==pairs.size()) {
            // Flush asynchronously; do not claim that accepting a cookie means successful login.
            io.execute(()->{
                boolean ok=true;try{CookieManager.getInstance().flush();}catch(Exception e){ok=false;}
                final boolean flushed=ok;
                activity.runOnUiThread(()->{
                    busy=false;writing=false;
                    tell(flushed?"Cookie 写入接受 "+accepted+" / "+pairs.size()+"（不代表登录成功）；请手动刷新并检查登录。部分失败不会自动回滚，不自动发送或重试。":"Cookie 保存失败；部分值可能已改变，请手动检查登录。");
                    if(closed)io.shutdown();
                });
            });return;
        }
        try {
            String pair=pairs.get(index);
            String cookie=compatibility?CookieRules.restoredDolaCookie(pair,target):CookieRules.restoredCookie(pair);
            if(compatibility) {
                // Replace the old host-only root cookie, not every cookie in this profile.
                // Otherwise a domain cookie can coexist with a stale HttpOnly host cookie.
                CookieManager.getInstance().setCookie(target,CookieRules.expiredHostCookie(pair),cleared->{
                    if(Boolean.TRUE.equals(cleared))writeCookie(target,pairs,index,accepted,true,cookie);
                    else apply(target,pairs,index+1,accepted,true);
                });
            } else writeCookie(target,pairs,index,accepted,false,cookie);
        } catch(RuntimeException ignored) {
            // A rejected item must not leave the whole transfer permanently busy.
            apply(target,pairs,index+1,accepted,compatibility);
        }
    }
    private void writeCookie(String target,List<String> pairs,int index,int accepted,
            boolean compatibility,String cookie) {
        try {
            CookieManager.getInstance().setCookie(target,cookie,
                ok->apply(target,pairs,index+1,accepted+(Boolean.TRUE.equals(ok)?1:0),compatibility));
        } catch(RuntimeException ignored) {apply(target,pairs,index+1,accepted,compatibility);}
    }
    void close(){closed=true;if(pendingExport!=null)Arrays.fill(pendingExport,(byte)0);pendingExport=null;importTarget=null;/* Allow an already confirmed import to finish. */if(!writing)io.shutdown();}
}
