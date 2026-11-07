package ru.meevo.rasshifruy.assembly;

/**
 * Интервал активности спикера (из диаризации): начало/конец в секундах, индекс кластера.
 * Простой data-holder, чтобы TranscriptAssembler не зависел от sherpa.
 */
public final class SpeakerSegment {

    public final float startSec;
    public final float endSec;
    public final int speakerIndex;

    public SpeakerSegment(float startSec, float endSec, int speakerIndex) {
        this.startSec = startSec;
        this.endSec = endSec;
        this.speakerIndex = speakerIndex;
    }
}
