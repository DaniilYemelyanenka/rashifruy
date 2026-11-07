package ru.meevo.rasshifruy.pipeline;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;

import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import ru.meevo.rasshifruy.assembly.SpeakerSegment;
import ru.meevo.rasshifruy.assembly.TranscriptAssembler;
import ru.meevo.rasshifruy.format.TimeFormatter;
import ru.meevo.rasshifruy.model.Transcript;
import ru.meevo.rasshifruy.model.Utterance;
import ru.meevo.rasshifruy.recognition.AudioDecoder;
import ru.meevo.rasshifruy.recognition.ModelFiles;
import ru.meevo.rasshifruy.recognition.OfflineAsr;
import ru.meevo.rasshifruy.recognition.SpeakerDiarizer;
import ru.meevo.rasshifruy.recording.MicRecorder;
import ru.meevo.rasshifruy.validation.AudioValidator;

/**
 * Application-scoped синглтон пайплайна распознавания.
 *
 * Зачем: начатая обработка не должна зависеть от жизненного цикла Activity —
 * поворот экрана/пересоздание Activity не прерывают распознавание. Поэтому модель,
 * фоновый поток и состояние живут здесь, а не в UI. Процесс прерывается только
 * при закрытии приложения.
 */
public final class PipelineManager {

    /** Подписчик на смену состояния (UI подписывается/отписывается в onStart/onStop). */
    public interface Listener {
        void onStateChanged(PipelineState state);
    }

    private static final String TAG = "GigaAMTest";
    /** Минимальная длительность записи, меньше — «Слишком короткая запись». */
    private static final int MIN_RECORDING_MS = 500;
    /** Длина чанка распознавания: больше ~90 с ASR деградирует (см. PROMPT §чанкинг). */
    private static final int CHUNK_SECONDS = 60;
    /** Защита от бесконечного цикла чанкинга. */
    private static final int MAX_CHUNKS = 200;
    private static volatile PipelineManager instance;

    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    /** Один фоновый поток: все шаги пайплайна выполняются последовательно. */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean prefetchStarted = new AtomicBoolean(false);
    /** Слушатели — это UI-слой, поэтому уведомления всегда маршалятся на главный поток. */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Context appContext;
    private volatile OfflineAsr recognizer;
    private volatile SpeakerDiarizer diarizer;
    private volatile MicRecorder micRecorder;

    private volatile PipelineState state = PipelineState.IDLE;
    private volatile String statusMessage = "";
    private volatile String currentFileName = "";
    private volatile boolean lastSourceWasRecording = false;
    private volatile Transcript transcript = Transcript.empty();

    private PipelineManager() {}

    /** Инициализация из Application.onCreate (до создания любой Activity). */
    public static void init(Context appContext) {
        if (instance == null) {
            synchronized (PipelineManager.class) {
                if (instance == null) {
                    PipelineManager manager = new PipelineManager();
                    manager.appContext = appContext.getApplicationContext();
                    instance = manager;
                    Log.d(TAG, "PipelineManager: синглтон создан");
                }
            }
        }
    }

    public static PipelineManager get() {
        PipelineManager local = instance;
        if (local == null) {
            throw new IllegalStateException("PipelineManager не инициализирован (вызовите init() в Application)");
        }
        return local;
    }

