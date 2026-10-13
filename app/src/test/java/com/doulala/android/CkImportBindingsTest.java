package com.doulala.android;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class CkImportBindingsTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private Path root;
    private CkImportBindings guard;
    @Before public void setup()throws Exception{root=temp.getRoot().toPath().resolve("bindings");guard=new CkImportBindings(root);}
    private List<String> ck(String s){return CookieRules.parse("sessionid="+s+"; csrf=fixture");}
    @Test public void sameSpaceReopenAndCookieOrderKeepReservation()throws Exception{
        assertTrue(guard.reserve(0,ck("one")).allowed);
        assertTrue(new CkImportBindings(root).reserve(0,Arrays.asList("csrf=fixture","sessionid=one")).allowed);
        CkImportBindings.Decision blocked=guard.reserve(63,ck("one"));assertFalse(blocked.allowed);assertEquals(0,blocked.otherSlot);
    }
    @Test public void differentSnapshotsAllowedButOldAttemptCannotMove()throws Exception{
        assertTrue(guard.reserve(1,ck("one")).allowed);assertTrue(guard.reserve(1,ck("refreshed")).allowed);
        assertFalse(guard.reserve(2,ck("one")).allowed);assertTrue(guard.reserve(2,ck("another")).allowed);
    }
    @Test public void resetReleasesOnlySelectedSpace()throws Exception{
        guard.reserve(0,ck("one"));guard.reserve(63,ck("two"));guard.release(0);
        assertTrue(guard.reserve(1,ck("one")).allowed);assertFalse(guard.reserve(1,ck("two")).allowed);
    }
    @Test public void registryNeverContainsPlaintextAndKeyIsInstallationLocal()throws Exception{
        guard.reserve(0,ck("unique-fixture-secret"));String text=new String(Files.readAllBytes(root.resolve("space0.v1")),java.nio.charset.StandardCharsets.US_ASCII);
        assertFalse(text.contains("sessionid"));assertFalse(text.contains("unique-fixture-secret"));assertFalse(text.contains("csrf"));
        Path second=temp.getRoot().toPath().resolve("other");new CkImportBindings(second).reserve(0,ck("unique-fixture-secret"));
        assertNotEquals(text,new String(Files.readAllBytes(second.resolve("space0.v1")),java.nio.charset.StandardCharsets.US_ASCII));assertEquals(32,Files.size(root.resolve("key.bin")));
    }
    @Test public void concurrentSpacesCannotBothReserveSameSnapshot()throws Exception{
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{
            Future<Boolean> a=pool.submit(()->{start.await();return guard.reserve(0,ck("race")).allowed;});
            Future<Boolean> b=pool.submit(()->{start.await();return new CkImportBindings(root).reserve(1,ck("race")).allowed;});start.countDown();
            assertNotEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();}
    }
    @Test public void corruptRegistryAndMissingKeyFailClosed()throws Exception{
        guard.reserve(0,ck("one"));Files.delete(root.resolve("key.bin"));
        try{guard.reserve(1,ck("one"));fail();}catch(IOException expected){}
        Files.write(root.resolve("space0.v1"),"garbage\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        try{guard.reserve(1,ck("one"));fail();}catch(IOException expected){}
    }
    @Test public void boundedHistoryNeverEvictsOldReservations()throws Exception{
        for(int n=0;n<CkImportBindings.MAX_PER_SPACE;n++)assertTrue(guard.reserve(0,ck("fixture"+n)).allowed);
        try{guard.reserve(0,ck("overflow"));fail();}catch(IOException expected){}
        assertTrue(guard.reserve(0,ck("fixture0")).allowed);assertFalse(guard.reserve(1,ck("fixture0")).allowed);
    }
    @Test public void invalidCookiesAndSlotsDoNotBind()throws Exception{
        for(int slot:new int[]{-1,64})try{guard.reserve(slot,ck("one"));fail();}catch(IOException expected){}
        try{guard.reserve(0,Arrays.asList("sessionid=x","sessionid=y"));fail();}catch(IOException expected){}
        assertFalse(Files.exists(root.resolve("space0.v1")));
    }
    @Test public void resettingCorruptSlotRecoversWithoutDeletingNeighbor()throws Exception{
        guard.reserve(0,ck("one"));guard.reserve(63,ck("neighbor"));
        Files.write(root.resolve("space0.v1"),"damaged".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        try{guard.reserve(1,ck("one"));fail();}catch(IOException expected){}
        guard.release(0);assertTrue(guard.reserve(1,ck("one")).allowed);
        assertFalse(guard.reserve(1,ck("neighbor")).allowed);assertTrue(Files.exists(root.resolve("space63.v1")));
    }
}
