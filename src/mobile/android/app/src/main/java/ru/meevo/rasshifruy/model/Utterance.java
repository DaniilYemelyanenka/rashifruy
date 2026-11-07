package ru.meevo.rasshifruy.model;

/**
 * Одна реплика: кто (speaker_id), когда (start_ms/end_ms) и что (text) сказал.
 * Соответствует каноничной JSON-схеме (см. PROMPT_APP_V2.md, §Каноничная JSON-схема).
 */
public final class Utterance {

    private final String speakerId;
    private final int startMs;
    private final int endMs;
    private final String text;

    public Utterance(String speakerId, int startMs, int endMs, String text) {
        this.speakerId = speakerId;
        this.startMs = startMs;
        this.endMs = endMs;
        this.text = text;
    }

    public String getSpeakerId() {
        return speakerId;
    }

    public int getStartMs() {
        return startMs;
    }

    public int getEndMs() {
        return endMs;
    }

    public String getText() {
        return text;
    }
}
