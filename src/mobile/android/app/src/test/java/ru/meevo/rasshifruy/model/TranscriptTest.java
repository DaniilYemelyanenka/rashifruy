package ru.meevo.rasshifruy.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;

/** Unit-тесты каноничной модели (этап B): не требуют Android-рантайма. */
public class TranscriptTest {

    @Test
    public void emptyTranscript_hasZeroUtterances() {
        Transcript transcript = Transcript.empty();
        assertEquals(0, transcript.size());
        assertTrue(transcript.getUtterances().isEmpty());
    }

    @Test
    public void utterance_keepsCanonicalFields() {
        Utterance utterance = new Utterance("speaker_1", 1250, 4380, "Добрый день!");
        assertEquals("speaker_1", utterance.getSpeakerId());
        assertEquals(1250, utterance.getStartMs());
        assertEquals(4380, utterance.getEndMs());
        assertEquals("Добрый день!", utterance.getText());
    }
}
