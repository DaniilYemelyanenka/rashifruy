package ru.meevo.rasshifruy;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Проба: длительность и формат всех тестовых аудиофайлов (для нагрузочного отчёта). */
@RunWith(AndroidJUnit4.class)
public class AudioProbeTest {

    private static final String TAG = "GigaAMTest";

    @Test
    public void probeAllFiles() throws Exception {
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String[] names = {"example.wav", "ru_long_consonants.ogg", "переговоры.mp3", "подкаст.mp3"};

        for (String name : names) {
            File f = new File(targetContext.getCacheDir(), name);
            try (InputStream in = testContext.getAssets().open(name);
                 FileOutputStream out = new FileOutputStream(f)) {
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) != -1) {
                    out.write(buf, 0, r);
                }
            }

            long durationMs = -1;
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(f.getAbsolutePath());
                String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (d != null) {
                    durationMs = Long.parseLong(d);
                }
            } catch (Exception e) {
                Log.e(TAG, "probe duration failed: " + name, e);
            } finally {
                mmr.release();
            }

            String mime = null;
            int rate = -1;
            int channels = -1;
            MediaExtractor ex = new MediaExtractor();
            try {
                ex.setDataSource(f.getAbsolutePath());
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    MediaFormat fmt = ex.getTrackFormat(i);
                    String m = fmt.getString(MediaFormat.KEY_MIME);
                    if (m != null && m.startsWith("audio/")) {
                        mime = m;
                        if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        }
                        if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "probe format failed: " + name, e);
            } finally {
                ex.release();
            }

            Log.d(TAG, "PROBE file=" + name + " bytes=" + f.length()
                    + " durationMs=" + durationMs
                    + " mime=" + mime + " rate=" + rate + " channels=" + channels);
        }
    }
}
