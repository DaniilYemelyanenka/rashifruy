package ru.meevo.rasshifruy.recording;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import ru.meevo.rasshifruy.validation.AudioValidator;

/** Инструментальный тест записи с микрофона (Эпик E). */
@RunWith(AndroidJUnit4.class)
public class MicRecorderTest {

    @Before
    public void grantRecordPermission() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(
                InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName(),
                Manifest.permission.RECORD_AUDIO);
    }

    @Test
    public void recordProducesValidWav() throws Exception {
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File wav = new File(targetContext.getCacheDir(), "mic_test.wav");

        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        AtomicReference<Integer> duration = new AtomicReference<>(-1);

        MicRecorder recorder = new MicRecorder(new MicRecorder.Listener() {
            @Override
            public void onTick(int elapsedMs) {
            }

            @Override
            public void onFinished(File file, int durationMs) {
                duration.set(durationMs);
                finished.countDown();
            }

            @Override
            public void onError(String message) {
                error.set(message);
                finished.countDown();
            }
        });

        recorder.start(wav);
        Thread.sleep(1200); // ~1.2 с записи
        recorder.stop();

        assertTrue("запись должна завершиться", finished.await(10, TimeUnit.SECONDS));
        assertNull("ошибка записи: " + error.get(), error.get());
        assertTrue("WAV-файл создан с заголовком", wav.exists() && wav.length() >= 44);
        assertNull("WAV проходит валидацию", AudioValidator.validate(wav, "mic_test.wav"));
    }
}