    public PipelineState getState() {
        return state;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public String getCurrentFileName() {
        return currentFileName;
    }

    /** Последний обработанный источник — запись с микрофона (влияет на имя экспорта). */
    public boolean isLastSourceRecording() {
        return lastSourceWasRecording;
    }

    public Transcript getTranscript() {
        return transcript;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * Фоновая инициализация модели после первого onResume (решение Q3): копирование
     * из assets + создание сессий распознавания. Идемпотентно и не блокирует UI.
     */
    public void prefetchModel() {
        if (!prefetchStarted.compareAndSet(false, true)) {
            return;
        }
        executor.execute(() -> {
            if (recognizer != null) {
                return;
            }
            try {
                setState(PipelineState.PREPARING, "Подготовка модели…");
                getOrInitRecognizer();
            } catch (Throwable t) {
                Log.e(TAG, "Ошибка инициализации модели", t);
                setState(PipelineState.ERROR, describeError(t));
                return;
            }
            if (state == PipelineState.PREPARING) {
                setState(PipelineState.IDLE, "");
            }
        });
    }

    /** Запуск пайплайна для выбранного аудиофайла. Без очереди: пока занято или идёт запись — вызов игнорируется. */
    public void startFromFile(Uri uri, String fileName) {
        if (uri == null) {
            return;
        }
        if (!canStartNewWork()) {
            Log.d(TAG, "startFromFile отклонён: пайплайн занят (" + state + ")");
            return;
        }
        currentFileName = fileName == null ? "" : fileName;
        lastSourceWasRecording = false;
        // Синхронно помечаем пайплайн занятым, чтобы UI сразу показал статус и не было
        // гонки: тест/пользователь не увидит старый DONE до старта фоновой задачи.
        setState(PipelineState.PREPARING, status("Подготовка модели…"));
        executor.execute(() -> {
            File cached = null;
            try {
                cached = copyToCache(uri);
                processLocalFile(cached);
            } catch (Throwable t) {
                Log.e(TAG, "Ошибка пайплайна", t);
                setState(PipelineState.ERROR, describeError(t));
            } finally {
                if (cached != null) {
                    //noinspection ResultOfMethodCallIgnored
                    cached.delete();
                }
            }
        });
    }

    /** Новую работу можно начать из IDLE/DONE/ERROR (не во время пайплайна и не во время записи). */
    private boolean canStartNewWork() {
        return !state.isBusy() && state != PipelineState.RECORDING;
    }

    /** Имя файла (если задано) + этап обработки — для строки статуса. */
    private String status(String stage) {
        return currentFileName.isEmpty() ? stage : currentFileName + " — " + stage;
    }

    /** Обрабатывает локальный файл: валидация -> почанково декод+распознавание -> DONE. */
    private void processLocalFile(File file) {
        try {
            setState(PipelineState.PREPARING, status("Подготовка модели…"));
            OfflineAsr asr = getOrInitRecognizer();
            SpeakerDiarizer diarizer = getOrInitDiarizer();

            setState(PipelineState.DECODING, status("Декодирование…"));

            // Эпик A: валидация файла (расширение/читаемость) до декодирования.
            String validationError = AudioValidator.validate(file, currentFileName);
            if (validationError != null) {
                setState(PipelineState.ERROR, status(validationError));
                return;
            }

            int sourceRate = AudioDecoder.getSourceSampleRate(file);
            if (sourceRate <= 0) {
                sourceRate = OfflineAsr.SAMPLE_RATE;
            }
            long totalMs = AudioValidator.readDurationMs(file);
            long totalUs = totalMs > 0 ? totalMs * 1000L : 0;

            List<Utterance> all = new ArrayList<>();
            int chunkIndex = 0;
            long startUs = 0;
            int maxSourceSamples = CHUNK_SECONDS * sourceRate;

            setState(PipelineState.RECOGNIZING, status("Распознавание…"));
            while (chunkIndex < MAX_CHUNKS && (totalUs <= 0 || startUs < totalUs)) {
                float[] samples = AudioDecoder.decodeToMono16k(file, OfflineAsr.SAMPLE_RATE, startUs, maxSourceSamples);
                if (samples.length == 0) {
                    break;
                }
                float chunkSec = samples.length / (float) OfflineAsr.SAMPLE_RATE;
                OfflineRecognizerResult result = asr.recognize(samples);

                // Эпик D: диаризация (кто говорит) + слияние с токенами ASR.
                setState(PipelineState.DIARIZING, status("Диаризация…"));
                OfflineSpeakerDiarizationSegment[] diarSegments = diarizer.process(samples);
                List<SpeakerSegment> segments = new ArrayList<>(diarSegments.length);
                for (OfflineSpeakerDiarizationSegment s : diarSegments) {
                    segments.add(new SpeakerSegment(s.getStart(), s.getEnd(), s.getSpeaker()));
                }
                List<Utterance> chunkUtterances = TranscriptAssembler.assemble(
                        result.getTokens(), result.getTimestamps(), segments, chunkSec);

                long offsetMs = (long) chunkIndex * CHUNK_SECONDS * 1000L;
                for (Utterance u : chunkUtterances) {
                    all.add(new Utterance(u.getSpeakerId(),
                            u.getStartMs() + (int) offsetMs,
                            u.getEndMs() + (int) offsetMs,
                            u.getText()));
                }

                Log.d(TAG, "Чанк " + chunkIndex + ": " + chunkUtterances.size()
                        + " реплик, сегментов диаризации " + diarSegments.length
                        + " (" + String.format(java.util.Locale.US, "%.1f", chunkSec) + " с)");

                setState(PipelineState.RECOGNIZING, status("Распознавание…"));
                startUs += (long) CHUNK_SECONDS * 1_000_000L;
                chunkIndex++;

                // Последний (неполный) чанк — конец файла.
                if (samples.length < OfflineAsr.SAMPLE_RATE * CHUNK_SECONDS * 0.9f) {
                    break;
                }
            }

            setTranscript(new Transcript(all));
            for (Utterance u : all) {
                Log.d(TAG, "РЕПЛИКА [" + u.getStartMs() + "-" + u.getEndMs() + " мс] "
                        + u.getSpeakerId() + ": " + u.getText());
            }
            setState(PipelineState.DONE, status("Готово: " + all.size() + " реплик"));
        } catch (Throwable t) {
            Log.e(TAG, "Ошибка пайплайна", t);
            setState(PipelineState.ERROR, describeError(t));
        }
    }

    /** Начинает запись с микрофона (состояние RECORDING). */
    public void startRecording() {
        if (!canStartNewWork()) {
            Log.d(TAG, "startRecording отклонён: пайплайн занят (" + state + ")");
            return;
        }
        currentFileName = "Запись";
        lastSourceWasRecording = true;
        File wavFile = new File(appContext.getCacheDir(), "recording_" + System.currentTimeMillis() + ".wav");
        micRecorder = new MicRecorder(new MicRecorder.Listener() {
            @Override
            public void onTick(int elapsedMs) {
                updateStatus("Записывается… " + TimeFormatter.formatClock(elapsedMs));
            }

            @Override
            public void onFinished(File file, int durationMs) {
                handleRecordingFinished(file, durationMs);
            }

            @Override
            public void onError(String message) {
                setState(PipelineState.ERROR, "Ошибка: " + message);
            }
        });
        setState(PipelineState.RECORDING, "Записывается… 00:00");
        micRecorder.start(wavFile);
    }

    /** Останавливает запись (2-е нажатие микрофона). */
    public void stopRecording() {
        if (micRecorder != null) {
            micRecorder.stop();
        }
    }

    /** Идёт ли сейчас запись с микрофона. */
    public boolean isRecording() {
        return state == PipelineState.RECORDING;
    }

    private void handleRecordingFinished(File wavFile, int durationMs) {
        Log.d(TAG, "Запись завершена: " + durationMs + " мс");
        if (durationMs < MIN_RECORDING_MS) {
            setState(PipelineState.ERROR, "Слишком короткая запись");
            //noinspection ResultOfMethodCallIgnored
            wavFile.delete();
            return;
        }
        // Файл записи идёт по общему пайплайну (валидация -> декод -> распознавание).
        executor.execute(() -> {
            try {
                processLocalFile(wavFile);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                wavFile.delete();
            }
        });
    }

    /** Обновляет только строку статуса (без смены состояния) — для таймера записи. */
    public void updateStatus(String message) {
        this.statusMessage = message == null ? "" : message;
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onStateChanged(state);
            }
        });
    }

    private OfflineAsr getOrInitRecognizer() throws IOException {
        if (recognizer == null) {
            synchronized (this) {
                if (recognizer == null) {
                    Log.d(TAG, "Инициализация sherpa-onnx (GigaAM v3)…");
                    ModelFiles.Paths paths = ModelFiles.prepare(appContext);
                    recognizer = new OfflineAsr(paths);
                }
            }
        }
        return recognizer;
    }

    private SpeakerDiarizer getOrInitDiarizer() throws IOException {
        if (diarizer == null) {
            synchronized (this) {
                if (diarizer == null) {
                    Log.d(TAG, "Инициализация диаризации…");
                    ModelFiles.DiarizationPaths paths = ModelFiles.prepareDiarization(appContext);
                    diarizer = new SpeakerDiarizer(paths);
                }
            }
        }
        return diarizer;
    }

    private File copyToCache(Uri uri) throws IOException {
        String suffix = ".bin";
        String displayName = queryDisplayName(uri);
        if (displayName != null && displayName.contains(".")) {
            suffix = displayName.substring(displayName.lastIndexOf('.'));
        }
        File target = File.createTempFile("rasshifruy_", suffix, appContext.getCacheDir());

        try (InputStream in = appContext.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) {
                throw new IOException("Не удалось открыть выбранный файл");
            }
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        Log.d(TAG, "Файл скопирован в кэш: " + target.getAbsolutePath() + " (" + target.length() + " байт)");
        return target;
    }

    private String queryDisplayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = appContext.getContentResolver().query(
                    uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    return cursor.getString(idx);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Не удалось узнать имя файла", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    private static String describeError(Throwable t) {
        String message = t.getMessage();
        return "Ошибка: " + (message != null && !message.isEmpty() ? message : t.toString());
    }

    private void setState(PipelineState newState, String message) {
        this.state = newState;
        this.statusMessage = message == null ? "" : message;
        Log.d(TAG, "PipelineManager: состояние -> " + newState + ", статус: " + this.statusMessage);
        // setState вызывается из фонового потока, а слушатели обновляют UI —
        // поэтому колбэки доставляются на главный поток (иначе «Animators may only run on Looper threads»).
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onStateChanged(newState);
            }
        });
    }

    private void setTranscript(Transcript value) {
        this.transcript = value == null ? Transcript.empty() : value;
    }
}
