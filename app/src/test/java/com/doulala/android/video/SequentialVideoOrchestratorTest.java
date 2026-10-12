package com.doulala.android.video;

import org.junit.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class SequentialVideoOrchestratorTest {
  static class Fake implements VideoProvider, LastFrameExtractor {
    final List<String> events=new ArrayList<>(); final Map<String,Deque<PollResult>> answers=new HashMap<>(); final List<Segment> submitted=new ArrayList<>(); int n;
    public String submit(Segment s,String key){ events.add("submit:"+s.index()); submitted.add(s); return "job-"+n++; }
    public PollResult poll(String id){events.add("poll:"+id); return answers.get(id).remove();}
    public void download(String u,Path p)throws Exception{events.add("download:"+u);Files.writeString(p,"video");}
    public String uploadReference(Path p){events.add("upload:"+p.getFileName());return "tail-"+submitted.size();}
    public void extractLastFrame(Path v,Path j)throws Exception{events.add("extract:"+v.getFileName());Files.writeString(j,"jpg");}
  }
  static class MemoryStore implements SequenceCheckpointStore { Snapshot value; int saves; public Optional<Snapshot> load(String id){return Optional.ofNullable(value);} public void saveAtomically(Snapshot s){value=s;saves++;} }
  static List<VideoProvider.Segment> inputs(){return List.of(new VideoProvider.Segment(0,"model-a","first",List.of("orig-a"),Map.of("ratio","16:9")),new VideoProvider.Segment(1,"model-b","next",List.of("orig-b","orig-c"),Map.of("seed","9")));}
  static void success(Fake f){f.answers.put("job-0",new ArrayDeque<>(List.of(new VideoProvider.PollResult(VideoProvider.Status.RUNNING,null,null),new VideoProvider.PollResult(VideoProvider.Status.SUCCEEDED,"u0",null))));f.answers.put("job-1",new ArrayDeque<>(List.of(new VideoProvider.PollResult(VideoProvider.Status.SUCCEEDED,"u1",null))));}
  @Test public void strictSerialOrderAndPreservesOriginalData() throws Exception {Fake f=new Fake();success(f); MemoryStore store=new MemoryStore(); var core=new SequentialVideoOrchestrator(f,f,store); Path d=Files.createTempDirectory("seq"); core.run("run",inputs(),new SequentialVideoOrchestrator.Options(3,d,()->false));
    assertEquals(List.of("submit:0","poll:job-0","poll:job-0","download:u0","extract:segment-000.mp4","upload:segment-000-last-frame.jpg","submit:1","poll:job-1","download:u1"),f.events);
    var second=f.submitted.get(1); assertEquals("model-b",second.model());assertEquals("next",second.prompt());assertEquals(Map.of("seed","9"),second.originalParams());assertEquals(List.of("tail-1","orig-b","orig-c"),second.originalRefs());assertEquals(SequenceCheckpointStore.Stage.COMPLETE,store.value.entries().get(1).stage());
  }
  @Test public void failedStopsWithoutNextSubmit() throws Exception {Fake f=new Fake(); f.answers.put("job-0",new ArrayDeque<>(List.of(new VideoProvider.PollResult(VideoProvider.Status.FAILED,null,"bad")))); MemoryStore s=new MemoryStore(); try {new SequentialVideoOrchestrator(f,f,s).run("x",inputs(),new SequentialVideoOrchestrator.Options(2,Files.createTempDirectory("seq"),()->false));fail();}catch(SequentialVideoOrchestrator.SequenceException expected){} assertEquals(List.of("submit:0","poll:job-0"),f.events);assertEquals(SequenceCheckpointStore.Stage.FAILED,s.value.entries().get(0).stage()); }
  @Test public void timeoutStopsWithoutNextSubmit() throws Exception {Fake f=new Fake(); f.answers.put("job-0",new ArrayDeque<>(List.of(new VideoProvider.PollResult(VideoProvider.Status.RUNNING,null,null)))); MemoryStore s=new MemoryStore(); try {new SequentialVideoOrchestrator(f,f,s).run("x",inputs(),new SequentialVideoOrchestrator.Options(1,Files.createTempDirectory("seq"),()->false));fail();}catch(SequentialVideoOrchestrator.SequenceException expected){} assertEquals(List.of("submit:0","poll:job-0"),f.events);assertEquals(SequenceCheckpointStore.Stage.TIMEOUT,s.value.entries().get(0).stage()); }
  @Test public void cancellationBeforeNextPollStops() throws Exception {Fake f=new Fake();f.answers.put("job-0",new ArrayDeque<>(List.of(new VideoProvider.PollResult(VideoProvider.Status.RUNNING,null,null)))); MemoryStore s=new MemoryStore(); final int[] calls={0}; try {new SequentialVideoOrchestrator(f,f,s).run("x",inputs(),new SequentialVideoOrchestrator.Options(2,Files.createTempDirectory("seq"),()->++calls[0]>1));fail();}catch(SequentialVideoOrchestrator.SequenceException expected){} assertEquals(List.of("submit:0"),f.events); }
  @Test public void resumeKnownJobNeverResubmits() throws Exception {Fake f=new Fake(); MemoryStore s=new MemoryStore(); String fp=SequentialVideoOrchestrator.fingerprint(inputs());s.value=new SequenceCheckpointStore.Snapshot("resume",fp,Map.of(0,new SequenceCheckpointStore.Entry("kept-job",SequenceCheckpointStore.Stage.SUBMITTED,null,null,null)));f.answers.put("kept-job",new ArrayDeque<>(List.of(new VideoProvider.PollResult(VideoProvider.Status.SUCCEEDED,"u0",null))));f.answers.put("job-0",new ArrayDeque<>(List.of(new VideoProvider.PollResult(VideoProvider.Status.SUCCEEDED,"u1",null)))); new SequentialVideoOrchestrator(f,f,s).run("resume",inputs(),new SequentialVideoOrchestrator.Options(2,Files.createTempDirectory("seq"),()->false));assertEquals(List.of("poll:kept-job","download:u0","extract:segment-000.mp4","upload:segment-000-last-frame.jpg","submit:1","poll:job-0","download:u1"),f.events); }
  @Test public void rejectsChangedInputForResume() throws Exception {Fake f=new Fake();MemoryStore s=new MemoryStore();s.value=new SequenceCheckpointStore.Snapshot("x","wrong",Map.of());try{new SequentialVideoOrchestrator(f,f,s).run("x",inputs(),new SequentialVideoOrchestrator.Options(1,Files.createTempDirectory("seq"),()->false));fail();}catch(SequentialVideoOrchestrator.SequenceException expected){}assertTrue(f.events.isEmpty());}
  @Test public void fingerprintSeparatesAmbiguousListsAndMaps() {
    VideoProvider.Segment a=new VideoProvider.Segment(0,"m","p",List.of("ab","c"),Map.of("k","v"));
    VideoProvider.Segment b=new VideoProvider.Segment(0,"m","p",List.of("a","bc"),Map.of("k","v"));
    assertNotEquals(SequentialVideoOrchestrator.fingerprint(List.of(a)),SequentialVideoOrchestrator.fingerprint(List.of(b)));
  }
  @Test public void cancelledAfterDownloadDoesNotExtractOrUpload() throws Exception {
    Fake f=new Fake(); f.answers.put("job-0",new ArrayDeque<>(List.of(new VideoProvider.PollResult(VideoProvider.Status.SUCCEEDED,"u0",null))));
    MemoryStore s=new MemoryStore();
    try { new SequentialVideoOrchestrator(f,f,s).run("x",inputs(),new SequentialVideoOrchestrator.Options(1,Files.createTempDirectory("seq"),()->f.events.contains("download:u0"))); fail(); }
    catch(SequentialVideoOrchestrator.SequenceException expected) {}
    assertEquals(List.of("submit:0","poll:job-0","download:u0"),f.events);
  }
  @Test public void timeoutCanResumeSameKnownJob() throws Exception {
    Fake f=new Fake(); MemoryStore s=new MemoryStore(); Path d=Files.createTempDirectory("seq");
    f.answers.put("job-0",new ArrayDeque<>(List.of(new VideoProvider.PollResult(VideoProvider.Status.RUNNING,null,null))));
    try { new SequentialVideoOrchestrator(f,f,s).run("x",inputs(),new SequentialVideoOrchestrator.Options(1,d,()->false)); fail(); }
    catch(SequentialVideoOrchestrator.SequenceException expected) {}
    assertEquals(SequenceCheckpointStore.Stage.TIMEOUT,s.value.entries().get(0).stage());
    f.answers.put("job-0",new ArrayDeque<>(List.of(new VideoProvider.PollResult(VideoProvider.Status.SUCCEEDED,"u0",null))));
    f.answers.put("job-1",new ArrayDeque<>(List.of(new VideoProvider.PollResult(VideoProvider.Status.SUCCEEDED,"u1",null))));
    new SequentialVideoOrchestrator(f,f,s).run("x",inputs(),new SequentialVideoOrchestrator.Options(1,d,()->false));
    assertEquals("poll:job-0",f.events.get(2)); assertEquals(1,Collections.frequency(f.events,"submit:0"));
  }
  @Test public void invalidCheckpointStageIsRejected() throws Exception {
    Fake f=new Fake(); MemoryStore s=new MemoryStore(); String fp=SequentialVideoOrchestrator.fingerprint(inputs());
    s.value=new SequenceCheckpointStore.Snapshot("x",fp,Map.of(0,new SequenceCheckpointStore.Entry("j",SequenceCheckpointStore.Stage.DOWNLOADED,"u","D:\\bad\\missing.mp4",null)));
    try { new SequentialVideoOrchestrator(f,f,s).run("x",inputs(),new SequentialVideoOrchestrator.Options(1,Files.createTempDirectory("seq"),()->false)); fail(); }
    catch(SequentialVideoOrchestrator.SequenceException expected) {}
  }
}
