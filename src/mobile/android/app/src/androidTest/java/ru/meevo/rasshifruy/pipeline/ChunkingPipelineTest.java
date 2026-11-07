package ru.meevo.rasshifruy.pipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Регрессионный тест чанкинга: длинный файл (переговоры.mp3, 550 с) обрабатывается
 * по 60-секундным чанкам и даёт непустой транскрипт (раньше ASR на длинном куске
 * возвращал пусто).
 */
@RunWith(AndroidJUnit4.class)
public class ChunkingPipelineTest {

    @Test
    public void longFile_isProcessedViaChunking() throws Exception {
        PipelineManager pipeline = PipelineManager.get();

        long deadline = System.currentTimeMillis() + 30_000;
        while (pipeline.getState().isBusy() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }

        File audio = copyTestAsset("переговоры.mp3");
        pipeline.startFromFile(Uri.fromFile(audio), "переговоры.mp3");

        deadline = System.currentTimeMillis() + 300_000; // до 5 минут на 10 чанков
        PipelineState state = pipeline.getState();
        while (state != PipelineState.DONE && state != PipelineState.ERROR
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(1000);
            state = pipeline.getState();
        }

        assertEquals(PipelineState.DONE, state);
        assertTrue("транскрипт не должен быть пустым", pipeline.getTranscript().size() > 0);
    }

    private static File copyTestAsset(String name) throws Exception {
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File target = File.createTempFile("chunk_", ".mp3", targetContext.getCacheDir());
        try (InputStream in = testContext.getAssets().open(name);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
        }
        return target;
    }
}
