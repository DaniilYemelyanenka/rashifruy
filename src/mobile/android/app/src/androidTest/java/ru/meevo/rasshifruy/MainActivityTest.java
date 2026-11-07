package ru.meevo.rasshifruy;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Дымовой UI-тест этапа B: каркас экрана отображается корректно. */
@RunWith(AndroidJUnit4.class)
public class MainActivityTest {

    @Test
    public void screenSkeleton_isDisplayed() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.title)).check(matches(isDisplayed()));
            onView(withId(R.id.subtitle)).check(matches(isDisplayed()));
            onView(withId(R.id.btnAddFile)).check(matches(isDisplayed()));
            onView(withId(R.id.btnMic)).check(matches(isDisplayed()));
            onView(withId(R.id.status)).check(matches(isDisplayed()));
            onView(withId(R.id.resultsScroll)).check(matches(isDisplayed()));
            onView(withId(R.id.btnExport)).check(matches(isDisplayed()));
        }
    }
}
