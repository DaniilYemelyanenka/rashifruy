package ru.meevo.rasshifruy.validation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/** Инструментальные тесты полной валидации на реальных файлах. */
@RunWith(AndroidJUnit4.class)
public class AudioValidatorInstrumentedTest {

    @Test
    public void validWav_isAccepted() throws IOException {
        File f = copyAsset("example.wav", ".wav");
        assertNull(AudioValidator.validate(f, "example.wav"));
    }

    @Test
    public void validOgg_isAccepted() throws IOException {
        File f = copyAsset("ru_long_consonants.ogg", ".ogg");
        assertNull(AudioValidator.validate(f, "ru_long_consonants.ogg"));
    }

    @Test
    public void txtFile_isRejectedByExtension() throws IOException {
        File f = writeBytes("doc.txt", "просто текст".getBytes(StandardCharsets.UTF_8));
        assertEquals("Формат файла не поддерживается", AudioValidator.validate(f, "doc.txt"));
    }

    @Test
    public void corruptedAudio_isRejected() throws IOException {
        byte[] garbage = new byte[4096];
        new Random(42).nextBytes(garbage);
        File f = writeBytes("broken.mp3", garbage);
        assertEquals("Файл не поддерживается/повреждён", AudioValidator.validate(f, "broken.mp3"));
    }

    @Test
    public void longAudio_isAcceptedForChunking() throws IOException {
        File f = createSilenceWav(181_000); // 181 с — больше не отклоняется (чанкинг справляется)
        assertNull(AudioValidator.validate(f, "long.wav"));
    }

    private static File copyAsset(String name, String suffix) throws IOException {
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File target = File.createTempFile("val_", suffix, targetContext.getCacheDir());
        try (InputStream in = testContext.getAssets().open(name);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        return target;
    }

    private static File writeBytes(String name, byte[] data) throws IOException {
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File target = new File(targetContext.getCacheDir(), name);
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(data);
        }
        return target;
    }

    /** Генерирует WAV с заданной длительностью тишины (16 кГц моно 16 бит). */
    private static File createSilenceWav(long durationMs) throws IOException {
        int sampleRate = 16000;
        int channels = 1;
        int bitsPerSample = 16;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int dataSize = (int) (durationMs * byteRate / 1000);

        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File target = new File(targetContext.getCacheDir(), "long_silence.wav");

        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        header.putInt(36 + dataSize);
        header.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(16);
        header.putShort((short) 1); // PCM
        header.putShort((short) channels);
        header.putInt(sampleRate);
        header.putInt(byteRate);
        header.putShort((short) blockAlign);
        header.putShort((short) bitsPerSample);
        header.put("data".getBytes(StandardCharsets.US_ASCII));
        header.putInt(dataSize);

        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(header.array());
            byte[] zeros = new byte[64 * 1024];
            int remaining = dataSize;
            while (remaining > 0) {
                int n = Math.min(remaining, zeros.length);
                out.write(zeros, 0, n);
                remaining -= n;
            }
        }
        return target;
    }
}
