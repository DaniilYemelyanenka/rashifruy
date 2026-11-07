package ru.meevo.rasshifruy.recording;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Запись с микрофона через AudioRecord сразу в родной формат модели —
 * 16 кГц, моно, 16 бит (ресемпл не нужен). Пишет WAV в cacheDir.
 */
public final class MicRecorder {

    private static final String TAG = "GigaAMTest";

    public static final int SAMPLE_RATE = 16000;
    /** Максимальная длительность записи (автостоп), единый лимит 180 с. */
    public static final int MAX_RECORDING_MS = 180_000;

    private static final int CHANNELS = 1;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int BYTES_PER_SECOND = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8; // 32000

    /** Колбэки вызываются из потока записи. */
    public interface Listener {
        void onTick(int elapsedMs);
        void onFinished(File wavFile, int durationMs);
        void onError(String message);
    }

    private final Listener listener;
    private volatile AudioRecord audioRecord;
    private Thread thread;
    private volatile boolean recording;

    public MicRecorder(Listener listener) {
        this.listener = listener;
    }

    public boolean isRecording() {
        return recording;
    }

    public void start(File wavFile) {
        recording = true;
        thread = new Thread(() -> run(wavFile), "MicRecorder");
        thread.start();
    }

    public void stop() {
        recording = false;
        AudioRecord ar = audioRecord;
        if (ar != null) {
            try {
                ar.stop(); // прерывает блокирующий read()
            } catch (Exception ignored) {
                // no-op
            }
        }
    }

    private void run(File wavFile) {
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        int elapsedMs = 0;
        try {
            int minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuffer <= 0) {
                throw new IOException("Микрофон недоступен");
            }
            int bufferSize = Math.max(minBuffer, BYTES_PER_SECOND / 5);
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IOException("Микрофон недоступен");
            }

            byte[] buffer = new byte[bufferSize];
            audioRecord.startRecording();
            int lastTickSecond = -1;
            while (recording && elapsedMs < MAX_RECORDING_MS) {
                int n = audioRecord.read(buffer, 0, buffer.length);
                if (n > 0) {
                    pcm.write(buffer, 0, n);
                    elapsedMs += n * 1000 / BYTES_PER_SECOND;
                    int second = elapsedMs / 1000;
                    if (second != lastTickSecond) {
                        lastTickSecond = second;
                        listener.onTick(elapsedMs);
                    }
                } else if (n == AudioRecord.ERROR_DEAD_OBJECT) {
                    throw new IOException("Микрофон недоступен");
                }
                // n == 0 или ERROR_INVALID_OPERATION (после stop()) — игнорируем
            }
            audioRecord.stop();
            writeWav(wavFile, pcm.toByteArray());
            Log.d(TAG, "Запись завершена: " + elapsedMs + " мс, " + pcm.size() + " байт PCM");
            listener.onFinished(wavFile, elapsedMs);
        } catch (Exception e) {
            Log.e(TAG, "Ошибка записи", e);
            listener.onError(e.getMessage() != null ? e.getMessage() : "Ошибка записи");
        } finally {
            if (audioRecord != null) {
                try {
                    audioRecord.release();
                } catch (Exception ignored) {
                    // no-op
                }
                audioRecord = null;
            }
        }
    }

    private static void writeWav(File file, byte[] pcm) throws IOException {
        int dataSize = pcm.length;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        header.putInt(36 + dataSize);
        header.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(16);
        header.putShort((short) 1); // PCM
        header.putShort((short) CHANNELS);
        header.putInt(SAMPLE_RATE);
        header.putInt(BYTES_PER_SECOND);
        header.putShort((short) (CHANNELS * BITS_PER_SAMPLE / 8));
        header.putShort((short) BITS_PER_SAMPLE);
        header.put("data".getBytes(StandardCharsets.US_ASCII));
        header.putInt(dataSize);

        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(header.array());
            out.write(pcm);
        }
    }
}
