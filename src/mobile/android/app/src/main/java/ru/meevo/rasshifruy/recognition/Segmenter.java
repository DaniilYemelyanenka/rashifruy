package ru.meevo.rasshifruy.recognition;

import java.util.ArrayList;
import java.util.List;

import ru.meevo.rasshifruy.model.Utterance;

/**
 * Token-based сегментация: таймстемпы BPE-токенов -> реплики каноничной модели.
 *
 * sherpa-onnx для RNN-T отдаёт время начала каждого BPE-токена (шаг ~40 мс:
 * хоп 10 мс × сабсемплинг 4). Слова склеиваются без добавленного пробела — пробел
 * несёт сам ▁-префикс токена.
 *
 * Алгоритм:
 *  1) Разбиваем токены на «сырые» куски по паузам >= PAUSE_GAP или по концу
 *     предложения (. ! ? …).
 *  2) Склеиваем обратно куски, разрыв между которыми искусственный: пунктуация
 *     отдельным куском, либо маленькая пауза (< MERGE_GAP) внутри фразы.
 *
 * Секунды -> миллисекунды конвертируются ровно один раз, здесь (Math.round(сек*1000)).
 * До Эпика D (диаризация) всем репликам приписывается speaker_1.
 */
public final class Segmenter {

    private static final float PAUSE_GAP = 0.4f;
    private static final float MERGE_GAP = 0.7f;
    private static final float LAST_TOKEN_DURATION = 0.3f;
    private static final float MAX_TOKEN_DURATION = 0.6f;
    private static final char BPE_PREFIX = '\u2581';
    private static final String DEFAULT_SPEAKER = "speaker_1";

    private Segmenter() {}

    /** Промежуточный кусок: gapAfter — пауза, которой он закончился (-1 = конец предложения/текста). */
    private static final class Raw {
        float start;
        float end;
        float gapAfter;
        String text;

        Raw(float start, float end, float gapAfter, String text) {
            this.start = start;
            this.end = end;
            this.gapAfter = gapAfter;
            this.text = text;
        }
    }

    public static List<Utterance> build(String[] tokens, float[] timestamps, float audioDurationSec) {
        List<Utterance> result = new ArrayList<>();
        if (tokens == null || timestamps == null) {
            return result;
        }

        int n = Math.min(tokens.length, timestamps.length);
        List<Raw> raws = splitRaw(tokens, timestamps, n);
        List<Raw> merged = merge(raws);

        for (Raw raw : merged) {
            float end = raw.end;
            if (audioDurationSec > 0) {
                end = Math.min(end, audioDurationSec);
            }
            String text = normalize(raw.text);
            if (text.isEmpty()) {
                continue;
            }
            int startMs = Math.round(raw.start * 1000f);
            int endMs = Math.round(Math.max(end, raw.start) * 1000f);
            result.add(new Utterance(DEFAULT_SPEAKER, startMs, endMs, text));
        }
        return result;
    }

    /** Шаг 1: разбиение токенов по паузам и концам предложений. */
    private static List<Raw> splitRaw(String[] tokens, float[] timestamps, int n) {
        List<Raw> raws = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        float currentStart = -1f;
        float currentEnd = 0f;

        for (int i = 0; i < n; i++) {
            String piece = tokens[i].replace(BPE_PREFIX, ' ');

            if (currentStart < 0) {
                currentStart = timestamps[i];
            }
            current.append(piece);

            float nextStart = (i + 1 < n) ? timestamps[i + 1] : -1f;
            float duration = nextStart >= 0
                    ? Math.min(nextStart - timestamps[i], MAX_TOKEN_DURATION)
                    : LAST_TOKEN_DURATION;
            currentEnd = timestamps[i] + duration;

            float gap = nextStart >= 0 ? nextStart - timestamps[i] : -1f;
            boolean pause = gap >= PAUSE_GAP;
            boolean sentenceEnd = isSentenceEnd(piece);
            boolean last = (i == n - 1);

            if (pause || (sentenceEnd && !last) || last) {
                String text = current.toString();
                if (!text.trim().isEmpty()) {
                    // Пауза фиксируется только если кусок закончен именно паузой,
                    // а не концом предложения: склеивать через точку нельзя.
                    float gapAfter = (pause && !last) ? gap : -1f;
                    raws.add(new Raw(currentStart, currentEnd, gapAfter, text));
                }
                current.setLength(0);
                currentStart = -1f;
            }
        }
        return raws;
    }

    /** Шаг 2: склейка искусственных разрывов. */
    private static List<Raw> merge(List<Raw> raws) {
        List<Raw> out = new ArrayList<>(raws.size());
        for (Raw raw : raws) {
            if (out.isEmpty()) {
                out.add(raw);
                continue;
            }
            Raw prev = out.get(out.size() - 1);

            boolean punctuationOnly = isPunctuationOnly(raw.text);
            boolean insidePhrase = prev.gapAfter >= 0
                    && prev.gapAfter < MERGE_GAP
                    && !isSentenceEnd(prev.text)
                    && startsLowercase(raw.text);

            if (punctuationOnly || insidePhrase) {
                // Конкатенация без пробела: токены-продолжения слова не начинаются
                // с ▁, а новые слова приходят с ведущим пробелом сами.
                prev.end = Math.max(prev.end, raw.end);
                prev.text = prev.text + raw.text;
                prev.gapAfter = raw.gapAfter;
            } else {
                out.add(raw);
            }
        }
        return out;
    }

    private static boolean isSentenceEnd(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        char last = trimmed.charAt(trimmed.length() - 1);
        return last == '.' || last == '!' || last == '?' || last == '\u2026';
    }

    private static boolean isPunctuationOnly(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetterOrDigit(text.charAt(i))) {
                return false;
            }
        }
        return !text.trim().isEmpty();
    }

    private static boolean startsLowercase(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                return Character.isLowerCase(c);
            }
        }
        return false;
    }

    /** Схлопывает двойные пробелы, убирает крайние и «висящую» пунктуацию в начале. */
    private static String normalize(String text) {
        String collapsed = text.replaceAll("\\s+", " ").trim();
        while (!collapsed.isEmpty()
                && Character.getType(collapsed.charAt(0)) == Character.START_PUNCTUATION) {
            collapsed = collapsed.substring(1).trim();
        }
        return collapsed;
    }
}
