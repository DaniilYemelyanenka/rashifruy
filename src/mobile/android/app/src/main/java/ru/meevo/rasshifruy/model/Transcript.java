package ru.meevo.rasshifruy.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Каноничная модель результата: хронологический список реплик.
 * Это внутренняя модель приложения (не только экспорт): UI, логи и экспортёры
 * читают всё из неё, а не параллельно.
 */
public final class Transcript {

    private final List<Utterance> utterances;

    public Transcript(List<Utterance> utterances) {
        this.utterances = Collections.unmodifiableList(new ArrayList<>(utterances));
    }

    public static Transcript empty() {
        return new Transcript(Collections.emptyList());
    }

    public List<Utterance> getUtterances() {
        return utterances;
    }

    public int size() {
        return utterances.size();
    }
}
