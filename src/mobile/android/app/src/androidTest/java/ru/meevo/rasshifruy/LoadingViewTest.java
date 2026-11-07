package ru.meevo.rasshifruy;

import static org.junit.Assert.assertTrue;

import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Проверка анимации загрузки: во время подготовки модели (PREPARING) в поле
 * вывода должен показываться loadingView с логотипом-микрофоном.
 */
@RunWith(AndroidJUnit4.class)
public class LoadingViewTest {

    @Test
    public void loadingAnimation_isShownWhileModelPrepares() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            final boolean[] sawLoading = {false};
            long deadline = System.currentTimeMillis() + 25_000;
            while (System.currentTimeMillis() < deadline && !sawLoading[0]) {
                Thread.sleep(100);
                scenario.onActivity(activity -> {
                    View loading = activity.findViewById(R.id.loadingView);
                    if (loading != null && loading.getVisibility() == View.VISIBLE) {
                        sawLoading[0] = true;
                    }
                });
            }
            assertTrue("Анимация загрузки должна показываться во время подготовки модели", sawLoading[0]);
        }
    }
}
