package ru.meevo.rasshifruy.pipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.Manifest;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Интеграционный тест записи (Эпик E): старт -> RECORDING -> стоп -> файл идёт по
 * пайплайну -> DONE. Записанная на эмуляторе тишина может дать пустой транскрипт,
 * но состояние должно дойти до DONE (без ошибок).
 */
@RunWith(AndroidJUnit4.class)
public class RecordingFlowTest {

    @Before
    public void grantPermission() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(
                InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName(),
                Manifest.permission.RECORD_AUDIO);
    }

    @Test
    public void recording_reachesPipelineDone() throws Exception {
        PipelineManager pipeline = PipelineManager.get();

        // Дождаться, что пайплайн не занят (модель готова).
        long deadline = System.currentTimeMillis() + 30_000;
        while (pipeline.getState().isBusy() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }

        pipeline.startRecording();
        assertTrue("состояние RECORDING после старта", pipeline.isRecording());

        Thread.sleep(1200); // ~1.2 с записи (> минимум 0.5 с)
        pipeline.stopRecording();

        deadline = System.currentTimeMillis() + 60_000;
        PipelineState state = pipeline.getState();
        while (state != PipelineState.DONE && state != PipelineState.ERROR
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            state = pipeline.getState();
        }

        assertEquals("запись должна пройти пайплайн до DONE", PipelineState.DONE, state);
    }
}
