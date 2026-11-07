package ru.meevo.rasshifruy.recognition;

import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig;

import java.io.IOException;

/**
 * Обёртка над sherpa-onnx OfflineRecognizer для модели GigaAM v3 (NeMo RNN-T).
 * Конфиг проверен на MVP-прототипе (см. PROMPT_APP_V2.md §Справка).
 */
public final class OfflineAsr {

    private static final String TAG = "GigaAMTest";

    /** Родная частота модели — 16 кГц моно. */
    public static final int SAMPLE_RATE = 16000;

    private final OfflineRecognizer recognizer;

    public OfflineAsr(ModelFiles.Paths paths) throws IOException {
        FeatureConfig feat = new FeatureConfig();
        feat.setSampleRate(SAMPLE_RATE);
        feat.setFeatureDim(64); // у GigaAM 64 mel-бина, а не 80, как у большинства моделей
        feat.setDither(0f);

        OfflineTransducerModelConfig transducer = new OfflineTransducerModelConfig();
        transducer.setEncoder(paths.encoder);
        transducer.setDecoder(paths.decoder);
        transducer.setJoiner(paths.joiner);

        OfflineModelConfig model = new OfflineModelConfig();
        model.setTransducer(transducer);
        model.setTokens(paths.tokens);
        model.setModelType("nemo_transducer");
        model.setNumThreads(4);
        model.setDebug(false);
        // NNAPI готового AAR не работает (issue #2073) — всегда cpu.
        model.setProvider("cpu");

        OfflineRecognizerConfig config = new OfflineRecognizerConfig();
        config.setFeatConfig(feat);
        config.setModelConfig(model);
        config.setDecodingMethod("greedy_search");

        long start = System.currentTimeMillis();
        // assetManager обязан быть null: модель читается с путей ФС, иначе sherpa-onnx
        // аварийно завершает процесс ("absolute path ... but assetManager is NOT set to null").
        recognizer = new OfflineRecognizer(null, config);
        Log.d(TAG, "Модель загружена за " + (System.currentTimeMillis() - start) + " мс");
    }

    /**
     * Распознаёт моно-сэмплы 16 кГц. Результат содержит текст, токены и таймстемпы
     * начала каждого BPE-токена (шаг ~40 мс).
     */
    public OfflineRecognizerResult recognize(float[] samples) {
        OfflineStream stream = recognizer.createStream();
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE);
            recognizer.decode(stream);
            return recognizer.getResult(stream);
        } finally {
            stream.release();
        }
    }

    public void release() {
        recognizer.release();
    }
}
