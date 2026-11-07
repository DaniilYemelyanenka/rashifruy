# Архитектура решения

## Контекст
Офлайн-приложение для Android (Java, UI в XML, Material 3): распознавание русской речи
с диаризацией говорящих. Вся обработка — на устройстве, сеть не используется.

## Компоненты
Три слоя (ни один не знает про остальные больше, чем нужно; обмен — только через каноничный список реплик):

1. **UI-слой** — `MainActivity`: отображение статуса/результатов и приём ввода.
2. **Управление** — `PipelineManager` (Application-scoped синглтон): владеет моделью и
   фоновым потоком, состояния `IDLE/PREPARING/DECODING/RECOGNIZING/DIARIZING/DONE/ERROR`.
   Распознавание не привязано к Activity — переживает поворот экрана.
3. **Данные (пайплайн)** — декод (`MediaCodec` → PCM) → даунмикс в моно + ресемпл 16 кГц →
   ASR (`OfflineRecognizer`) → диаризация (`OfflineSpeakerDiarization`) → `TranscriptAssembler`.

## Основные технические решения
- Движок: **sherpa-onnx** (AAR v2.0.1) — `OfflineRecognizer` + `OfflineSpeakerDiarization`,
  провайдер `cpu` (NNAPI готового AAR не работает).
- Модели: **GigaAM v3** (RNN-T, русский) + **pyannote-сегментация** + **3D-Speaker embedding**.
- Каноничная модель реплики `{speaker_id, start_ms, end_ms, text}` — единая для UI, логов и экспорта.
- **Чанкинг 60 с** (лимит модели ~200 с; ASR деградирует на чанках > ~90 с).
- Инициализация модели — в фоне после первого `onResume`.
- R8/ProGuard отключены (защита нативного JNI-слоя sherpa).

## Зависимости
- `sherpa-onnx.aar` (v2.0.1) в `app/libs`.
- ONNX-модели в `app/src/main/assets` (`noCompress += "onnx"`).
- androidx: appcompat, material, constraintlayout, activity, core.

## Безопасность и приватность
- Полный офлайн: нет `INTERNET`-permission, нет сетевых вызовов в рантайме.
- Записи с микрофона — только в приватном каталоге (`cacheDir`).
- В коде нет «зашитых» эталонных расшифровок: весь текст — только из результата модели.
