package com.authorityclosers.salesxray;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import java.io.File;
import java.io.IOException;

/** One speech-size AAC recorder for both ways Sales Xray records (AppRecorder, CallRecorderService). */
final class Mic {

    private Mic() {}

    /**
     * Starts recording {@code source} into {@code file}: 16 kHz mono AAC at 32 kbps
     * (about 14 MB an hour). Asked for 96 kbps at 44.1 kHz, the Redmi's encoder
     * wrote 670 kbps.
     */
    static MediaRecorder start(Context context, File file, int source, long maxMs) throws IOException {
        MediaRecorder recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(context) : new MediaRecorder();
        try {
            recorder.setAudioSource(source);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1);
            recorder.setAudioSamplingRate(16_000);
            recorder.setAudioEncodingBitRate(32_000);
            recorder.setMaxDuration((int) maxMs);
            recorder.setOutputFile(file.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            return recorder;
        } catch (IOException | RuntimeException error) {
            recorder.release();
            throw error instanceof IOException ? (IOException) error : new IOException(error);
        }
    }

    /** Stops and releases; true when the file holds audio. */
    static boolean stop(MediaRecorder recorder) {
        boolean saved = false;
        try {
            recorder.stop();
            saved = true;
        } catch (RuntimeException error) {
            // Stopped within a moment of starting: nothing to keep.
        }
        try {
            recorder.release();
        } catch (RuntimeException ignored) {}
        return saved;
    }
}
