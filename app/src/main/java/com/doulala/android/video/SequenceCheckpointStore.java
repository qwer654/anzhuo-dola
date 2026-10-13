package com.doulala.android.video;

import java.util.*;

/** Checkpoint storage must make save atomic (write temp, fsync if supported, then rename). */
public interface SequenceCheckpointStore {
    Optional<Snapshot> load(String sequenceId) throws Exception;
    void saveAtomically(Snapshot snapshot) throws Exception;

    record Snapshot(String sequenceId, String inputFingerprint, Map<Integer, Entry> entries) {
        public Snapshot { entries = Collections.unmodifiableMap(new TreeMap<>(entries == null ? Collections.<Integer,Entry>emptyMap() : entries)); }
    }
    record Entry(String jobId, Stage stage, String resultUrl, String outputPath, String continuityRef) {}
    enum Stage { SUBMITTED, SUCCEEDED, DOWNLOADED, REFERENCE_UPLOADED, COMPLETE, FAILED, CANCELLED, TIMEOUT }
}
