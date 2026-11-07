package ru.meevo.rasshifruy.recognition;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Декодирует произвольный аудиофайл в моно float[] с частотой 16 кГц.
 *
 * MediaExtractor + MediaCodec отдают исходные частоту и каналы; даунмикс в моно и
 * ресемпл до 16 кГц делает наш код (декодер этого не делает). В v2 — линейный
 * ресемпл как в прототипе (TODO(точность): антиалиасный ресемплер — вне скоупа).
 */
public final class AudioDecoder {

    private static final String TAG = "GigaAMTest";

    private static final long DEQUEUE_TIMEOUT_US = 10_000L;
    private static final int MAX_IDLE_LOOPS = 200;

    private AudioDecoder() {}

    public static float[] decodeToMono16k(File file, int targetSampleRate) throws IOException {
        return decodeToMono16k(file, targetSampleRate, -1);
    }

    /**
     * Декодирует максимум maxSourceSamples исходных сэмплов (-1 = весь файл).
     * Ограничение нужно для чанкинга и нагрузочных замеров длинных файлов,
     * чтобы не расти по памяти на частоте источника (иначе OOM на длинных записях).
     */
    public static float[] decodeToMono16k(File file, int targetSampleRate, int maxSourceSamples) throws IOException {
        return decodeToMono16k(file, targetSampleRate, 0, maxSourceSamples);
    }

