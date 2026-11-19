package com.meowsic.app;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Process;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/** Decodes a file with MediaCodec and fills a {@link WaveformData} with its peak envelope. */
final class WaveformExtractor {
    private WaveformExtractor() {
    }

    /** Runs on a background thread. Never throws; sets {@code data.failed} on problems. */
    static void run(Context ctx, Track track, WaveformData data, AtomicBoolean cancel) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(ctx, track.uri, null);
            int trackIndex = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) {
                    trackIndex = i;
                    format = f;
                    break;
                }
            }
            if (trackIndex < 0 || format == null) {
                data.failed = true;
                return;
            }
            extractor.selectTrack(trackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION) : 0;

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            Peaker peaker = new Peaker(data);
            peaker.setFormat(format);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            int idleLoops = 0;
            long lastInputUs = 0;
            while (!outputDone) {
                if (cancel.get()) {
                    return;
                }
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        ByteBuffer in = codec.getInputBuffer(inIndex);
                        int size = in == null ? -1 : extractor.readSampleData(in, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            lastInputUs = extractor.getSampleTime();
                            codec.queueInputBuffer(inIndex, 0, size, lastInputUs, 0);
                            extractor.advance();
                        }
                    }
                }
                int outIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outIndex >= 0) {
                    idleLoops = 0;
                    ByteBuffer out = codec.getOutputBuffer(outIndex);
                    if (out != null && info.size > 0) {
                        peaker.consume(out, info.offset, info.size);
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                    if (durationUs > 0) {
                        data.progress = Math.min(0.99f, lastInputUs / (float) durationUs);
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    peaker.setFormat(codec.getOutputFormat());
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inputDone && ++idleLoops > 500) {
                        break; // decoder stalled after the last input; use what we have
                    }
                }
            }
            peaker.finish();
            data.progress = 1f;
            data.complete = true;
        } catch (Throwable t) {
            data.failed = true;
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Throwable ignored) {
                    // already stopped
                }
                try {
                    codec.release();
                } catch (Throwable ignored) {
                    // nothing to do
                }
            }
            try {
                extractor.release();
            } catch (Throwable ignored) {
                // nothing to do
            }
        }
    }

    /** Turns decoded PCM into one peak value per bucket. */
    private static final class Peaker {
        private final WaveformData data;
        private int sampleRate = 44100;
        private int channels = 2;
        private int encoding = AudioFormat.ENCODING_PCM_16BIT;
        private int framesPerBucket = 882;
        private int framesInBucket;
        private int chIdx;
        private float bucketPeak;
        private long totalFrames;

        Peaker(WaveformData data) {
            this.data = data;
        }

        void setFormat(MediaFormat f) {
            if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                sampleRate = Math.max(1000, f.getInteger(MediaFormat.KEY_SAMPLE_RATE));
            }
            if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                channels = Math.max(1, f.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
            }
            encoding = AudioFormat.ENCODING_PCM_16BIT;
            if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING);
            }
            framesPerBucket = Math.max(1, (int) ((long) sampleRate * WaveformData.BUCKET_MS / 1000));
            chIdx = 0;
        }

        void consume(ByteBuffer buf, int offset, int size) {
            buf.order(ByteOrder.LITTLE_ENDIAN);
            int end = offset + size;
            int pos = offset;
            switch (encoding) {
                case AudioFormat.ENCODING_PCM_FLOAT:
                    while (pos + 4 <= end) {
                        add(Math.abs(buf.getFloat(pos)));
                        pos += 4;
                    }
                    break;
                case AudioFormat.ENCODING_PCM_8BIT:
                    while (pos < end) {
                        add(Math.abs((buf.get(pos) & 0xFF) - 128) / 128f);
                        pos++;
                    }
                    break;
                case AudioFormat.ENCODING_PCM_24BIT_PACKED:
                    while (pos + 3 <= end) {
                        int v = (buf.get(pos) & 0xFF) | ((buf.get(pos + 1) & 0xFF) << 8) | (buf.get(pos + 2) << 16);
                        add(Math.abs(v) / 8388608f);
                        pos += 3;
                    }
                    break;
                case AudioFormat.ENCODING_PCM_32BIT:
                    while (pos + 4 <= end) {
                        add(Math.abs((long) buf.getInt(pos)) / 2147483648f);
                        pos += 4;
                    }
                    break;
                default:
                    while (pos + 2 <= end) {
                        add(Math.abs((int) buf.getShort(pos)) / 32768f);
                        pos += 2;
                    }
                    break;
            }
        }

        private void add(float v) {
            if (v > bucketPeak) {
                bucketPeak = v;
            }
            if (++chIdx >= channels) {
                chIdx = 0;
                totalFrames++;
                if (++framesInBucket >= framesPerBucket) {
                    flushBucket();
                }
            }
        }

        private void flushBucket() {
            float v = bucketPeak > 1f ? 1f : bucketPeak;
            if (v > data.globalPeak) {
                data.globalPeak = v;
            }
            data.append(WaveformData.quantize(v));
            bucketPeak = 0f;
            framesInBucket = 0;
        }

        void finish() {
            if (framesInBucket > 0) {
                flushBucket();
            }
            data.durationMs = totalFrames * 1000L / sampleRate;
        }
    }
}
