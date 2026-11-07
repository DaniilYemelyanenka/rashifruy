package ru.meevo.rasshifruy.assembly;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import ru.meevo.rasshifruy.model.Utterance;

/** Unit-тесты TranscriptAssembler («кто + что -> реплики»). */
public class TranscriptAssemblerTest {

    private static List<SpeakerSegment> segs(SpeakerSegment... s) {
        return Arrays.asList(s);
    }

    @Test
    public void singleSpeaker_allUtterancesAreSpeaker1() {
        List<Utterance> result = TranscriptAssembler.assemble(
                new String[]{"\u2581привет", "\u2581мир"},
                new float[]{0f, 1f},
                segs(new SpeakerSegment(0f, 2f, 0)),
                2f);

        assertEquals(1, result.size());
        assertEquals("speaker_1", result.get(0).getSpeakerId());
        assertEquals("привет мир", result.get(0).getText());
    }

    @Test
    public void speakerChangeAtTokenBoundary_createsTwoReplicas() {
        List<Utterance> result = TranscriptAssembler.assemble(
                new String[]{"\u2581привет", "\u2581пока"},
                new float[]{0f, 5f},
                segs(new SpeakerSegment(0f, 4.9f, 0), new SpeakerSegment(5f, 10f, 1)),
                10f);

        assertEquals(2, result.size());
        assertEquals("speaker_1", result.get(0).getSpeakerId());
        assertEquals("привет", result.get(0).getText());
        assertEquals(0, result.get(0).getStartMs());

        assertEquals("speaker_2", result.get(1).getSpeakerId());
        assertEquals("пока", result.get(1).getText());
        assertEquals(5000, result.get(1).getStartMs());
    }

    @Test
    public void pauseBetweenSpeakers_createsSeparateReplicas() {
        List<Utterance> result = TranscriptAssembler.assemble(
                new String[]{"\u2581да", "\u2581нет"},
                new float[]{0f, 10f},
                segs(new SpeakerSegment(0f, 2f, 0), new SpeakerSegment(9f, 12f, 1)),
                12f);

        assertEquals(2, result.size());
        assertEquals("speaker_1", result.get(0).getSpeakerId());
        assertEquals("да", result.get(0).getText());
        assertEquals("speaker_2", result.get(1).getSpeakerId());
        assertEquals("нет", result.get(1).getText());
    }

    @Test
    public void bpeContinuation_doesNotBreakWord() {
        List<Utterance> result = TranscriptAssembler.assemble(
                new String[]{"\u2581соглас", "ные"},
                new float[]{0f, 0.04f},
                segs(new SpeakerSegment(0f, 1f, 0)),
                1f);

        assertEquals(1, result.size());
        assertEquals("согласные", result.get(0).getText());
    }

    @Test
    public void emptyTokens_producesNoReplicas() {
        assertTrue(TranscriptAssembler.assemble(null, null, null, 0f).isEmpty());
        assertTrue(TranscriptAssembler.assemble(new String[0], new float[0], Collections.emptyList(), 0f).isEmpty());
    }

    @Test
    public void timestampsAreMonotonicAndWithinDuration() {
        List<Utterance> result = TranscriptAssembler.assemble(
                new String[]{"\u2581один", "\u2581два", "\u2581три", "\u2581четыре"},
                new float[]{0f, 1f, 2f, 3f},
                segs(new SpeakerSegment(0f, 4f, 0)),
                3.5f);

        int durationMs = Math.round(3.5f * 1000f);
        int prevStart = -1;
        for (Utterance u : result) {
            assertTrue(u.getStartMs() >= prevStart);
            assertTrue(u.getEndMs() >= u.getStartMs());
            assertTrue(u.getEndMs() <= durationMs);
            prevStart = u.getStartMs();
        }
    }
}
