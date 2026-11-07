package ru.meevo.rasshifruy.format;

import java.util.Locale;

/**
 * Единый формат времени mm:ss.ss для UI и экспорта (решение 2026-11-07).
 * JSON хранит целые миллисекунды, а на экран и в файл время выводится только через этот класс.
 */
public final class TimeFormatter {

    private TimeFormatter() {}

    /** Миллисекунды -> "mm:ss.ss". */
    public static String format(int milliseconds) {
        long ms = Math.max(0, milliseconds);
        long minutes = ms / 60_000;
        long seconds = (ms / 1000) % 60;
        long hundredths = (ms % 1000) / 10;
        return String.format(Locale.US, "%02d:%02d.%02d", minutes, seconds, hundredths);
    }

    /** "mm:ss.ss – mm:ss.ss" для диапазона реплики. */
    public static String formatRange(int startMs, int endMs) {
        return format(startMs) + " – " + format(endMs);
    }

    /** Миллисекунды -> "mm:ss" (для таймера записи). */
    public static String formatClock(int milliseconds) {
        long ms = Math.max(0, milliseconds);
        long minutes = ms / 60_000;
        long seconds = (ms / 1000) % 60;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }
}
