package com.authorityclosers.salesxray;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * How loud a call recording is, frame by frame, decoded on the phone (Redmi
 * audit #53/#60). Only the start of a long call is read, within a time
 * budget, so a check never holds the page up for long. Nothing leaves the
 * phone; AudioCheckRules turns the frames into a verdict.
 */
final class AudioLevels {

    /** Enough to tell a silent or one-sided recording. */
    static final int MAX_SECONDS = 180;
    static final long BUDGET_MS = 8_000;
    private static final long WAIT_US = 10_000;

    private AudioLevels() {}

    /** The verdict for one recording; "unknown" when the phone can't decode it. */
    static JSONObject check(Context context, String source) {
        return verdict(frames(context, source, MAX_SECONDS, BUDGET_MS));
    }

    static JSONObject verdict(double[] frames) {
        if (frames != null) return AudioCheckRules.verdict(frames);
        JSONObject unknown = new JSONObject();
        try {
            unknown.put("verdict", "unknown");
        } catch (JSONException ignored) {}
        return unknown;
    }

    /** dBFS per 0.1 s frame, all channels together; null when it can't be read. */
    static double[] frames(Context context, String source, int maxSeconds, long budgetMs) {
        long deadline = System.currentTimeMillis() + budgetMs;
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try (ParcelFileDescriptor descriptor = RecordingScanner.descriptor(context, source)) {
            extractor.setDataSource(descriptor.getFileDescriptor());
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    extractor.selectTrack(i);
                    format = candidate;
                    break;
                }
            }
            if (format == null) return null;
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            Frames frames = new Frames(maxSeconds);
            frames.setFormat(format);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            while (!frames.full() && System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(WAIT_US);
                    if (in >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(in);
                        int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, WAIT_US);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    frames.setFormat(codec.getOutputFormat());
                } else if (out >= 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(out);
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        frames.add(buffer.slice().order(ByteOrder.nativeOrder()));
                    }
                    codec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
            return frames.done();
        } catch (IOException | RuntimeException error) {
            return null;
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (RuntimeException ignored) {}
                codec.release();
            }
            extractor.release();
        }
    }

    /** Sums squares into 0.1 s frames as decoded audio arrives. */
    private static final class Frames {

        private final double[] levels;
        private int count;
        private int rate = 8000;
        private int channels = 1;
        private boolean floats;
        private double sum;
        private int samples;

        Frames(int maxSeconds) {
            levels = new double[(int) Math.round(maxSeconds / AudioCheckRules.FRAME_SECONDS)];
        }

        void setFormat(MediaFormat format) {
            if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = Math.max(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
            floats = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
        }

        boolean full() {
            return count >= levels.length;
        }

        private int perFrame() {
            return Math.max(1, (int) Math.round(rate * AudioCheckRules.FRAME_SECONDS)) * channels;
        }

        void add(ByteBuffer pcm) {
            if (floats) {
                FloatBuffer values = pcm.asFloatBuffer();
                while (values.hasRemaining() && !full()) take(values.get());
            } else {
                ShortBuffer values = pcm.asShortBuffer();
                while (values.hasRemaining() && !full()) take(values.get() / 32768.0);
            }
        }

        private void take(double sample) {
            sum += sample * sample;
            if (++samples >= perFrame()) {
                levels[count++] = AudioCheckRules.decibels(Math.sqrt(sum / samples));
                sum = 0;
                samples = 0;
            }
        }

        double[] done() {
            double[] result = new double[count];
            System.arraycopy(levels, 0, result, 0, count);
            return result;
        }
    }
}