    /**
     * Декодирует максимум maxSourceSamples исходных сэмплов начиная с позиции startUs (микросекунды).
     * Смещение нужно для чанкинга длинных файлов (декодирование по кускам).
     */
    public static float[] decodeToMono16k(File file, int targetSampleRate, long startUs, int maxSourceSamples) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());

            int trackIndex = findAudioTrack(extractor);
            if (trackIndex < 0) {
                throw new IOException("В выбранном файле нет аудиодорожки");
            }

            MediaFormat format = extractor.getTrackFormat(trackIndex);
            extractor.selectTrack(trackIndex);
            if (startUs > 0) {
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            }

            String mime = format.getString(MediaFormat.KEY_MIME);
            int sourceRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    : targetSampleRate;
            int sourceChannels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    : 1;

            Log.d(TAG, "Декодирование: mime=" + mime + ", sampleRate=" + sourceRate
                    + ", channels=" + sourceChannels + ", startUs=" + startUs);

            PcmChunk chunk;
            if (MediaFormat.MIMETYPE_AUDIO_RAW.equals(mime)) {
                // WAV/PCM: MediaCodec для audio/raw не нужен, читаем сэмплы напрямую.
                chunk = readRawPcm(extractor, format, sourceChannels, maxSourceSamples);
            } else {
                chunk = decodeWithCodec(extractor, format, sourceRate, sourceChannels, maxSourceSamples);
            }

            if (chunk.samples.length == 0) {
                return new float[0];
            }
            return resampleLinear(chunk.samples, chunk.sampleRate, targetSampleRate);
        } finally {
            extractor.release();
        }
    }

    private static int findAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }

    /** Частота дискретизации аудиодорожки файла (или -1). Нужна для расчёта чанка. */
    public static int getSourceSampleRate(File file) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int trackIndex = findAudioTrack(extractor);
            if (trackIndex < 0) {
                return -1;
            }
            MediaFormat format = extractor.getTrackFormat(trackIndex);
            return format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    : -1;
        } finally {
            extractor.release();
        }
    }

    private static PcmChunk readRawPcm(MediaExtractor extractor, MediaFormat format, int channels,
                                       int maxSourceSamples) throws IOException {
        int encoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                ? format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                : android.media.AudioFormat.ENCODING_PCM_16BIT;
        int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                : 16000;

        FloatList out = new FloatList();
        ByteBuffer buffer = ByteBuffer.allocateDirect(1 << 20).order(ByteOrder.LITTLE_ENDIAN);

        while (true) {
            buffer.clear();
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) {
                break;
            }
            buffer.position(0);
            buffer.limit(size);
            appendPcm(buffer, encoding, channels, out);
            extractor.advance();
            if (maxSourceSamples > 0 && out.size() >= maxSourceSamples) {
                break;
            }
        }
        return new PcmChunk(out.toArray(), sampleRate);
    }

    private static PcmChunk decodeWithCodec(MediaExtractor extractor, MediaFormat format,
                                            int sourceRate, int sourceChannels,
                                            int maxSourceSamples) throws IOException {
        MediaCodec codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
        FloatList out = new FloatList();
        int sampleRate = sourceRate;
        int channels = sourceChannels;
        int encoding = android.media.AudioFormat.ENCODING_PCM_16BIT;

        try {
            codec.configure(format, null, null, 0);
            codec.start();

            ByteBuffer[] inputBuffers = codec.getInputBuffers();
            ByteBuffer[] outputBuffers = codec.getOutputBuffers();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            boolean inputDone = false;
            boolean outputDone = false;
            int idleLoops = 0;

            while (!outputDone) {
                boolean madeProgress = false;

                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                    if (inIndex >= 0) {
                        ByteBuffer inBuffer = inputBuffers[inIndex];
                        inBuffer.clear();
                        int sampleSize = extractor.readSampleData(inBuffer, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                        madeProgress = true;
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFormat = codec.getOutputFormat();
                    if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                    if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        encoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                    Log.d(TAG, "Выходной формат декодера: sampleRate=" + sampleRate
                            + ", channels=" + channels + ", pcmEncoding=" + encoding);
                    madeProgress = true;
                } else if (outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = codec.getOutputBuffers();
                    madeProgress = true;
                } else if (outIndex >= 0) {
                    ByteBuffer outBuffer = outputBuffers[outIndex];
                    outBuffer.position(info.offset);
                    outBuffer.limit(info.offset + info.size);
                    appendPcm(outBuffer, encoding, channels, out);
                    codec.releaseOutputBuffer(outIndex, false);
                    madeProgress = true;

                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            || (maxSourceSamples > 0 && out.size() >= maxSourceSamples)) {
                        outputDone = true;
                    }
                }

                if (!madeProgress) {
                    idleLoops++;
                    if (idleLoops > MAX_IDLE_LOOPS) {
                        Log.w(TAG, "Декодер не отдаёт данные, останавливаемся");
                        break;
                    }
                } else {
                    idleLoops = 0;
                }
            }
        } finally {
            try {
                codec.stop();
            } catch (Exception ignored) {
                // декодер мог не успеть запуститься
            }
            codec.release();
        }

        return new PcmChunk(out.toArray(), sampleRate);
    }

    /** Преобразует interleaved PCM в моно float[-1..1]. */
    private static void appendPcm(ByteBuffer buffer, int encoding, int channels, FloatList out) {
        if (channels <= 0) {
            channels = 1;
        }
        if (encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
            int frames = buffer.remaining() / (4 * channels);
            for (int i = 0; i < frames; i++) {
                float sum = 0f;
                for (int c = 0; c < channels; c++) {
                    sum += buffer.getFloat();
                }
                out.add(sum / channels);
            }
        } else if (encoding == android.media.AudioFormat.ENCODING_PCM_8BIT) {
            int frames = buffer.remaining() / channels;
            for (int i = 0; i < frames; i++) {
                int sum = 0;
                for (int c = 0; c < channels; c++) {
                    sum += (buffer.get() & 0xFF) - 128;
                }
                out.add((sum / (float) channels) / 128f);
            }
        } else {
            // ENCODING_PCM_16BIT (по умолчанию)
            int frames = buffer.remaining() / (2 * channels);
            for (int i = 0; i < frames; i++) {
                int sum = 0;
                for (int c = 0; c < channels; c++) {
                    sum += buffer.getShort();
                }
                out.add((sum / (float) channels) / 32768f);
            }
        }
    }

    private static float[] resampleLinear(float[] input, int sourceRate, int targetRate) {
        if (sourceRate <= 0 || targetRate <= 0 || sourceRate == targetRate || input.length == 0) {
            return input;
        }

        int outputLength = Math.max(1,
                (int) Math.round(input.length * (double) targetRate / sourceRate));
        float[] output = new float[outputLength];
        double step = (double) sourceRate / targetRate;

        for (int i = 0; i < outputLength; i++) {
            double position = i * step;
            int index = (int) position;
            int next = Math.min(index + 1, input.length - 1);
            float fraction = (float) (position - index);
            output[i] = input[index] * (1f - fraction) + input[next] * fraction;
        }
        return output;
    }

    private static final class PcmChunk {
        final float[] samples;
        final int sampleRate;

        PcmChunk(float[] samples, int sampleRate) {
            this.samples = samples;
            this.sampleRate = sampleRate;
        }
    }

    /** Минимальный аналог ArrayList&lt;Float&gt; без автобоксинга. */
    private static final class FloatList {
        private float[] data = new float[16000 * 30];
        private int size;

        void add(float value) {
            if (size == data.length) {
                float[] grown = new float[data.length + (data.length >> 1)];
                System.arraycopy(data, 0, grown, 0, size);
                data = grown;
            }
            data[size++] = value;
        }

        int size() {
            return size;
        }

        float[] toArray() {
            float[] result = new float[size];
            System.arraycopy(data, 0, result, 0, size);
            return result;
        }
    }
}
