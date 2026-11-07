package ru.meevo.rasshifruy.recognition;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import ru.meevo.rasshifruy.model.Utterance;

/**
 * Инструментальный end-to-end тест ядра (Эпик F): файл -> декод -> ASR -> сегментация.
 * Требует устройства/эмулятора и модели в assets. Тест-аудио — публичные Wikimedia-файлы.
 */
@RunWith(AndroidJUnit4.class)
public class AsrPipelineTest {

    private static final String TAG = "GigaAMTest";

    private static OfflineAsr asr;

    @BeforeClass
    public static void setUpModel() throws IOException {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ModelFiles.Paths paths = ModelFiles.prepare(appContext);
        asr = new OfflineAsr(paths);
    }

    @AfterClass
    public static void releaseModel() {
        if (asr != null) {
            asr.release();
            asr = null;
        }
    }

    @Test
    public void exampleWav_producesMonotonicNonEmptySegments() throws IOException {
        runAndAssert("example.wav");
    }

    @Test
    public void longOgg_producesSegmentsWithinDuration() throws IOException {
        runAndAssert("ru_long_consonants.ogg");
    }

    private static void runAndAssert(String assetName) throws IOException {
        File audio = copyTestAsset(assetName);
        float[] samples = AudioDecoder.decodeToMono16k(audio, OfflineAsr.SAMPLE_RATE);
        //noinspection ResultOfMethodCallIgnored
        audio.delete();
        assertTrue("сэмплы не пустые (" + assetName + ")", samples.length > 0);

        long start = System.currentTimeMillis();
        OfflineRecognizerResult result = asr.recognize(samples);
        long decodeMs = System.currentTimeMillis() - start;

        float durationSec = samples.length / (float) OfflineAsr.SAMPLE_RATE;
        List<Utterance> utterances = Segmenter.build(
                result.getTokens(), result.getTimestamps(), durationSec);

        assertFalse("реплики не пустые (" + assetName + ")", utterances.isEmpty());

        int durationMs = Math.round(durationSec * 1000f);
        int prevStart = -1;
        for (Utterance u : utterances) {
            assertFalse("текст реплики не пустой", u.getText().trim().isEmpty());
            assertTrue("end >= start", u.getEndMs() >= u.getStartMs());
            assertTrue("таймкоды монотонные", u.getStartMs() >= prevStart);
            assertTrue("end в пределах длительности", u.getEndMs() <= durationMs);
            prevStart = u.getStartMs();
        }

        Log.d(TAG, assetName + ": длительность " + durationMs + " мс, реплик " + utterances.size()
                + ", распознано за " + decodeMs + " мс");
    }

    private static File copyTestAsset(String name) throws IOException {
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File target = File.createTempFile("asr_test_", ".audio", targetContext.getCacheDir());
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
