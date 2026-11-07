package ru.meevo.rasshifruy.format;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Unit-тесты единого форматтера времени mm:ss.ss. */
public class TimeFormatterTest {

    @Test
    public void zeroMilliseconds() {
        assertEquals("00:00.00", TimeFormatter.format(0));
    }

    @Test
    public void belowOneSecond() {
        assertEquals("00:01.25", TimeFormatter.format(1250));
    }

    @Test
    public void seconds() {
        assertEquals("00:04.38", TimeFormatter.format(4380));
    }

    @Test
    public void minuteAndSeconds() {
        assertEquals("01:05.23", TimeFormatter.format(65230));
    }

    @Test
    public void negativeIsClampedToZero() {
        assertEquals("00:00.00", TimeFormatter.format(-100));
    }

    @Test
    public void rangeFormat() {
        assertEquals("00:01.25 – 00:04.38", TimeFormatter.formatRange(1250, 4380));
    }

    @Test
    public void clockFormat() {
        assertEquals("00:00", TimeFormatter.formatClock(0));
        assertEquals("00:01", TimeFormatter.formatClock(1250));
        assertEquals("01:05", TimeFormatter.formatClock(65230));
    }
}
