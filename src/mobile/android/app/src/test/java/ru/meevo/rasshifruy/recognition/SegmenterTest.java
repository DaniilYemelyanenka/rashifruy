package ru.meevo.rasshifruy.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import ru.meevo.rasshifruy.model.Utterance;

/** Unit-тесты token-based сегментации (без sherpa — чистые данные). */
public class SegmenterTest {

    @Test
    public void nullOrEmptyTokens_produceNoUtterances() {
        assertEquals(0, Segmenter.build(null, null, 0f).size());
        assertEquals(0, Segmenter.build(new String[0], new float[0], 0f).size());
    }

    @Test
    public void singleWord_producesOneUtterance() {
        List<Utterance> result = Segmenter.build(
                new String[]{"\u2581привет"},
                new float[]{0f},
                1f);

        assertEquals(1, result.size());
        assertEquals("привет", result.get(0).getText());
        assertEquals(0, result.get(0).getStartMs());
    }

    @Test
    public void bpeContinuation_doesNotBreakWord() {
        // «соглас» + «ные» — второй токен без ▁-префикса, склеивается без пробела.
        List<Utterance> result = Segmenter.build(
                new String[]{"\u2581соглас", "ные"},
                new float[]{0f, 0.04f},
                1f);

        assertEquals(1, result.size());
        assertEquals("согласные", result.get(0).getText());
    }

    @Test
    public void longPause_splitsIntoTwoUtterances() {
        List<Utterance> result = Segmenter.build(
                new String[]{"\u2581привет", "\u2581мир"},
                new float[]{0f, 1.0f},
                2f);

        assertEquals(2, result.size());
        assertEquals("привет", result.get(0).getText());
        assertEquals("мир", result.get(1).getText());
        assertTrue(result.get(0).getEndMs() <= result.get(1).getStartMs());
    }

    @Test
    public void sentenceEnd_splitsAndShortGapMergesBack() {
        List<Utterance> result = Segmenter.build(
                new String[]{"\u2581привет.", "\u2581как", "\u2581дела"},
                new float[]{0f, 0.5f, 0.9f},
                2f);

        assertEquals(2, result.size());
        assertEquals("привет.", result.get(0).getText());
        assertEquals("как дела", result.get(1).getText());
    }

    @Test
    public void timestampsAreMonotonicAndWithinDuration() {
        List<Utterance> result = Segmenter.build(
                new String[]{"\u2581один", "\u2581два", "\u2581три", "\u2581четыре"},
                new float[]{0f, 1.0f, 2.0f, 3.0f},
                3.2f);

        int durationMs = Math.round(3.2f * 1000f);
        int prevStart = -1;
        for (Utterance u : result) {
            assertTrue(u.getStartMs() >= prevStart);
            assertTrue(u.getEndMs() >= u.getStartMs());
            assertTrue(u.getEndMs() <= durationMs);
            prevStart = u.getStartMs();
        }
    }
}
