package ru.meevo.rasshifruy.validation;

import android.media.MediaMetadataRetriever;
import android.util.Log;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Эпик A: проверка файла перед обработкой.
 *
 * 1) whitelist расширений (.mp3/.wav — минимум, .ogg/.flac/.m4a — graceful);
 * 2) читаемость (MediaMetadataRetriever открывает файл и отдаёт длительность);
 * 3) единый лимит длительности 180 с (запас до модельных ~200 с; TODO(MVP): снимется чанкингом).
 *
 * Методы возвращают сообщение об ошибке или null, если всё в порядке.
 */
public final class AudioValidator {

    private static final String TAG = "GigaAMTest";

    /** Единый лимит длительности (решение 2026-11-07). */
    public static final long MAX_DURATION_MS = 180_000L;

    private static final Set<String> SUPPORTED_EXTENSIONS = new HashSet<>(Arrays.asList(
            "mp3", "wav", "ogg", "flac", "m4a", "aac", "m4b", "mp4", "3gp", "amr", "opus"
    ));

    private AudioValidator() {}

    /** Расширение входит в whitelist. null/без расширения — пропускаем (решит проверка формата). */
    public static boolean isSupportedExtension(String fileName) {
        if (fileName == null) {
            return true;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return true;
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.US);
        return SUPPORTED_EXTENSIONS.contains(ext);
    }

    /** Проверка лимита длительности (чистая функция; в v2 больше не применяется — см. чанкинг). */
    public static String validateDuration(long durationMs) {
        if (durationMs > MAX_DURATION_MS) {
            return "Длиннее 3 минут пока не поддерживается";
        }
        return null;
    }

    /** Длительность файла в миллисекундах (или -1, если файл не читается как аудио). */
    public static long readDurationMs(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                try {
                    return Long.parseLong(durationStr);
                } catch (NumberFormatException ignored) {
                    // не распарсилась
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Файл не читается как аудио", e);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // no-op
            }
        }
        return -1;
    }

    /** Полная валидация: расширение + читаемость. Длительность больше не ограничивается (чанкинг). */
    public static String validate(File file, String fileName) {
        if (!isSupportedExtension(fileName)) {
            return "Формат файла не поддерживается";
        }
        if (readDurationMs(file) < 0) {
            return "Файл не поддерживается/повреждён";
        }
        return null;
    }
}
