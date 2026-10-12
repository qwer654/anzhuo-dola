package com.doulala.android.video;

import java.nio.file.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Synchronous, deliberately serial workflow. Segment indices are 0-based. */
public final class SequentialVideoOrchestrator {
    public record Options(int maxPolls, Path workDirectory, Cancellation cancellation) {
        public Options { if (maxPolls < 1 || workDirectory == null) throw new IllegalArgumentException("options"); cancellation = cancellation == null ? () -> false : cancellation; }
    }
    @FunctionalInterface public interface Cancellation { boolean cancelled(); }
    public static final class SequenceException extends Exception { public SequenceException(String message) { super(message); } }
    private final VideoProvider provider; private final LastFrameExtractor extractor; private final SequenceCheckpointStore store;
    public SequentialVideoOrchestrator(VideoProvider provider, LastFrameExtractor extractor, SequenceCheckpointStore store) {
        this.provider=Objects.requireNonNull(provider); this.extractor=Objects.requireNonNull(extractor); this.store=Objects.requireNonNull(store);
    }
    public SequenceCheckpointStore.Snapshot run(String sequenceId, List<VideoProvider.Segment> originals, Options options) throws Exception {
        if (sequenceId == null || sequenceId.trim().isEmpty() || originals == null || originals.isEmpty()) throw new IllegalArgumentException("sequence");
        List<VideoProvider.Segment> source=immutableList(originals); String fingerprint=fingerprint(source);
        for (int i=0;i<source.size();i++) if (source.get(i).index()!=i) throw new IllegalArgumentException("segment indices must be contiguous");
        SequenceCheckpointStore.Snapshot loaded=store.load(sequenceId).orElse(new SequenceCheckpointStore.Snapshot(sequenceId,fingerprint,Collections.<Integer,SequenceCheckpointStore.Entry>emptyMap()));
        validateCheckpoint(loaded, sequenceId, fingerprint, source.size(), options.workDirectory());
        if (!fingerprint.equals(loaded.inputFingerprint())) throw new SequenceException("checkpoint input fingerprint differs; refusing to mix sequences");
        Map<Integer,SequenceCheckpointStore.Entry> entries=new TreeMap<>(loaded.entries()); Files.createDirectories(options.workDirectory());
        String previousRef=null;
        for(int i=0;i<source.size();i++) {
            checkCancelled(options, entries, loaded, sequenceId, fingerprint); SequenceCheckpointStore.Entry existing=entries.get(i);
            // A continuity reference is only usable after predecessor completion.
            if(i>0) { SequenceCheckpointStore.Entry prior=entries.get(i-1); if(prior==null || prior.stage()!=SequenceCheckpointStore.Stage.COMPLETE) throw new SequenceException("predecessor "+(i-1)+" is not complete"); previousRef=prior.continuityRef(); }
            VideoProvider.Segment segment=i==0 ? source.get(i) : source.get(i).withContinuityRef(previousRef);
            if(existing==null) { String job=provider.submit(segment,key(sequenceId,i,fingerprint)); existing=new SequenceCheckpointStore.Entry(job,SequenceCheckpointStore.Stage.SUBMITTED,null,null,null); entries.put(i,existing); loaded=save(sequenceId,fingerprint,entries); }
            if(existing.stage()==SequenceCheckpointStore.Stage.COMPLETE) continue;
            if(existing.stage()==SequenceCheckpointStore.Stage.FAILED || existing.stage()==SequenceCheckpointStore.Stage.CANCELLED) throw new SequenceException("segment "+i+" previously stopped at "+existing.stage());
            if(existing.stage()==SequenceCheckpointStore.Stage.TIMEOUT) { existing=new SequenceCheckpointStore.Entry(existing.jobId(),SequenceCheckpointStore.Stage.SUBMITTED,null,null,null); entries.put(i,existing); loaded=save(sequenceId,fingerprint,entries); }
            String url=existing.resultUrl();
            if(existing.stage()==SequenceCheckpointStore.Stage.SUBMITTED) {
                for(int polls=0; polls<options.maxPolls(); polls++) { checkCancelled(options,entries,loaded,sequenceId,fingerprint); VideoProvider.PollResult result=provider.poll(existing.jobId());
                    if(result.status()==VideoProvider.Status.SUCCEEDED) { if(result.resultUrl()==null||result.resultUrl().trim().isEmpty()) throw new SequenceException("segment "+i+" succeeded without result URL"); url=result.resultUrl(); existing=new SequenceCheckpointStore.Entry(existing.jobId(),SequenceCheckpointStore.Stage.SUCCEEDED,url,null,null); entries.put(i,existing); loaded=save(sequenceId,fingerprint,entries); break; }
                    if(result.status()==VideoProvider.Status.FAILED || result.status()==VideoProvider.Status.CANCELLED) { SequenceCheckpointStore.Stage s=result.status()==VideoProvider.Status.FAILED?SequenceCheckpointStore.Stage.FAILED:SequenceCheckpointStore.Stage.CANCELLED; entries.put(i,new SequenceCheckpointStore.Entry(existing.jobId(),s,null,null,null)); save(sequenceId,fingerprint,entries); throw new SequenceException("segment "+i+" "+s+": "+result.detail()); }
                }
                existing=entries.get(i); if(existing.stage()==SequenceCheckpointStore.Stage.SUBMITTED) { entries.put(i,new SequenceCheckpointStore.Entry(existing.jobId(),SequenceCheckpointStore.Stage.TIMEOUT,null,null,null)); save(sequenceId,fingerprint,entries); throw new SequenceException("segment "+i+" polling timeout"); }
            }
            existing=entries.get(i); Path video=options.workDirectory().resolve(String.format("segment-%03d.mp4",i));
            if(existing.stage()==SequenceCheckpointStore.Stage.SUCCEEDED) { checkCancelled(options, entries, loaded, sequenceId, fingerprint); provider.download(existing.resultUrl(),video); if(!Files.isRegularFile(video)||Files.size(video)==0) throw new SequenceException("segment "+i+" download is empty"); checkCancelled(options, entries, loaded, sequenceId, fingerprint); existing=new SequenceCheckpointStore.Entry(existing.jobId(),SequenceCheckpointStore.Stage.DOWNLOADED,existing.resultUrl(),video.toString(),null); entries.put(i,existing); loaded=save(sequenceId,fingerprint,entries); }
            if(i==source.size()-1) { checkCancelled(options, entries, loaded, sequenceId, fingerprint); existing=entries.get(i); entries.put(i,new SequenceCheckpointStore.Entry(existing.jobId(),SequenceCheckpointStore.Stage.COMPLETE,existing.resultUrl(),existing.outputPath(),null)); loaded=save(sequenceId,fingerprint,entries); continue; }
            existing=entries.get(i); Path jpg=options.workDirectory().resolve(String.format("segment-%03d-last-frame.jpg",i));
            if(existing.stage()==SequenceCheckpointStore.Stage.DOWNLOADED) { checkCancelled(options, entries, loaded, sequenceId, fingerprint); extractor.extractLastFrame(video,jpg); checkCancelled(options, entries, loaded, sequenceId, fingerprint); String ref=provider.uploadReference(jpg); if(ref==null||ref.trim().isEmpty()) throw new SequenceException("empty uploaded reference"); checkCancelled(options, entries, loaded, sequenceId, fingerprint); entries.put(i,new SequenceCheckpointStore.Entry(existing.jobId(),SequenceCheckpointStore.Stage.REFERENCE_UPLOADED,existing.resultUrl(),existing.outputPath(),ref)); loaded=save(sequenceId,fingerprint,entries); }
            existing=entries.get(i);
            if(existing.stage()==SequenceCheckpointStore.Stage.REFERENCE_UPLOADED) { entries.put(i,new SequenceCheckpointStore.Entry(existing.jobId(),SequenceCheckpointStore.Stage.COMPLETE,existing.resultUrl(),existing.outputPath(),existing.continuityRef())); loaded=save(sequenceId,fingerprint,entries); }
        } return loaded;
    }
    private SequenceCheckpointStore.Snapshot save(String id,String f,Map<Integer,SequenceCheckpointStore.Entry> e)throws Exception { var s=new SequenceCheckpointStore.Snapshot(id,f,e); store.saveAtomically(s); return s; }
    private void checkCancelled(Options o,Map<Integer,SequenceCheckpointStore.Entry> e,SequenceCheckpointStore.Snapshot s,String id,String f)throws Exception { if(o.cancellation().cancelled()){ store.saveAtomically(new SequenceCheckpointStore.Snapshot(id,f,e)); throw new SequenceException("sequence cancelled"); } }
    public static String key(String id,int index,String fingerprint){ return id+":"+index+":"+fingerprint.substring(0,16); }
    private static List<VideoProvider.Segment> immutableList(List<VideoProvider.Segment> values) { return Collections.unmodifiableList(new ArrayList<>(values)); }
    private static void field(MessageDigest d, String value) { byte[] b=value==null?null:value.getBytes(StandardCharsets.UTF_8); int n=b==null?-1:b.length; d.update((byte)(n>>>24)); d.update((byte)(n>>>16)); d.update((byte)(n>>>8)); d.update((byte)n); if(b!=null)d.update(b); }
    public static String fingerprint(List<VideoProvider.Segment> s) { try { MessageDigest d=MessageDigest.getInstance("SHA-256"); field(d,Integer.toString(s.size())); for(VideoProvider.Segment x:s) { field(d,Integer.toString(x.index())); field(d,x.model()); field(d,x.prompt()); field(d,Integer.toString(x.originalRefs().size())); for(String ref:x.originalRefs())field(d,ref); TreeMap<String,String> params=new TreeMap<>(x.originalParams()); field(d,Integer.toString(params.size())); for(Map.Entry<String,String> e:params.entrySet()){field(d,e.getKey());field(d,e.getValue());} } byte[] b=d.digest(); StringBuilder r=new StringBuilder();for(byte v:b)r.append(String.format("%02x",v));return r.toString(); }catch(Exception e){throw new IllegalStateException(e);} }
    private static void validateCheckpoint(SequenceCheckpointStore.Snapshot s, String id, String fp, int count, Path work) throws SequenceException {
        if(s==null || !id.equals(s.sequenceId()) || !fp.equals(s.inputFingerprint())) throw new SequenceException("invalid checkpoint identity");
        int expected=0; for(Map.Entry<Integer,SequenceCheckpointStore.Entry> item:new TreeMap<>(s.entries()).entrySet()) { if(item.getKey()!=expected || expected>=count) throw new SequenceException("invalid checkpoint indices"); SequenceCheckpointStore.Entry e=item.getValue(); if(e==null || e.jobId()==null || e.jobId().trim().isEmpty() || e.stage()==null) throw new SequenceException("invalid checkpoint entry"); if(e.stage()==SequenceCheckpointStore.Stage.SUCCEEDED && blank(e.resultUrl())) throw new SequenceException("invalid succeeded checkpoint"); if(e.stage()==SequenceCheckpointStore.Stage.DOWNLOADED || e.stage()==SequenceCheckpointStore.Stage.COMPLETE) { if(blank(e.resultUrl())||blank(e.outputPath())||!validOutput(e.outputPath(),work)) throw new SequenceException("invalid checkpoint output"); } if(e.stage()==SequenceCheckpointStore.Stage.REFERENCE_UPLOADED && (blank(e.resultUrl())||blank(e.outputPath())||blank(e.continuityRef()))) throw new SequenceException("invalid checkpoint reference"); if(e.stage()==SequenceCheckpointStore.Stage.COMPLETE && expected<count-1 && blank(e.continuityRef())) throw new SequenceException("missing continuity reference"); expected++; }
    }
    private static boolean blank(String v){return v==null||v.trim().isEmpty();}
    private static boolean validOutput(String value, Path work){ try { Path p=Paths.get(value); return Files.isRegularFile(p)&&Files.size(p)>0; } catch(Exception e){return false;} }
}

