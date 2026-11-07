package ru.meevo.rasshifruy.pipeline;

/**
 * Состояния пайплайна распознавания (решение Q1/Q13: один статус на все события).
 */
public enum PipelineState {
    IDLE,
    RECORDING,
    PREPARING,
    DECODING,
    RECOGNIZING,
    DIARIZING,
    DONE,
    ERROR;

    /**
     * Пайплайн активно обрабатывает аудио. DONE/ERROR — терминальные состояния,
     * а не «занятость»: при них UI должен показывать результат и разрешать новый запуск.
     * RECORDING сюда не входит — это отдельный режим записи с микрофона.
     */
    public boolean isBusy() {
        return this == PREPARING || this == DECODING || this == RECOGNIZING || this == DIARIZING;
    }
}
