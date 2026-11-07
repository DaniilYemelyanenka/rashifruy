package ru.meevo.rasshifruy.export;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;

import ru.meevo.rasshifruy.model.Transcript;
import ru.meevo.rasshifruy.model.Utterance;

/** Проверка сериализации каноничной модели в JSON-схему. */
@RunWith(AndroidJUnit4.class)
public class JsonExporterTest {

    @Test
    public void serializesCanonicalSchema() throws Exception {
        Transcript transcript = new Transcript(Arrays.asList(
                new Utterance("speaker_1", 1250, 4380, "Добрый день!"),
                new Utterance("speaker_2", 5000, 7200, "Здравствуйте.")
        ));

        JSONObject root = new JSONObject(JsonExporter.toJson(transcript));
        JSONArray array = root.getJSONArray("transcript");

        assertEquals(2, array.length());

        JSONObject first = array.getJSONObject(0);
        assertEquals("speaker_1", first.getString("speaker_id"));
        assertEquals(1250, first.getInt("start_ms"));
        assertEquals(4380, first.getInt("end_ms"));
        assertEquals("Добрый день!", first.getString("text"));
    }

    @Test
    public void emptyTranscript_producesEmptyArray() throws Exception {
        JSONObject root = new JSONObject(JsonExporter.toJson(Transcript.empty()));
        assertEquals(0, root.getJSONArray("transcript").length());
    }

    @Test
    public void textWithSpecialChars_isEscaped() throws Exception {
        Transcript transcript = new Transcript(Collections.singletonList(
                new Utterance("speaker_1", 0, 100, "Он сказал: \"привет\" и ушёл\nна следующую строку.")
        ));

        JSONObject root = new JSONObject(JsonExporter.toJson(transcript));
        String text = root.getJSONArray("transcript").getJSONObject(0).getString("text");
        assertEquals("Он сказал: \"привет\" и ушёл\nна следующую строку.", text);
    }
}
