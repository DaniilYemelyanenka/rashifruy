package ru.meevo.rasshifruy;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import ru.meevo.rasshifruy.assembly.SpeakerSegment;
import ru.meevo.rasshifruy.assembly.TranscriptAssembler;
import ru.meevo.rasshifruy.model.Utterance;
import ru.meevo.rasshifruy.recognition.AudioDecoder;
import ru.meevo.rasshifruy.recognition.ModelFiles;
import ru.meevo.rasshifruy.recognition.OfflineAsr;
import ru.meevo.rasshifruy.recognition.SpeakerDiarizer;

/**
 * Нагрузочный тест: полный прогон каждого файла по чанкам 60 с
 * (декод -> ASR -> диаризация -> ассемблер) с замером времени по этапам.
 */
@RunWith(AndroidJUnit4.class)
public class AudioLoadTest {

    private static final String TAG = "GigaAMTest";
    private static final int CHUNK_SECONDS = 60;

    private static OfflineAsr asr;
    private static SpeakerDiarizer diarizer;

    @BeforeClass
    public static void setUp() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ModelFiles.Paths asrPaths = ModelFiles.prepare(ctx);
        ModelFiles.DiarizationPaths diarPaths = ModelFiles.prepareDiarization(ctx);
        asr = new OfflineAsr(asrPaths);
        diarizer = new SpeakerDiarizer(diarPaths);
    }

    @AfterClass
    public static void tearDown() {
        if (asr != null) {
            asr.release();
            asr = null;
        }
        if (diarizer != null) {
            diarizer.release();
            diarizer = null;
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
            int sourceRate = AudioDecoder.getSourceSampleRate(f);
            if (sourceRate <= 0) {
                sourceRate = OfflineAsr.SAMPLE_RATE;
            }
            long totalUs = fullDurationSec > 0 ? (long) (fullDurationSec * 1_000_000.0) : 0;
            int maxSourceSamples = CHUNK_SECONDS * sourceRate;

            long decodeMs = 0;
            long asrMs = 0;
            long diarMs = 0;
            int replicas = 0;
            int chunks = 0;
            Set<Integer> speakers = new HashSet<>();
            long startUs = 0;

            while (chunks < 200 && (totalUs <= 0 || startUs < totalUs)) {
                long t0 = System.currentTimeMillis();
                float[] samples = AudioDecoder.decodeToMono16k(f, OfflineAsr.SAMPLE_RATE, startUs, maxSourceSamples);
                decodeMs += System.currentTimeMillis() - t0;
                if (samples.length == 0) {
                    break;
                }
                float chunkSec = samples.length / (float) OfflineAsr.SAMPLE_RATE;

                long t1 = System.currentTimeMillis();
                OfflineRecognizerResult res = asr.recognize(samples);
                asrMs += System.currentTimeMillis() - t1;

                long t2 = System.currentTimeMillis();
                OfflineSpeakerDiarizationSegment[] ds = diarizer.process(samples);
                diarMs += System.currentTimeMillis() - t2;

                List<SpeakerSegment> segs = new ArrayList<>(ds.length);
                for (OfflineSpeakerDiarizationSegment d : ds) {
                    segs.add(new SpeakerSegment(d.getStart(), d.getEnd(), d.getSpeaker()));
                    speakers.add(d.getSpeaker());
                }
                List<Utterance> utts = TranscriptAssembler.assemble(
                        res.getTokens(), res.getTimestamps(), segs, chunkSec);
                replicas += utts.size();

                startUs += (long) CHUNK_SECONDS * 1_000_000L;
                chunks++;
                if (samples.length < OfflineAsr.SAMPLE_RATE * CHUNK_SECONDS * 0.9f) {
                    break;
                }
            }

            double rtf = (asrMs + diarMs) / 1000.0 / fullDurationSec;
            Log.d(TAG, "LOAD name=" + name
                    + " sizeMb=" + String.format(Locale.US, "%.2f", sizeBytes / 1048576.0)
                    + " durationSec=" + String.format(Locale.US, "%.1f", fullDurationSec)
                    + " chunks=" + chunks
                    + " decodeMs=" + decodeMs
                    + " asrMs=" + asrMs
                    + " diarMs=" + diarMs
                    + " rtf=" + String.format(Locale.US, "%.2f", rtf)
                    + " replicas=" + replicas
                    + " speakers=" + speakers.size());
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
