package ru.meevo.rasshifruy.recognition;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/** Инструментальный тест диаризации (Эпик D): модель грузится и даёт сегменты. */
@RunWith(AndroidJUnit4.class)
public class SpeakerDiarizerTest {

    private static SpeakerDiarizer diarizer;

    @BeforeClass
    public static void setUp() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ModelFiles.DiarizationPaths paths = ModelFiles.prepareDiarization(ctx);
        diarizer = new SpeakerDiarizer(paths);
    }

    @AfterClass
    public static void tearDown() {
        if (diarizer != null) {
            diarizer.release();
            diarizer = null;
        }
    }

    @Test
    public void singleSpeakerAudio_producesSegments() throws Exception {
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File f = new File(targetContext.getCacheDir(), "example.wav");
        try (InputStream in = testContext.getAssets().open("example.wav");
             FileOutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
        }

        float[] samples = AudioDecoder.decodeToMono16k(f, OfflineAsr.SAMPLE_RATE);
        OfflineSpeakerDiarizationSegment[] segments = diarizer.process(samples);

        assertTrue("диаризация должна дать хотя бы один сегмент", segments.length > 0);

        // На одноголосной записи ожидается один кластер (speaker 0 -> speaker_1).
        Set<Integer> speakers = new HashSet<>();
        for (OfflineSpeakerDiarizationSegment s : segments) {
            speakers.add(s.getSpeaker());
        }
        Log.d("GigaAMTest", "DIAR сегментов=" + segments.length + ", спикеров=" + speakers);
    }
}
