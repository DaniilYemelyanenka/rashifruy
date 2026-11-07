package ru.meevo.rasshifruy;

import android.app.Application;
import android.util.Log;

import ru.meevo.rasshifruy.pipeline.PipelineManager;

/**
 * Точка входа приложения.
 *
 * Зачем: пайплайн распознавания живёт в Application-scoped синглтоне и должен
 * существовать с момента старта процесса, независимо от жизненного цикла Activity.
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d("GigaAMTest", "App.onCreate: инициализация синглтона пайплайна");
        PipelineManager.init(this);
    }
}
