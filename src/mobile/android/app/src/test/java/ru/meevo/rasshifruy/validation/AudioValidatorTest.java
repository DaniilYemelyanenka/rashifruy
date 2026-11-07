package ru.meevo.rasshifruy.validation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit-тесты чистой логики валидации (без Android-рантайма). */
public class AudioValidatorTest {

    @Test
    public void supportedExtensions_areAccepted() {
        assertTrue(AudioValidator.isSupportedExtension("song.mp3"));
        assertTrue(AudioValidator.isSupportedExtension("record.wav"));
        assertTrue(AudioValidator.isSupportedExtension("voice.ogg"));
        assertTrue(AudioValidator.isSupportedExtension("music.flac"));
        assertTrue(AudioValidator.isSupportedExtension("note.m4a"));
        assertTrue(AudioValidator.isSupportedExtension("SONG.MP3")); // регистр не важен
    }

    @Test
    public void unsupportedExtensions_areRejected() {
        assertFalse(AudioValidator.isSupportedExtension("doc.txt"));
        assertFalse(AudioValidator.isSupportedExtension("file.pdf"));
        assertFalse(AudioValidator.isSupportedExtension("photo.jpg"));
    }

    @Test
    public void nullOrNoExtension_passesExtensionCheck() {
        assertTrue(AudioValidator.isSupportedExtension(null));
        assertTrue(AudioValidator.isSupportedExtension("noext"));
        assertTrue(AudioValidator.isSupportedExtension("trailing."));
    }

    @Test
    public void durationWithinLimit_isOk() {
        assertNull(AudioValidator.validateDuration(0));
        assertNull(AudioValidator.validateDuration(180_000));
    }

    @Test
    public void durationOverLimit_isRejected() {
        assertEquals("Длиннее 3 минут пока не поддерживается", AudioValidator.validateDuration(180_001));
        assertEquals("Длиннее 3 минут пока не поддерживается", AudioValidator.validateDuration(600_000));
    }
}
