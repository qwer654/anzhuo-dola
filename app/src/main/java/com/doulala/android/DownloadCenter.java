package com.doulala.android;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.webkit.URLUtil;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Tracks only explicit downloads from this profile. Does not attach cookies or auth headers. */
final class DownloadCenter {
    private static final int SAVE=8220, MAX_DOWNLOADS=100;
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
            if(!prefs.edit().putStringSet("downloads",ids).commit()){manager.remove(id);throw new IOException();}
            tell("已加入下载，可在菜单 → 下载中心查看。使用移动数据时会消耗流量。");refresh();
        }catch(Exception e){tell("下载未加入，请检查系统下载服务、存储空间和网络");}
    }
    void confirm(String url,String disposition,String mime) {
        new AlertDialog.Builder(activity).setTitle("下载此文件？")
            .setMessage("将通过系统下载服务保存，允许使用移动数据。不传递登录 Cookie；需要登录或已过期的链接可能失败。")
            .setNegativeButton("取消",null).setPositiveButton("下载",(d,w)->enqueue(url,disposition,mime)).show();
    }
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
    boolean onResult(int request,int result,Intent data) {
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
                        if(success){Set<String> ids=ids();ids.remove(Long.toString(e.id));prefs.edit().putStringSet("downloads",ids).apply();}
                        else tell("移除失败，记录已保留");
                        refresh();
                    });
                });
            }).show();
    }
    void resume(){resumed=true;refresh();}
    void pause(){resumed=false;handler.removeCallbacks(refresh);}
    void close(){closed=true;handler.removeCallbacksAndMessages(null);if(dialog!=null)dialog.dismiss();io.shutdown();}
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
            String state;
            switch(status){case DownloadManager.STATUS_SUCCESSFUL:state="已完成";break;
                case DownloadManager.STATUS_RUNNING:state="下载中";break;
                case DownloadManager.STATUS_PENDING:state="等待下载";break;
                case DownloadManager.STATUS_PAUSED:state="暂停 / 等待网络（"+reason+"）";break;
                case DownloadManager.STATUS_FAILED:state="失败（"+reason+"），请返回网页获取新链接";break;
                default:state="系统记录已不存在";}
            return state+" · "+String.format(Locale.ROOT,"%.1f MiB",done/1048576.0)+(total>0?" / "+String.format(Locale.ROOT,"%.1f MiB",total/1048576.0):"");
        }
    }
}
