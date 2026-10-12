package com.doulala.android.video;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Adapter boundary: production code supplies real HTTP calls; this core never invents success. */
public interface VideoProvider {
    String submit(Segment segment, String idempotencyKey) throws Exception;
    PollResult poll(String jobId) throws Exception;
    void download(String resultUrl, Path destination) throws Exception;
    String uploadReference(Path jpg) throws Exception;

    enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
    record PollResult(Status status, String resultUrl, String detail) {
        public PollResult { if (status == null) throw new IllegalArgumentException("status"); }
    }
    record Segment(int index, String model, String prompt, List<String> originalRefs,
                   Map<String, String> originalParams) {
        public Segment {
            if (index < 0 || model == null || model.trim().isEmpty() || prompt == null) throw new IllegalArgumentException("segment");
            originalRefs = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(originalRefs == null ? java.util.Collections.<String>emptyList() : originalRefs));
            originalParams = java.util.Collections.unmodifiableMap(new java.util.HashMap<>(originalParams == null ? java.util.Collections.<String,String>emptyMap() : originalParams));
        }
        /** The source segment is unchanged except for a prepended continuity reference. */
        public Segment withContinuityRef(String reference) {
            if (reference == null || reference.trim().isEmpty()) throw new IllegalArgumentException("reference");
            java.util.ArrayList<String> refs = new java.util.ArrayList<>(); refs.add(reference); refs.addAll(originalRefs);
            return new Segment(index, model, prompt, refs, originalParams);
        }
    }
}
