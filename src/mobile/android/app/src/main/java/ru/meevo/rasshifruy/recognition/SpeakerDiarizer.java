package ru.meevo.rasshifruy.recognition;

import android.util.Log;

import com.k2fsa.sherpa.onnx.FastClusteringConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment;
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;

import java.io.IOException;

/**
 * Обёртка над sherpa-onnx OfflineSpeakerDiarization: pyannote-сегментация +
 * speaker-embedding + FastClustering. Диаризация всегда включена (Эпик D).
 */
public final class SpeakerDiarizer {

    private static final String TAG = "GigaAMTest";

    private final OfflineSpeakerDiarization diarization;

    public SpeakerDiarizer(ModelFiles.DiarizationPaths paths) throws IOException {
        OfflineSpeakerSegmentationPyannoteModelConfig pyannote =
                new OfflineSpeakerSegmentationPyannoteModelConfig(paths.segmentation, 0.5f);
        OfflineSpeakerSegmentationModelConfig segmentation =
                new OfflineSpeakerSegmentationModelConfig(pyannote, 2, false, "cpu");
        SpeakerEmbeddingExtractorConfig embedding =
                new SpeakerEmbeddingExtractorConfig(paths.embedding, 2, false, "cpu");
        // numClusters = -1: число спикеров определяется автоматически по порогу.
        FastClusteringConfig clustering = new FastClusteringConfig(-1, 0.5f, false);
        OfflineSpeakerDiarizationConfig config = new OfflineSpeakerDiarizationConfig(
                segmentation, embedding, clustering, 0.3f, 0.5f);

        long start = System.currentTimeMillis();
        // assetManager = null: модель читается с путей ФС.
        diarization = new OfflineSpeakerDiarization(null, config);
        Log.d(TAG, "Диаризация инициализирована за " + (System.currentTimeMillis() - start) + " мс");
    }

    /** Сегменты диаризации: start/end (сек), speaker (индекс кластера). */
    public OfflineSpeakerDiarizationSegment[] process(float[] samples) {
        return diarization.process(samples);
    }

    public void release() {
        diarization.release();
    }
}
