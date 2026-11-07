package ru.meevo.rasshifruy.recognition;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Копирует файлы моделей из assets в приватный каталог filesDir при первом запуске.
 * sherpa-onnx умеет читать модели только из реальных путей ФС, поэтому ONNX-файлы
 * не используются напрямую из assets.
 */
public final class ModelFiles {

    private static final String TAG = "GigaAMTest";
    private static final String GIGAAM_DIR = "gigaam";
    private static final String DIARIZATION_DIR = "diarization";
    private static final String COPY_MARKER = ".copied";

    /** Абсолютные пути к файлам модели ASR после копирования. */
    public static final class Paths {
        public final String encoder;
        public final String decoder;
        public final String joiner;
        public final String tokens;

        Paths(String encoder, String decoder, String joiner, String tokens) {
            this.encoder = encoder;
            this.decoder = decoder;
            this.joiner = joiner;
            this.tokens = tokens;
        }
    }

    /** Абсолютные пути к файлам модели диаризации. */
    public static final class DiarizationPaths {
        public final String segmentation;
        public final String embedding;

        DiarizationPaths(String segmentation, String embedding) {
            this.segmentation = segmentation;
            this.embedding = embedding;
        }
    }

    private ModelFiles() {}

    public static Paths prepare(Context context) throws IOException {
        File dir = copyAssets(context, GIGAAM_DIR);
        String encoder = findFile(dir, "encoder.onnx", "encoder.int8.onnx");
        String decoder = findFile(dir, "decoder.onnx", "decoder.int8.onnx");
        String joiner = findFile(dir, "joiner.onnx", "joiner.int8.onnx");
        String tokens = findFile(dir, "tokens.txt");
        if (encoder == null || decoder == null || joiner == null || tokens == null) {
            throw new IOException("В " + dir.getAbsolutePath()
                    + " не хватает файлов модели (encoder/decoder/joiner/tokens.txt)");
        }
        return new Paths(encoder, decoder, joiner, tokens);
    }

    public static DiarizationPaths prepareDiarization(Context context) throws IOException {
        File dir = copyAssets(context, DIARIZATION_DIR);
        String segmentation = findFile(dir, "segmentation.onnx");
        String embedding = findFile(dir, "embedding.onnx");
        if (segmentation == null || embedding == null) {
            throw new IOException("В " + dir.getAbsolutePath()
                    + " не хватает файлов диаризации (segmentation.onnx, embedding.onnx)");
        }
        return new DiarizationPaths(segmentation, embedding);
    }

    /** Копирует assets/<assetDir>/*.onnx (и tokens.txt) в filesDir/<assetDir> с маркером. */
    private static File copyAssets(Context context, String assetDir) throws IOException {
        File dir = new File(context.getFilesDir(), assetDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Не удалось создать каталог модели: " + dir.getAbsolutePath());
        }

        String[] assetNames = context.getAssets().list(assetDir);
        if (assetNames == null || assetNames.length == 0) {
            throw new IOException("Папка assets/" + assetDir + " пуста");
        }

        // Копируем только файлы модели, пояснительные файлы (README) не нужны.
        List<String> modelFiles = new ArrayList<>();
        for (String name : assetNames) {
            if (name.endsWith(".onnx") || "tokens.txt".equals(name)) {
                modelFiles.add(name);
            }
        }
        if (modelFiles.isEmpty()) {
            throw new IOException("В assets/" + assetDir + " нет файлов модели (.onnx, tokens.txt)");
        }

        File marker = new File(dir, COPY_MARKER);
        if (marker.exists() && hasAllFiles(dir, modelFiles)) {
            return dir;
        }

        for (String name : modelFiles) {
            File target = new File(dir, name);
            if (target.isDirectory()) {
                continue;
            }
            try (InputStream in = context.getAssets().open(assetDir + "/" + name);
                 FileOutputStream out = new FileOutputStream(target, false)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            Log.d(TAG, "Ассет модели скопирован: " + assetDir + "/" + name + " (" + target.length() + " байт)");
        }

        if (!marker.exists() && !marker.createNewFile()) {
            Log.w(TAG, "Не удалось создать маркер " + marker.getAbsolutePath());
        }
        return dir;
    }

    private static String findFile(File dir, String... candidates) {
        for (String candidate : candidates) {
            File file = new File(dir, candidate);
            if (file.isFile() && file.length() > 0) {
                return file.getAbsolutePath();
            }
        }
        return null;
    }

    private static boolean hasAllFiles(File dir, List<String> names) {
        for (String name : names) {
            File file = new File(dir, name);
            if (!file.isFile() || file.length() == 0) {
                return false;
            }
        }
        return true;
    }
}
