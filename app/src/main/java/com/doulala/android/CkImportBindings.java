package com.doulala.android;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Local import-attempt guard, NOT an authenticated account/device binding.
 * Stores keyed digests only, in app-private no-backup storage. A durable
 * reservation precedes any Cookie write so concurrent spaces cannot both win.
 * An uncertain/partially failed attempt stays reserved until an explicit reset.
 */
final class CkImportBindings {
    static final int MAX_PER_SPACE=16;
    private static final int MAX_FILE_BYTES=2*1024;
    private final Path root;
    CkImportBindings(Path root){this.root=root;}

    static final class Decision {
        final boolean allowed;
        final int otherSlot;
        Decision(boolean allowed,int otherSlot){this.allowed=allowed;this.otherSlot=otherSlot;}
    }

    Decision reserve(int slot,List<String> pairs)throws IOException {
        checkSlot(slot);
        // Validate and canonicalize before any registry write; values never leave this call.
        String canonical=canonical(pairs);
        return locked(()->{
            SortedMap<Integer,SortedSet<String>> records=read();
            String fingerprint=digest(key(!records.isEmpty()),canonical);
            for(Map.Entry<Integer,SortedSet<String>> row:records.entrySet())
                if(row.getKey()!=slot&&row.getValue().contains(fingerprint))return new Decision(false,row.getKey());
            SortedSet<String> own=records.computeIfAbsent(slot,k->new TreeSet<>());
            if(own.contains(fingerprint))return new Decision(true,-1);
            if(own.size()>=MAX_PER_SPACE)throw new IOException("import history capacity reached");
            own.add(fingerprint);writeSlot(slot,own);
            return new Decision(true,-1);
        });
    }

    /** Called only inside the selected profile's exclusive reset gate. */
    void release(int slot)throws IOException {
        checkSlot(slot);
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return;
        // Do not parse a global index here: a damaged auxiliary record must not
        // permanently lock a cleared profile. Only this slot's file is deleted.
        locked(()->{Files.deleteIfExists(slotFile(slot));return null;});
    }

    private interface Work<T>{T run()throws IOException;}
    private <T>T locked(Work<T> work)throws IOException {
        Files.createDirectories(root);
        if(Files.isSymbolicLink(root))throw new IOException("linked registry refused");
        Path lockFile=root.resolve("registry.lock");
        try(FileChannel channel=FileChannel.open(lockFile,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            long end=System.nanoTime()+2_000_000_000L;
            while(true){
                FileLock lock=null;
                try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){/* another thread */}
                if(lock!=null){try(FileLock held=lock){return work.run();}}
                if(System.nanoTime()>=end)throw new IOException("registry busy");
                try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("interrupted");}
            }
        }
    }

    static String canonical(List<String> pairs)throws IOException {
        if(pairs==null||pairs.isEmpty()||pairs.size()>CookieRules.MAX_COOKIES)throw new IOException("invalid cookies");
        try{
            List<String> validated=CookieRules.parse(String.join("; ",pairs));
            ArrayList<String> sorted=new ArrayList<>(validated);Collections.sort(sorted);
            return String.join("\n",sorted);
        }catch(IllegalArgumentException e){throw new IOException("invalid cookies");}
    }
    private byte[] key(boolean required)throws IOException {
        Path path=root.resolve("key.bin");
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)){
            if(Files.isSymbolicLink(path)||Files.size(path)!=32)throw new IOException("invalid registry key");
            return Files.readAllBytes(path);
        }
        if(required)throw new IOException("missing registry key");
        byte[] key=new byte[32];new SecureRandom().nextBytes(key);atomic(path,key);return key;
    }
    private static String digest(byte[] key,String canonical)throws IOException {
        try{
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            byte[] bytes=mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex=new StringBuilder();for(byte b:bytes)hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();
        }catch(java.security.GeneralSecurityException e){throw new IOException("digest unavailable");}
        finally{Arrays.fill(key,(byte)0);}
    }
    private Path slotFile(int slot){return root.resolve("space"+slot+".v1");}
    private SortedMap<Integer,SortedSet<String>> read()throws IOException {
        SortedMap<Integer,SortedSet<String>> records=new TreeMap<>();Set<String> unique=new HashSet<>();
        for(int slot=0;slot<64;slot++){
            Path path=slotFile(slot);
            if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))continue;
            if(Files.isSymbolicLink(path)||Files.size(path)>MAX_FILE_BYTES)throw new IOException("invalid space record");
            List<String> lines=Files.readAllLines(path,StandardCharsets.US_ASCII);
            if(lines.size()<2||!"CK_IMPORT_ATTEMPTS_V1".equals(lines.get(0)))throw new IOException("invalid space record");
            SortedSet<String> own=new TreeSet<>();
            for(int i=1;i<lines.size();i++){
                String fingerprint=lines.get(i);
                if(!fingerprint.matches("[0-9a-f]{64}")||!unique.add(fingerprint)||!own.add(fingerprint)||own.size()>MAX_PER_SPACE)
                    throw new IOException("invalid space record");
            }
            records.put(slot,own);
        }
        return records;
    }
    private void writeSlot(int slot,SortedSet<String> fingerprints)throws IOException {
        StringBuilder text=new StringBuilder("CK_IMPORT_ATTEMPTS_V1\n");
        for(String fingerprint:fingerprints)text.append(fingerprint).append('\n');
        atomic(slotFile(slot),text.toString().getBytes(StandardCharsets.US_ASCII));
    }
    private static void atomic(Path target,byte[] bytes)throws IOException {
        Path temp=target.resolveSibling(target.getFileName()+".tmp");
        if(Files.isSymbolicLink(temp)||Files.isSymbolicLink(target))throw new IOException("linked registry refused");
        try(FileChannel output=FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())output.write(buffer);output.force(true);
        }
        // Fail closed if atomic replacement is not available. Never drop old bindings.
        Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    private static void checkSlot(int slot)throws IOException{if(slot<0||slot>=64)throw new IOException("invalid space");}
}
