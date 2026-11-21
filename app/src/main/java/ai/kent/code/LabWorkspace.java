package ai.kent.code;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

/** Independent, app-private copies of the source snapshot packaged by Gradle. */
final class LabWorkspace {
    private static final String ASSETS = "lab-source";

    private LabWorkspace() {}

    static String create(Context context) throws IOException {
        AssetManager assets = context.getAssets();
        String[] children = assets.list(ASSETS);
        if (children == null || children.length == 0) throw new IOException("В APK нет снимка исходного кода.");
        String id = UUID.randomUUID().toString();
        SafeWorkspace workspace = new SafeWorkspace(directory(context, id));
        try {
            for (String name : children) copy(assets, workspace, name);
        } catch (IOException e) {
            try (Stream<java.nio.file.Path> files = Files.walk(workspace.root().toPath())) {
                for (java.nio.file.Path file : files.sorted(Comparator.reverseOrder())
                        .toArray(java.nio.file.Path[]::new)) Files.deleteIfExists(file);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        return id;
    }

    private static void copy(AssetManager assets, SafeWorkspace workspace, String target) throws IOException {
        String source = ASSETS + "/" + target;
        String[] children = assets.list(source);
        if (children != null && children.length > 0) {
            for (String child : children) copy(assets, workspace, target + "/" + child);
            return;
        }
        File file = workspace.resolve(target);
        Files.createDirectories(file.getParentFile().toPath());
        try (InputStream in = assets.open(source)) {
            Files.copy(in, file.toPath());
        }
    }

    static SafeWorkspace open(Context context, String id) throws IOException {
        File path = directory(context, id);
        if (!path.isDirectory()) throw new IOException("Копия KENT LAB недоступна.");
        return new SafeWorkspace(path);
    }

    private static File directory(Context context, String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
            throw new IOException("Недопустимая рабочая копия KENT LAB.");
        return new File(new File(context.getFilesDir(), "kent-lab"), id);
    }
}
