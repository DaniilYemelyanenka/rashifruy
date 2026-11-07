package ru.meevo.rasshifruy;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import ru.meevo.rasshifruy.databinding.ActivityMainBinding;
import ru.meevo.rasshifruy.export.JsonExporter;
import ru.meevo.rasshifruy.format.TimeFormatter;
import ru.meevo.rasshifruy.model.Transcript;
import ru.meevo.rasshifruy.model.Utterance;
import ru.meevo.rasshifruy.pipeline.PipelineManager;
import ru.meevo.rasshifruy.pipeline.PipelineState;

/**
 * UI-слой: только отображение статуса/результатов и приём пользовательского ввода.
 * Вся логика распознавания — в PipelineManager (Application-scoped), поэтому
 * пересоздание Activity (поворот экрана) не влияет на идущую обработку.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "GigaAMTest";

    private ActivityMainBinding binding;
    private final PipelineManager pipeline = PipelineManager.get();

    private final PipelineManager.Listener listener = this::render;
    private AnimatorSet pulseAnimator;
    private PipelineState lastState = PipelineState.IDLE;

    /** SAF-пикер аудиофайла (прав на чтение хранилища не требует). */
    private final ActivityResultLauncher<String[]> openDocument = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri == null) {
                    return;
                }
                try {
                    // Грант persistable: файл останется доступен и после поворота экрана.
                    getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                    // Провайдер не дал persistable-грант — файл всё равно обработается сразу.
                }
                pipeline.startFromFile(uri, queryDisplayName(uri));
            });

    /** Запрос RECORD_AUDIO при первом нажатии микрофона. */
    private final ActivityResultLauncher<String> requestRecordPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            granted -> {
                if (granted) {
                    pipeline.startRecording();
                } else {
                    pipeline.updateStatus("Нет доступа к микрофону");
                    Log.d(TAG, "Нет доступа к микрофону");
                }
            });

    /** Экспорт JSON через SAF (прав на хранение не требует). */
    private final ActivityResultLauncher<String> exportDocument = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"),
            uri -> {
                if (uri != null) {
                    writeJsonToUri(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Edge-to-edge + отступы под системные панели и вырез камеры.
        EdgeToEdge.enable(this);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            int base = getResources().getDimensionPixelSize(R.dimen.screen_padding);
            v.setPadding(bars.left + base, bars.top + base, bars.right + base, bars.bottom + base);
            return windowInsets;
        });

        binding.btnAddFile.setOnClickListener(v -> onAddFileClicked());
        binding.btnMic.setOnClickListener(v -> onMicClicked());
        binding.btnExport.setOnClickListener(v -> onExportClicked());

        Log.d(TAG, "MainActivity.onCreate");
    }

    @Override
    protected void onStart() {
        super.onStart();
        pipeline.addListener(listener);
        render(pipeline.getState());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Решение Q3: фоновая инициализация модели после первого onResume.
        pipeline.prefetchModel();
    }

    @Override
    protected void onStop() {
        pipeline.removeListener(listener);
        super.onStop();
    }

    private void onAddFileClicked() {
        Log.d(TAG, "MainActivity: открываю выбор аудиофайла");
        openDocument.launch(new String[]{"audio/*"});
    }

    private void onMicClicked() {
        // Эпик E: 1-е нажатие — старт записи (с запросом RECORD_AUDIO), 2-е — стоп.
        if (pipeline.isRecording()) {
            Log.d(TAG, "MainActivity: остановка записи");
            pipeline.stopRecording();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            pipeline.startRecording();
        } else {
            requestRecordPermission.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void onExportClicked() {
        if (pipeline.getTranscript().size() == 0) {
            return;
        }
        Log.d(TAG, "MainActivity: экспорт в JSON");
        exportDocument.launch(suggestedExportName());
    }

    /** Имя экспортного файла: <исходное>.json или rasshifruy_<дата-время>.json для записи. */
    private String suggestedExportName() {
        String base;
        if (pipeline.isLastSourceRecording()) {
            base = "rasshifruy_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(new Date());
        } else {
            base = pipeline.getCurrentFileName();
            if (base == null || base.isEmpty()) {
                base = "rasshifruy";
            }
            int dot = base.lastIndexOf('.');
            if (dot > 0) {
                base = base.substring(0, dot);
            }
        }
        return base + ".json";
    }

    private void writeJsonToUri(Uri uri) {
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) {
                Toast.makeText(this, "Не удалось открыть файл для записи", Toast.LENGTH_LONG).show();
                return;
            }
            String json = JsonExporter.toJson(pipeline.getTranscript());
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.flush();
            Log.d(TAG, "Экспорт JSON: " + pipeline.getTranscript().size() + " реплик");
            Toast.makeText(this, "Экспортировано", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Log.e(TAG, "Ошибка экспорта", e);
            Toast.makeText(this, "Ошибка экспорта: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void render(PipelineState state) {
        boolean busy = state.isBusy();
        boolean recording = state == PipelineState.RECORDING;
        boolean hasResult = pipeline.getTranscript().size() > 0;

        // Q2: во время пайплайна обе кнопки ввода и экспорт disabled;
        // во время записи дополнительно disabled «Добавить файл».
        binding.btnAddFile.setEnabled(!busy && !recording);
        binding.btnMic.setEnabled(!busy);
        binding.btnExport.setEnabled(hasResult && !busy && !recording);

        binding.status.setText(pipeline.getStatusMessage());

        // Анимация загрузки в поле вывода — только во время обработки пайплайна.
        setLoadingVisible(busy);
        updateMicButton(recording);

        if (!busy && !recording) {
            renderResults();
        }

        // При переходе в ERROR — показать сообщение ещё и в Toast (решение Эпика A).
        if (state == PipelineState.ERROR && lastState != PipelineState.ERROR) {
            Toast.makeText(this, pipeline.getStatusMessage(), Toast.LENGTH_LONG).show();
        }
        lastState = state;
    }

    /** Подсвечивает кнопку микрофона во время записи. */
    private void updateMicButton(boolean recording) {
        if (recording) {
            binding.btnMic.setImageTintList(ColorStateList.valueOf(getColor(R.color.terracotta)));
        } else {
            binding.btnMic.setImageTintList(null);
        }
    }

    private void renderResults() {
        Transcript transcript = pipeline.getTranscript();
        StringBuilder sb = new StringBuilder();
        for (Utterance utterance : transcript.getUtterances()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append('[')
                    .append(TimeFormatter.formatRange(utterance.getStartMs(), utterance.getEndMs()))
                    .append("] ")
                    .append(speakerLabel(utterance.getSpeakerId()))
                    .append(": ")
                    .append(utterance.getText());
        }
        binding.results.setText(sb.toString());
    }

    /** "speaker_1" -> "Спикер 1" (решение Q2/Q9). */
    private static String speakerLabel(String speakerId) {
        if (speakerId != null && speakerId.startsWith("speaker_")) {
            return "Спикер " + speakerId.substring("speaker_".length());
        }
        return "Спикер";
    }

    /** Показывает/скрывает пульсирующий логотип-микрофон в поле вывода. */
    private void setLoadingVisible(boolean visible) {
        binding.loadingView.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            if (pulseAnimator == null) {
                ObjectAnimator scaleX = ObjectAnimator.ofFloat(binding.loadingMic, View.SCALE_X, 1f, 1.18f);
                ObjectAnimator scaleY = ObjectAnimator.ofFloat(binding.loadingMic, View.SCALE_Y, 1f, 1.18f);
                scaleX.setRepeatMode(ValueAnimator.REVERSE);
                scaleY.setRepeatMode(ValueAnimator.REVERSE);
                scaleX.setRepeatCount(ValueAnimator.INFINITE);
                scaleY.setRepeatCount(ValueAnimator.INFINITE);
                pulseAnimator = new AnimatorSet();
                pulseAnimator.playTogether(scaleX, scaleY);
                pulseAnimator.setDuration(750);
            }
            if (!pulseAnimator.isStarted()) {
                pulseAnimator.start();
            }
        } else {
            if (pulseAnimator != null && pulseAnimator.isStarted()) {
                pulseAnimator.cancel();
            }
            binding.loadingMic.setScaleX(1f);
            binding.loadingMic.setScaleY(1f);
        }
    }

    private String queryDisplayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(
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
}
