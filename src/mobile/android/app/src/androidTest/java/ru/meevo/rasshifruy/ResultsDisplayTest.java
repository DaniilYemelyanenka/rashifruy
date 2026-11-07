package ru.meevo.rasshifruy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import ru.meevo.rasshifruy.pipeline.PipelineManager;
import ru.meevo.rasshifruy.pipeline.PipelineState;

/**
 * Регрессионный тест: после завершения обработки в поле вывода должен появиться
 * текст результата, а анимация загрузки — скрыться (баг: DONE считался «занятым»).
 */
@RunWith(AndroidJUnit4.class)
public class ResultsDisplayTest {

    @Test
    public void resultsText_isShownAfterProcessing() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            PipelineManager pipeline = PipelineManager.get();
            waitForNotBusy(pipeline, 30_000);

            File audio = copyTestAsset("example.wav");
            pipeline.startFromFile(Uri.fromFile(audio), "example.wav");

            long deadline = System.currentTimeMillis() + 60_000;
            while (pipeline.getState() != PipelineState.DONE
                    && pipeline.getState() != PipelineState.ERROR
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(500);
            }
            // Дать главному потоку обработать render(DONE).
            Thread.sleep(500);

            final String[] text = {""};
            final boolean[] loadingGone = {false};
            scenario.onActivity(activity -> {
                TextView results = activity.findViewById(R.id.results);
                text[0] = results.getText().toString();
                View loading = activity.findViewById(R.id.loadingView);
                loadingGone[0] = loading.getVisibility() == View.GONE;
            });

            assertFalse("текст результата должен быть непустым", text[0].trim().isEmpty());
            assertTrue("анимация загрузки должна скрыться после завершения", loadingGone[0]);
        }
    }

    /** Ждёт, пока пайплайн не занят (IDLE/DONE/ERROR) и продержится так секунду (prefetch завершён). */
    private static void waitForNotBusy(PipelineManager pipeline, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!pipeline.getState().isBusy()) {
                long stableStart = System.currentTimeMillis();
                boolean stable = true;
                while (System.currentTimeMillis() - stableStart < 1000) {
                    if (pipeline.getState().isBusy()) {
                        stable = false;
                        break;
                    }
                    Thread.sleep(100);
                }
                if (stable) {
                    return;
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("таймаут ожидания завершения prefetch");
    }

    private static File copyTestAsset(String name) throws IOException {
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File target = File.createTempFile("results_", ".wav", targetContext.getCacheDir());
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
