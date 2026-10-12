package com.doulala.android;

import android.content.Context;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Runs the app-bundled FFmpeg executable to decode every video frame and retain
 * the final decoded video frame as a bounded JPEG. Inputs and outputs must be
 * staged in app-private storage before this API is called.
 */
public final class FfmpegLastFrame {
    public interface CancellationSignal {
        boolean isCancelled();
    }

    public static final long DEFAULT_TIMEOUT_MILLIS = 120_000L;
    private static final long POLL_MILLIS = 100L;
    private static final int MAX_LOG_BYTES = 32 * 1024;
    private static final int MIN_JPEG_BYTES = 128;
    private static final int MAX_EDGE_PIXELS = 1280;

    private FfmpegLastFrame() { }

    /**
     * Decodes from beginning to EOF. FFmpeg's image2 {@code -update 1} replaces
     * the same temporary JPEG after each decoded frame, so the committed file is
     * the actual final decoded frame (not a seek-near-end approximation).
     *
     * This is intentionally O(number of decoded frames); callers should execute
     * it off the UI thread and stage both files in Context-private storage.
     */
    public static File extract(Context context, File video, File outputJpg,
                               CancellationSignal cancellation) throws IOException {
        return extract(context, video, outputJpg, cancellation, DEFAULT_TIMEOUT_MILLIS);
    }

    static File extract(Context context, File video, File outputJpg,
                        CancellationSignal cancellation, long timeoutMillis) throws IOException {
        if (context == null || video == null || outputJpg == null) {
            throw new IllegalArgumentException("context, video, and outputJpg are required");
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        if(cancellation!=null&&cancellation.isCancelled())throw new IOException("Last-frame extraction was cancelled");
        File input = video.getCanonicalFile();
        File requestedOutput = outputJpg.getCanonicalFile();
        if (!isAppPrivate(context, input) || !isAppPrivate(context, requestedOutput)) {
            throw new IOException("FFmpeg inputs and outputs must be app-private staged files");
        }
        if (!input.isFile() || input.length() == 0) {
            throw new IOException("Video is missing or empty: " + input);
        }
        if (input.equals(requestedOutput)) {
            throw new IOException("Output must not overwrite the staged input video");
        }
        File parent = requestedOutput.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("Cannot create output directory");
        }
        if (!parent.isDirectory()) {
            throw new IOException("Output parent is not a directory");
        }

        File executable = new File(context.getApplicationInfo().nativeLibraryDir, "libffmpegexec.so");
        if (!executable.isFile()) {
            throw new IOException("Bundled FFmpeg executable is unavailable for this ABI");
        }
        File temporary = new File(parent, "." + requestedOutput.getName() + "."
                + UUID.randomUUID() + ".jpg");
        deleteQuietly(temporary);

        List<String> command = new ArrayList<>(Arrays.asList(
                executable.getAbsolutePath(), "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-i", input.getAbsolutePath(),
                "-map", "0:v:0", "-an", "-sn", "-dn",
                // Autorotation is FFmpeg's default. The scale keeps decoding output bounded.
                "-vf", "scale='min(" + MAX_EDGE_PIXELS + ",iw)':'min(" + MAX_EDGE_PIXELS
                        + ",ih)':force_original_aspect_ratio=decrease:force_divisible_by=2",
                "-c:v", "mjpeg", "-q:v", "3", "-f", "image2", "-update", "1",
                temporary.getAbsolutePath()));

        Process process = null;
        OutputDrainer drainer = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            drainer = new OutputDrainer(process.getInputStream());
            drainer.start();
            long deadline = SystemClock.elapsedRealtime() + timeoutMillis;
            while (process.isAlive()) {
                if (cancellation != null && cancellation.isCancelled()) {
                    terminate(process);
                    throw new IOException("Last-frame extraction was cancelled");
                }
                if (SystemClock.elapsedRealtime() >= deadline) {
                    terminate(process);
                    throw new IOException("Last-frame extraction timed out after " + timeoutMillis + " ms");
                }
                SystemClock.sleep(POLL_MILLIS);
            }
            int exitCode = process.exitValue();
            joinDrainer(drainer);
            if (exitCode != 0) {
                throw new IOException("FFmpeg failed (exit " + exitCode + "): " + drainer.text());
            }
            if (!isJpeg(temporary)) {
                throw new IOException("FFmpeg completed without a valid JPEG: " + drainer.text());
            }
            Files.move(temporary.toPath(), requestedOutput.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (!isJpeg(requestedOutput)) {
                deleteQuietly(requestedOutput);
                throw new IOException("Committed JPEG failed validation");
            }
            return requestedOutput;
        } finally {
            if (process != null && process.isAlive()) {
                terminate(process);
            }
            joinDrainer(drainer);
            deleteQuietly(temporary);
        }
    }

    private static boolean isAppPrivate(Context context, File candidate) throws IOException {
        String path = candidate.getCanonicalPath();
        return isUnder(context.getFilesDir(), path)
                || isUnder(context.getCacheDir(), path)
                || isUnder(context.getCodeCacheDir(), path)
                || isUnder(context.getNoBackupFilesDir(), path);
    }

    private static boolean isUnder(File root, String candidate) throws IOException {
        if (root == null) return false;
        String base = root.getCanonicalPath();
        return candidate.startsWith(base + File.separator);
    }

    private static boolean isJpeg(File file) throws IOException {
        if (!file.isFile() || file.length() < MIN_JPEG_BYTES) return false;
        try (FileInputStream in = new FileInputStream(file)) {
            if (in.read() != 0xff || in.read() != 0xd8) return false;
        }
        try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(file, "r")) {
            in.seek(file.length() - 2);
            return in.read() == 0xff && in.read() == 0xd9;
        }
    }

    private static void terminate(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static void joinDrainer(OutputDrainer drainer) {
        if (drainer == null) return;
        try {
            drainer.join(2_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) file.delete();
    }

    /** Drains continuously so verbose malformed input cannot deadlock FFmpeg. */
    private static final class OutputDrainer extends Thread {
        private final InputStream input;
        private final ByteArrayOutputStream retained = new ByteArrayOutputStream();

        OutputDrainer(InputStream input) {
            super("ffmpeg-last-frame-output");
            this.input = input;
            setDaemon(true);
        }

        @Override public void run() {
            byte[] buffer = new byte[4096];
            try (InputStream stream = input) {
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    synchronized (retained) {
                        int left = MAX_LOG_BYTES - retained.size();
                        if (left > 0) retained.write(buffer, 0, Math.min(left, count));
                    }
                }
            } catch (IOException ignored) {
                // The process may have been deliberately terminated.
            }
        }

        String text() {
            synchronized (retained) {
                return new String(retained.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).trim();
            }
        }
    }
}



