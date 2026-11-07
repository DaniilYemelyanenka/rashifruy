package ru.meevo.rasshifruy;

import android.content.Context;
import android.media.MediaMetadataRetriever;
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
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

import ru.meevo.rasshifruy.model.Utterance;
import ru.meevo.rasshifruy.recognition.AudioDecoder;
import ru.meevo.rasshifruy.recognition.ModelFiles;
import ru.meevo.rasshifruy.recognition.OfflineAsr;
import ru.meevo.rasshifruy.recognition.Segmenter;

/**
 * Нагрузочный тест (lead-tester): декод + распознавание + RTF + память по всем тестовым аудио.
 * Для файлов длиннее 180 с декодируется/распознаётся первый 180-секундный кусок
 * (полный проход требует чанкинга — вне скоупа v2).
 */
@RunWith(AndroidJUnit4.class)
public class AudioLoadTest {

    private static final String TAG = "GigaAMTest";
    /** ~180 с исходных сэмплов при 44.1 кГц (короткие файлы декодируются целиком). */
    private static final int MAX_SOURCE_SAMPLES = 180 * 44100;

    private static OfflineAsr asr;

    @BeforeClass
    public static void setUp() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ModelFiles.Paths paths = ModelFiles.prepare(ctx);
        asr = new OfflineAsr(paths);
    }

    @AfterClass
    public static void tearDown() {
        if (asr != null) {
            asr.release();
            asr = null;
        }
    }

    @Test
    public void loadTestAllFiles() throws Exception {
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String[] names = {"example.wav", "ru_long_consonants.ogg", "переговоры.mp3", "подкаст.mp3"};

        for (String name : names) {
            File f = copyAsset(testContext, targetContext, name);
            long sizeBytes = f.length();
            double fullDurationSec = probeDurationSec(f);
            boolean chunked = fullDurationSec > 180.0;

            long t0 = System.currentTimeMillis();
            float[] samples = AudioDecoder.decodeToMono16k(f, OfflineAsr.SAMPLE_RATE, MAX_SOURCE_SAMPLES);
            long decodeMs = System.currentTimeMillis() - t0;

            double recognizedSec = samples.length / (double) OfflineAsr.SAMPLE_RATE;
            long decodedSamplesMb = Math.round(samples.length * 4L / 1048576.0);

            long memBefore = usedMemory();
            long t1 = System.currentTimeMillis();
            OfflineRecognizerResult res = asr.recognize(samples);
            long recogMs = System.currentTimeMillis() - t1;
            long memAfter = usedMemory();

            List<Utterance> utts = Segmenter.build(
                    res.getTokens(), res.getTimestamps(), (float) recognizedSec);
            double rtf = recogMs / 1000.0 / recognizedSec;

            Log.d(TAG, "LOAD name=" + name
                    + " sizeMb=" + String.format(Locale.US, "%.2f", sizeBytes / 1048576.0)
                    + " fullDurationSec=" + String.format(Locale.US, "%.1f", fullDurationSec)
                    + " decodeMs=" + decodeMs
                    + " decodedSamplesMb=" + decodedSamplesMb
                    + " recogMs=" + recogMs
                    + " rtf=" + String.format(Locale.US, "%.2f", rtf)
                    + " replicas=" + utts.size()
                    + " memDeltaMb=" + String.format(Locale.US, "%.1f", (memAfter - memBefore) / 1048576.0)
                    + (chunked ? " CHUNKED=first180s" : " FULL"));
        }
    }

    private static double probeDurationSec(File f) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(f.getAbsolutePath());
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) {
                return Long.parseLong(d) / 1000.0;
            }
        } catch (Exception ignored) {
            // no-op
        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {
                // no-op
            }
        }
        return -1;
    }

    private static long usedMemory() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static File copyAsset(Context testContext, Context targetContext, String name) throws Exception {
        File f = new File(targetContext.getCacheDir(), name);
        try (InputStream in = testContext.getAssets().open(name);
             FileOutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
        }
        return f;
    }
}
