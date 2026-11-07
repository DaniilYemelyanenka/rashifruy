package ru.meevo.rasshifruy.pipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import ru.meevo.rasshifruy.model.Transcript;

/**
 * Сквозной тест пайплайна (уровень PipelineManager): выбранный файл -> декод ->
 * распознавание -> каноничный транскрипт -> состояние DONE. Проверяет реальный
 * пользовательский путь, который MainActivity запускает через startFromFile.
 */
@RunWith(AndroidJUnit4.class)
public class PipelineFlowTest {

    @Test
    public void startFromFile_reachesDoneWithNonEmptyTranscript() throws Exception {
        PipelineManager pipeline = PipelineManager.get();

        // Если модель ещё готовится (prefetch из другого теста) — дождаться, пока пайплайн не занят.
        long deadline = System.currentTimeMillis() + 30_000;
        while (pipeline.getState().isBusy() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertFalse("пайплайн не должен быть занят", pipeline.getState().isBusy());

        File audio = copyTestAsset("example.wav");
        Uri uri = Uri.fromFile(audio);
        pipeline.startFromFile(uri, "example.wav");

        deadline = System.currentTimeMillis() + 60_000;
        PipelineState state = pipeline.getState();
        while (state != PipelineState.DONE && state != PipelineState.ERROR
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            state = pipeline.getState();
        }

        assertEquals(PipelineState.DONE, state);
        Transcript transcript = pipeline.getTranscript();
        assertTrue("транскрипт не пустой", transcript.size() > 0);
        assertTrue("статус должен содержать имя файла", pipeline.getStatusMessage().contains("example.wav"));
    }

    private static File copyTestAsset(String name) throws IOException {
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File target = File.createTempFile("flow_", ".wav", targetContext.getCacheDir());
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
}
