# «Расшифруй» — Android-приложение

## Требования
- JDK 21
- Android SDK (platform 37, build-tools 36)
- Эмулятор/устройство с ОЗУ более 4 ГБ

## Сборка
```
./gradlew assembleDebug
```

## Тесты
```
./gradlew testDebugUnitTest          # unit-тесты
./gradlew connectedDebugAndroidTest  # инструментальные (нужен запущенный эмулятор)
```

Тяжёлые нагрузочные тесты лучше гонять отдельно:
```
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ru.meevo.rasshifruy.AudioLoadTest
```

## Структура
- `app/src/main/java/ru/meevo/rasshifruy/` — исходный код
  - `pipeline/` — PipelineManager, состояния
  - `recognition/` — ASR, диаризация, декодер, модели
  - `assembly/` — TranscriptAssembler (кто + что)
  - `validation/` — валидация файлов
  - `recording/` — запись с микрофона
  - `export/` — экспорт JSON
  - `model/`, `format/` — каноничная модель, форматтер времени
- `app/src/main/assets/gigaam/` — модель ASR (GigaAM v3)
- `app/src/main/assets/diarization/` — модели диаризации
- `app/libs/sherpa-onnx.aar` — движок sherpa-onnx

## Примечание
- Модели (`.onnx`) и AAR — большие бинарники, в Git идут через LFS (см. корневой `.gitattributes`).
- `applicationId`: `ru.meevo.rasshifruy`, `minSdk 26`, `targetSdk 36`.
