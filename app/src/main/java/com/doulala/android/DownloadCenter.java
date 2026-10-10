package com.doulala.android;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.webkit.URLUtil;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Tracks only explicit downloads from this profile. Does not attach cookies or auth headers. */
final class DownloadCenter {
    private static final int SAVE=8220, SAVE_DIRECT=8221, MAX_DOWNLOADS=100;
    static final long DIRECT_MAX_BYTES=512L*1024*1024;
    interface StatusCallback { void accept(String status); }
    private final Activity activity;
    private final SharedPreferences prefs;
    private final DownloadManager manager;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final String profile;
    private AlertDialog dialog;
    private LinearLayout list;
    private boolean closed, querying, resumed;
    private long pendingSave=-1;
    private String pendingDirectUrl, pendingDirectMime, pendingDirectName;
    private volatile HttpURLConnection directConnection;
    private final Runnable refresh=()->refresh();

    DownloadCenter(Activity a,SharedPreferences p,String name) {
        activity=a;prefs=p;profile=name;manager=(DownloadManager)a.getSystemService(Activity.DOWNLOAD_SERVICE);
    }
    private void tell(String s){if(!closed && !activity.isFinishing())Ui.toast(activity,s);}
    private Set<String> ids(){return new HashSet<>(prefs.getStringSet("downloads",Collections.emptySet()));}
    void enqueue(String raw,String disposition,String mime) {
        String url=UrlRules.normalize(raw);
        if(url==null || !url.startsWith("https://")){tell("只支持 HTTPS 下载；blob/data 资源暂不支持");return;}
        Set<String> ids=ids();
        if(ids.size()>=MAX_DOWNLOADS){tell("下载记录已达 100 项，请先在下载中心移除不需要的项目");return;}
        try {
            String name=UrlRules.safeFilename(URLUtil.guessFileName(url,disposition,mime),"resource.bin");
            DownloadManager.Request request=new DownloadManager.Request(Uri.parse(url))
                .setTitle(name).setDescription("豆啦啦 · "+profile)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true).setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(activity,Environment.DIRECTORY_DOWNLOADS,
                    profile+"/"+UUID.randomUUID()+"-"+name);
            if(mime!=null && !mime.contains("*") && mime.matches("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+"))request.setMimeType(mime);
            long id=manager.enqueue(request);ids.add(Long.toString(id));
            if(!prefs.edit().putStringSet("downloads",ids).putString(urlKey(id),url).commit()){manager.remove(id);throw new IOException();}
            tell("已加入下载，可在菜单 → 下载中心查看。使用移动数据时会消耗流量。");refresh();
        }catch(Exception e){tell("下载未加入，请检查系统下载服务、存储空间和网络");}
    }
    void confirm(String url,String disposition,String mime) {
        confirm(url,disposition,mime,null);
    }
    void confirm(String url,String disposition,String mime,StatusCallback callback) {
        new AlertDialog.Builder(activity).setTitle("下载此文件？")
            .setMessage("将通过系统下载服务保存，允许使用移动数据。不传递登录 Cookie；需要登录或已过期的链接可能失败。")
            .setNegativeButton("取消",null).setPositiveButton("下载",(d,w)->{enqueue(url,disposition,mime);statusForUrl(url,callback);}).show();
    }
    void statusForUrl(String raw,StatusCallback callback) {
        String url=UrlRules.normalize(raw);
        if(url==null||!url.startsWith("https://")){if(callback!=null)callback.accept("仅支持 HTTPS 下载");return;}
        if(callback==null)return;
        ArrayList<Long> matches=new ArrayList<>();
        for(String value:ids())try{long id=Long.parseLong(value);if(url.equals(prefs.getString(urlKey(id),null)))matches.add(id);}catch(NumberFormatException ignored){}
        if(matches.isEmpty()){callback.accept("尚未通过本应用下载");return;}
        long[] filter=new long[matches.size()];for(int i=0;i<filter.length;i++)filter[i]=matches.get(i);
        io.execute(()->{
            String state="下载记录已不存在";
            try(Cursor c=manager.query(new DownloadManager.Query().setFilterById(filter))){
                if(c==null)throw new IOException();
                Entry latest=null;while(c.moveToNext()){Entry candidate=new Entry(c);if(latest==null||candidate.id>latest.id)latest=candidate;}
                if(latest!=null)state=latest.summary();
            }catch(Exception e){state="暂时无法读取下载状态";}
            final String result=state;activity.runOnUiThread(()->{if(!closed&&!activity.isFinishing())callback.accept(result);});
        });
    }
    private static String urlKey(long id){return "download_url_"+id;}
    void show() {
        if(dialog!=null && dialog.isShowing())return;
        list=Ui.column(activity);Ui.pad(list,12);
        dialog=new AlertDialog.Builder(activity).setTitle("本空间下载")
            .setView(Ui.scroll(activity,list)).setPositiveButton("关闭",null).create();
        dialog.setOnDismissListener(d->{handler.removeCallbacks(refresh);dialog=null;list=null;});
        dialog.show();refresh();
    }
    private void refresh() {
        handler.removeCallbacks(refresh);
        if(closed || !resumed || dialog==null || !dialog.isShowing() || querying)return;
        List<Long> idList=new ArrayList<>();
        for(String s:ids())try{idList.add(Long.parseLong(s));}catch(NumberFormatException ignored){}
        long[] filter=new long[idList.size()];for(int i=0;i<filter.length;i++)filter[i]=idList.get(i);
        querying=true;final AlertDialog requested=dialog;
        io.execute(()->{
            ArrayList<Entry> entries=new ArrayList<>();boolean ok=true;
            try {
                if(filter.length>0)try(Cursor c=manager.query(new DownloadManager.Query().setFilterById(filter))) {
                    if(c==null)throw new IOException();
                    while(c.moveToNext())entries.add(new Entry(c));
                }
                Set<Long> found=new HashSet<>();for(Entry e:entries)found.add(e.id);
                for(long id:filter)if(!found.contains(id))entries.add(new Entry(id));
                entries.sort((a,b)->Long.compare(b.id,a.id));
            }catch(Exception e){ok=false;}
            final boolean success=ok;
            activity.runOnUiThread(()->{
                querying=false;
                if(closed || dialog!=requested || list==null){if(!closed)refresh();return;}
                render(entries,success);
                if(resumed)handler.postDelayed(refresh,2500);
            });
        });
    }
    private void render(List<Entry> entries,boolean ok) {
        list.removeAllViews();list.addView(Ui.text(activity,"保存在应用下载目录；卸载会删除。需长期保留时，完成后点“另存”。",13,Ui.MUTED));
        if(!ok){list.addView(Ui.text(activity,"无法读取系统下载状态，请稍后重试。",14,Ui.INK));return;}
        if(entries.isEmpty())list.addView(Ui.text(activity,"暂无下载记录",15,Ui.INK));
        for(Entry e:entries) {
            LinearLayout card=Ui.card(activity);card.addView(Ui.text(activity,e.title,14,Ui.INK));
            card.addView(Ui.text(activity,e.summary(),12,Ui.MUTED));LinearLayout row=Ui.row(activity);
            if(e.status==DownloadManager.STATUS_SUCCESSFUL) {
                Ui.equal(row,Ui.button(activity,"打开",()->open(e,false)));
                Ui.equal(row,Ui.button(activity,"分享",()->open(e,true)));
                Ui.equal(row,Ui.button(activity,"另存",()->save(e)));
            }
            Ui.equal(row,Ui.button(activity,e.status==0?"移除":"删除",()->remove(e)));
            card.addView(row);list.addView(card);
        }
    }
    private void open(Entry e,boolean share) {
        try {
            Uri uri=manager.getUriForDownloadedFile(e.id);if(uri==null)throw new IOException();
            String mime=manager.getMimeTypeForDownloadedFile(e.id);if(mime==null)mime="application/octet-stream";
            Intent i=share?new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM,uri)
                :new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime);
            i.setClipData(ClipData.newRawUri("download",uri));i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(i,share?"分享文件":"打开文件"));
        }catch(Exception ex){tell("文件不存在，或没有可以打开它的应用");}
    }
    private void save(Entry e) {
        try {
            pendingSave=e.id;String mime=manager.getMimeTypeForDownloadedFile(e.id);
            activity.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime==null?"application/octet-stream":mime).putExtra(Intent.EXTRA_TITLE,e.title),SAVE);
        }catch(Exception ex){pendingSave=-1;tell("无法打开保存位置选择器");}
    }
    void chooseLocation(String raw,String mime,String title) {
        String url=UrlRules.normalize(raw);
        if(url==null||!url.startsWith("https://")){tell("仅支持 HTTPS 资源");return;}
        try {
            pendingDirectUrl=url;pendingDirectMime=mime;pendingDirectName=UrlRules.safeFilename(title,"download.bin");
            activity.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime!=null&&mime.matches("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")?mime:"application/octet-stream")
                .putExtra(Intent.EXTRA_TITLE,pendingDirectName),SAVE_DIRECT);
        } catch(Exception ex){clearDirectPending();tell("无法打开保存位置选择器");}
    }
    private void clearDirectPending(){pendingDirectUrl=null;pendingDirectMime=null;pendingDirectName=null;}
    static long copyDirect(InputStream in,OutputStream out,long declaredLength,long limit)throws IOException {
        if(declaredLength>limit)throw new IOException("file too large");
        byte[] buffer=new byte[65536];long copied=0;int n;
        while((n=in.read(buffer))!=-1){if(copied>limit-n)throw new IOException("file too large");out.write(buffer,0,n);copied+=n;}
        return copied;
    }
    private void streamDirect(String url,Uri dest) {
        io.execute(()->{
            boolean ok=false;
            try {
                URL parsed=new URL(url);if(!"https".equalsIgnoreCase(parsed.getProtocol())||parsed.getUserInfo()!=null)throw new IOException("unsafe URL");
                HttpURLConnection conn=(HttpURLConnection)parsed.openConnection();directConnection=conn;
                conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(15000);conn.setReadTimeout(30000);conn.setRequestMethod("GET");
                int code=conn.getResponseCode();if(code<200||code>=300)throw new IOException("HTTP "+code);
                long length=conn.getContentLengthLong();if(length>DIRECT_MAX_BYTES)throw new IOException("too large");
                try(InputStream in=conn.getInputStream();OutputStream out=activity.getContentResolver().openOutputStream(dest,"wt")){
                    if(out==null)throw new IOException("destination unavailable");copyDirect(in,out,length,DIRECT_MAX_BYTES);
                }ok=true;
            }catch(Exception ignored){}
            finally {HttpURLConnection c=directConnection;directConnection=null;if(c!=null)c.disconnect();}
            if(!ok)try{activity.getContentResolver().delete(dest,null,null);}catch(Exception ignored){}
            final boolean success=ok;activity.runOnUiThread(()->tell(success?"已保存到所选位置":"保存失败；不支持需登录资源或重定向，已尝试清理不完整文件"));
        });
    }
    boolean onResult(int request,int result,Intent data) {
        if(request==SAVE_DIRECT){String url=pendingDirectUrl;clearDirectPending();Uri dest=data==null?null:data.getData();
            if(result!=Activity.RESULT_OK)return true;
            if(url==null||dest==null||!"content".equals(dest.getScheme())){tell("保存已中断，请重新选择");return true;}
            streamDirect(url,dest);return true;}
        if(request!=SAVE)return false;
        long id=pendingSave;pendingSave=-1;Uri dest=data==null?null:data.getData();
        if(result!=Activity.RESULT_OK)return true;
        if(id<0 || dest==null || !"content".equals(dest.getScheme())){tell("保存已中断，请重新选择");return true;}
        io.execute(()->{
            boolean ok=false;
            try {
                Uri source=manager.getUriForDownloadedFile(id);if(source==null)throw new IOException();
                try(InputStream in=activity.getContentResolver().openInputStream(source);
                    OutputStream out=activity.getContentResolver().openOutputStream(dest,"wt")) {
                    if(in==null || out==null)throw new IOException();byte[] buffer=new byte[65536];int n;
                    while((n=in.read(buffer))!=-1)out.write(buffer,0,n);ok=true;
                }
            }catch(Exception ignored){}
            final boolean success=ok;activity.runOnUiThread(()->tell(success?"文件已另存":"另存失败，可能留下不完整文件；原下载未删除"));
        });return true;
    }
    private void remove(Entry e) {
        new AlertDialog.Builder(activity).setTitle("移除下载？")
            .setMessage("会取消未完成任务并删除已下载文件及记录；操作不可撤销。需要保留的文件请先另存。")
            .setNegativeButton("取消",null).setPositiveButton("移除",(d,w)->{
                io.execute(()->{
                    boolean ok=true;try{manager.remove(e.id);}catch(Exception ex){ok=false;}
                    final boolean success=ok;
                    activity.runOnUiThread(()->{
                        if(success){Set<String> ids=ids();ids.remove(Long.toString(e.id));prefs.edit().putStringSet("downloads",ids).remove(urlKey(e.id)).apply();}
                        else tell("移除失败，记录已保留");
                        refresh();
                    });
                });
            }).show();
    }
    void resume(){resumed=true;refresh();}
    void pause(){resumed=false;handler.removeCallbacks(refresh);}
    void close(){closed=true;handler.removeCallbacksAndMessages(null);if(dialog!=null)dialog.dismiss();HttpURLConnection c=directConnection;if(c!=null)c.disconnect();io.shutdown();}
    private static final class Entry {
        final long id,done,total;final int status,reason;final String title;
        Entry(Cursor c){id=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
            title=UrlRules.safeFilename(c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)),"download");
            done=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            total=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            status=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            reason=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));}
        Entry(long n){id=n;title="记录 "+n;done=0;total=-1;status=0;reason=0;}
        String summary(){
            String state=statusLabel(status,reason);
            return state+" · "+String.format(Locale.ROOT,"%.1f MiB",done/1048576.0)+(total>0?" / "+String.format(Locale.ROOT,"%.1f MiB",total/1048576.0):"");
        }
    }
    static String statusLabel(int status,int reason){
        switch(status){case DownloadManager.STATUS_SUCCESSFUL:return "已完成";
            case DownloadManager.STATUS_RUNNING:return "下载中";
            case DownloadManager.STATUS_PENDING:return "等待下载";
            case DownloadManager.STATUS_PAUSED:return "暂停 / 等待网络（"+reason+"）";
            case DownloadManager.STATUS_FAILED:return "失败（"+reason+"），请返回网页获取新链接";
            default:return "系统记录已不存在";}
    }
}

