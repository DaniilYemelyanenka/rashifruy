package ru.meevo.rasshifruy.export;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import ru.meevo.rasshifruy.model.Transcript;
import ru.meevo.rasshifruy.model.Utterance;

/**
 * Эпик C: экспорт в JSON. Сериализует каноничную модель в схему
 * {"transcript":[{speaker_id,start_ms,end_ms,text},...]} (см. PROMPT §Каноничная JSON-схема).
 */
public final class JsonExporter {

    private JsonExporter() {}

    /** Каноничный JSON результата. Целые миллисекунды — как в модели. */
    public static String toJson(Transcript transcript) {
        try {
            JSONArray array = new JSONArray();
            for (Utterance u : transcript.getUtterances()) {
                JSONObject obj = new JSONObject();
                obj.put("speaker_id", u.getSpeakerId());
                obj.put("start_ms", u.getStartMs());
                obj.put("end_ms", u.getEndMs());
                obj.put("text", u.getText());
                array.put(obj);
            }
            JSONObject root = new JSONObject();
            root.put("transcript", array);
            return root.toString(2);
        } catch (JSONException e) {
            // Невозможно при наших типах (только String/int) — страхуемся от падения.
            throw new IllegalStateException("Ошибка сериализации JSON", e);
        }
    }
}
