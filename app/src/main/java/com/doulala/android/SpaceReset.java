package com.doulala.android;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Process;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Destructive operations for one WebView profile.  Markers are deliberately in
 * cross-process marker files so a crashed reset remains closed until it is retried. */
public final class SpaceReset {
    public static final String EXTRA_RESET_SLOT="com.doulala.android.resetSlot";
    public static final String EXTRA_RESET_OPERATION="com.doulala.android.resetOperation";
    public static final String OP_CLEAR="clear", OP_DELETE="delete";
    private static final String CATALOG="catalog", PENDING="resetPending", OP="resetOperation";
    private SpaceReset(){}
    static boolean valid(int slot){return slot>=0&&slot<SpaceSlots.COUNT;}
    static boolean isOperation(String op){return OP_CLEAR.equals(op)||OP_DELETE.equals(op);}
    public static boolean isPending(Context c,int slot){return valid(slot)&&(Files.exists(marker(c,slot),LinkOption.NOFOLLOW_LINKS)||c.getSharedPreferences(CATALOG,Context.MODE_PRIVATE).getBoolean(PENDING+slot,false));}
    static String pendingOperation(Context c,int slot){try{Path p=marker(c,slot);if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)){if(Files.size(p)>16)return "invalid";String op=new String(Files.readAllBytes(p),StandardCharsets.UTF_8);return isOperation(op)?op:"invalid";}}catch(IOException e){return "invalid";}return c.getSharedPreferences(CATALOG,Context.MODE_PRIVATE).getString(OP+slot,OP_CLEAR);}

    /** Runs off the UI thread. False may mean a partial cleanup; admission remains blocked until retry. */
    static boolean performBlocking(Context c,int slot,String op) {
        if(!valid(slot)||!isOperation(op)) return false;
        Context app=c.getApplicationContext(); SharedPreferences catalog=app.getSharedPreferences(CATALOG,Context.MODE_PRIVATE);
        // Close the admission gate before asking the browser process to die.
        boolean archived=catalog.getBoolean("archived"+slot,false);
        if(!writeMarker(app,slot,op)||!catalog.edit().putBoolean(PENDING+slot,true).putString(OP+slot,op).putBoolean("resetArchived"+slot,archived).putBoolean("enabled"+slot,false).commit()) return false;
        if(!stopProfile(app,slot)) return false; // marker intentionally survives
        try (FileChannel gate=FileChannel.open(gate(app,slot),StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE);
             FileLock exclusive=tryExclusive(gate)) {
            if(exclusive==null) return false;
            // A main-process native video runner owns this lock for its whole job.
            Path video=app.getFilesDir().toPath().resolve("video-Space"+slot);
            if(!Files.exists(video)) Files.createDirectories(video);
            try(FileChannel runner=FileChannel.open(video.resolve("runner.lock"),StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE);FileLock runnerLock=tryExclusive(runner)){
             if(runnerLock==null) return false;
            deleteProfileDirectories(app,slot);
            // This also removes browser_SpaceN.xml; do not retain a cached instance in Main.
            File browserPrefs=new File(app.getDataDir(),"shared_prefs/browser_Space"+slot+".xml");
            if(browserPrefs.exists()&&!app.deleteSharedPreferences("browser_Space"+slot)) return false;
            // Reset only this slot's local import-attempt digests, after profile cleanup.
            // Failure keeps the reset gate closed so it can be retried safely.
            new CkImportBindings(app.getNoBackupFilesDir().toPath().resolve("ck-import-bindings")).release(slot);
            SharedPreferences.Editor e=catalog.edit().remove(PENDING+slot).remove(OP+slot);
            if(OP_DELETE.equals(op)) {
                e.remove("enabled"+slot).remove("name"+slot).remove("url"+slot)
                 .remove("archived"+slot).remove("archivedName"+slot).remove("archivedUrl"+slot)
                 .remove("retired"+slot).remove("resetArchived"+slot).putBoolean("deleted"+slot,true).putBoolean("catalogInitialized",true);
            } else {
                boolean restoreArchived=catalog.getBoolean("resetArchived"+slot,false);
                e.remove("resetArchived"+slot).putBoolean("enabled"+slot,!restoreArchived); // name/url are deliberately retained
            }
            if(!e.commit()) return false;
            Files.deleteIfExists(marker(app,slot));
            return true;
            }
        } catch (IOException securityOrIo) { return false; }
    }

    private static boolean stopProfile(Context c,int slot){
        ActivityManager am=(ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);
        if(am==null) return false;
        String wanted=c.getPackageName()+":"+SpaceSlots.suffix(slot);
        // Remove only this profile's task; task affinity is not used as a broad selector.
        for(ActivityManager.AppTask task:am.getAppTasks()) {
            ActivityManager.RecentTaskInfo info=task.getTaskInfo(); Intent base=info==null?null:info.baseIntent;
            ComponentName component=base==null?null:base.getComponent();
            if(component!=null&&SpaceSlots.className(slot).equals(component.getClassName())) task.finishAndRemoveTask();
        }
        List<ActivityManager.RunningAppProcessInfo> processes=am.getRunningAppProcesses();
        if(processes!=null) for(ActivityManager.RunningAppProcessInfo p:processes)
            if(wanted.equals(p.processName)&&p.uid==Process.myUid()) Process.killProcess(p.pid);
        long end=android.os.SystemClock.uptimeMillis()+2500;
        while(android.os.SystemClock.uptimeMillis()<end){
            if(!profileAlive(am,wanted)) return true;
            android.os.SystemClock.sleep(50);
        }
        return !profileAlive(am,wanted);
    }
    private static boolean profileAlive(ActivityManager am,String name){
        List<ActivityManager.RunningAppProcessInfo> ps=am.getRunningAppProcesses(); if(ps==null)return false;
        for(ActivityManager.RunningAppProcessInfo p:ps) if(name.equals(p.processName)&&p.uid==Process.myUid()) return true;
        return false;
    }
    private static void deleteProfileDirectories(Context c,int slot)throws IOException{
        String suffix=SpaceSlots.suffix(slot);
        deleteExact(c.getDataDir().toPath().resolve("app_webview_"+suffix));
        deleteExact(c.getCacheDir().toPath().resolve("webview_"+suffix));
        // Native-video jobs are per-space only; public Downloads are intentionally untouched.
        deleteExact(c.getFilesDir().toPath().resolve("video-Space"+slot));
    }
    private static void deleteExact(Path target)throws IOException{
        Path normalized=target.toAbsolutePath().normalize(); Path parent=normalized.getParent();
        if(parent==null||!normalized.getFileName().toString().matches("(?:app_webview|webview)_space(?:0|[1-9][0-9]?)|video-Space(?:0|[1-9][0-9]?)")) throw new IOException("unsafe reset path");
        if(!Files.exists(normalized,LinkOption.NOFOLLOW_LINKS)) return;
        if(Files.isSymbolicLink(normalized)) throw new IOException("refusing linked profile path");
        Files.walkFileTree(normalized,new SimpleFileVisitor<Path>(){
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException{
                if(attrs.isSymbolicLink()) throw new IOException("refusing linked profile entry"); Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir,IOException exc)throws IOException{
                if(exc!=null)throw exc; if(Files.isSymbolicLink(dir))throw new IOException("refusing linked profile entry"); Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }
    static BrowserGate acquireBrowserGate(Context c,int slot)throws IOException{
        if(!valid(slot))throw new IOException("invalid space");
        Files.createDirectories(resetDir(c));
        FileChannel channel=FileChannel.open(gate(c,slot),StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE);
        try { FileLock lock=channel.tryLock(0L,Long.MAX_VALUE,true); if(lock==null||Files.exists(marker(c,slot),LinkOption.NOFOLLOW_LINKS)){if(lock!=null)lock.release();channel.close();throw new IOException("space reset pending");}return new BrowserGate(channel,lock); }catch(Exception e){try{channel.close();}catch(IOException ignored){}if(e instanceof IOException)throw(IOException)e;throw new IOException("space gate unavailable",e);}
    }
    static final class BrowserGate implements AutoCloseable {final FileChannel channel;final FileLock lock;BrowserGate(FileChannel c,FileLock l){channel=c;lock=l;}public void close()throws IOException{lock.release();channel.close();}}
    private static FileLock tryExclusive(FileChannel channel)throws IOException{try{return channel.tryLock();}catch(java.nio.channels.OverlappingFileLockException busy){return null;}}
    private static Path resetDir(Context c){return c.getFilesDir().toPath().resolve("space_reset");}
    private static Path marker(Context c,int slot){return resetDir(c).resolve("space"+slot+".pending");}
    private static Path gate(Context c,int slot){return resetDir(c).resolve("space"+slot+".lock");}
    private static boolean writeMarker(Context c,int slot,String op){try{Files.createDirectories(resetDir(c));Path temp=resetDir(c).resolve("space"+slot+".tmp");Files.write(temp,op.getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE);try{Files.move(temp,marker(c,slot),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(java.nio.file.AtomicMoveNotSupportedException noAtomic){Files.move(temp,marker(c,slot),StandardCopyOption.REPLACE_EXISTING);}return true;}catch(IOException e){return false;}}
}
