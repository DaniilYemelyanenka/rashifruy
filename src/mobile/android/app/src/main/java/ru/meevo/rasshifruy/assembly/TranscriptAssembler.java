package ru.meevo.rasshifruy.assembly;

import java.util.ArrayList;
import java.util.List;

import ru.meevo.rasshifruy.model.Utterance;

/**
 * Финальный анализ (Эпик D): «кто + что -> обобщённая расшифровка».
 *
 * Вход — две независимые структуры:
 *  - ЧТО: BPE-токены ASR с таймстемпом начала каждого;
 *  - КТО: интервалы активности спикеров из диаризации.
 *
 * Алгоритм: каждому токену присваивается спикер интервала с наибольшим перекрытием его
 * таймстемпа; при смене спикера реплика закрывается и открывается новая; текст реплики
 * собирается заново из её токенов (слово не рвётся); границы — первый/последний токен.
 *
 * Чистая функция: не зависит от UI и sherpa, покрывается unit-тестами.
 */
public final class TranscriptAssembler {

    private static final char BPE_PREFIX = '\u2581';
    /** Оценка длительности токена для расчёта перекрытия (шаг ~40 мс). */
    private static final float TOKEN_DURATION = 0.04f;

    private TranscriptAssembler() {}

    public static List<Utterance> assemble(
            String[] tokens, float[] timestamps,
            List<SpeakerSegment> segments, float audioDurationSec) {
        List<Utterance> result = new ArrayList<>();
        if (tokens == null || timestamps == null || tokens.length == 0 || segments == null) {
            return result;
        }

        int n = Math.min(tokens.length, timestamps.length);
        if (n == 0) {
            return result;
        }

        // Строим реплики: идём по токенам, группируем по спикеру.
        int replicaSpeaker = -1;
        StringBuilder text = new StringBuilder();
        float replicaStart = 0f;
        float replicaEnd = 0f;

        for (int i = 0; i < n; i++) {
            float t = timestamps[i];
            int speaker = speakerForTime(t, segments);

            if (replicaSpeaker < 0) {
                // первая реплика
                replicaSpeaker = speaker;
                replicaStart = t;
                text.setLength(0);
                text.append(join(tokens[i]));
                replicaEnd = t + TOKEN_DURATION;
            } else if (speaker == replicaSpeaker) {
                // тот же спикер — продолжаем
                text.append(join(tokens[i]));
                replicaEnd = t + TOKEN_DURATION;
            } else {
                // смена спикера — закрываем реплику
                emitReplica(result, replicaSpeaker, replicaStart, replicaEnd, text.toString(), audioDurationSec);
                replicaSpeaker = speaker;
                replicaStart = t;
                text.setLength(0);
                text.append(join(tokens[i]));
                replicaEnd = t + TOKEN_DURATION;
            }
        }
        emitReplica(result, replicaSpeaker, replicaStart, replicaEnd, text.toString(), audioDurationSec);
        return result;
    }

    /** Спикер с максимальным перекрытием таймстемпа токена; при паузе — ближайший. */
    private static int speakerForTime(float t, List<SpeakerSegment> segments) {
        int best = -1;
        float bestOverlap = 0f;
        for (SpeakerSegment s : segments) {
            float overlap = Math.min(s.endSec, t + TOKEN_DURATION) - Math.max(s.startSec, t);
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = s.speakerIndex;
            }
        }
        if (best >= 0) {
            return best;
        }
        // Пауза: ближайший интервал.
        float bestDist = Float.MAX_VALUE;
        for (SpeakerSegment s : segments) {
            float dist = t < s.startSec ? s.startSec - t : t - s.endSec;
            if (dist < bestDist) {
                bestDist = dist;
                best = s.speakerIndex;
            }
        }
        return best;
    }

    private static String join(String token) {
        return token.replace(BPE_PREFIX, ' ');
    }

    private static void emitReplica(List<Utterance> out, int speaker, float start, float end,
                                    String text, float audioDurationSec) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return;
        }
        float cappedEnd = end;
        if (audioDurationSec > 0) {
            cappedEnd = Math.min(end, audioDurationSec);
        }
        int startMs = Math.round(start * 1000f);
        int endMs = Math.round(Math.max(cappedEnd, start) * 1000f);
        // N = индекс кластера + 1.
        out.add(new Utterance("speaker_" + (speaker + 1), startMs, endMs, normalized));
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
