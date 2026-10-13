package com.doulala.android.video;

import java.nio.file.Path;

/** Implement with ffmpeg (for example: ffmpeg -sseof -0.1 -i input -frames:v 1 output.jpg). */
public interface LastFrameExtractor {
    void extractLastFrame(Path video, Path jpg) throws Exception;
}
