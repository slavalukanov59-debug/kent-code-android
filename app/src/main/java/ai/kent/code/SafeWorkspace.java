package ai.kent.code;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Only app-private project files. This is not a sandbox for arbitrary shell commands. */
public final class SafeWorkspace {
    public static final int MAX_READ_BYTES = 120_000;
    public static final int MAX_WRITE_BYTES = 250_000;
    private final File root;

    public SafeWorkspace(File rootDir) throws IOException {
        if (!rootDir.exists() && !rootDir.mkdirs()) throw new IOException("Не удалось создать рабочую папку");
        root = rootDir.getCanonicalFile();
    }

    public File root() { return root; }

    public File resolve(String path) throws IOException {
        if (path == null || path.trim().isEmpty() || path.startsWith("/") || path.contains("\\") || path.indexOf('\u0000') != -1) {
            throw new IOException("Нужен относительный путь внутри рабочего каталога");
        }
        for (int i = 0; i < path.length(); i++) {
            if (Character.isISOControl(path.charAt(i))) throw new IOException("Недопустимый символ в пути");
        }
        Path target = root.toPath();
        for (String component : path.split("/", -1)) {
            if (component.isEmpty() || ".".equals(component) || "..".equals(component)) {
                throw new IOException("Нужен относительный путь внутри рабочего каталога");
            }
            target = target.resolve(component);
            if (Files.isSymbolicLink(target)) throw new IOException("Символические ссылки недоступны");
        }
        return target.toFile();
    }

    private void rejectSecret(String path) throws IOException {
        String p = "/" + path.toLowerCase(Locale.ROOT).replace('\\', '/');
        String name = p.substring(p.lastIndexOf('/') + 1);
        if (name.equals(".env") || name.startsWith(".env.") || name.equals(".npmrc") ||
            name.equals(".netrc") || name.equals("credentials.json") || name.equals("secrets.json") ||
            name.equals("id_rsa") || name.equals("id_ed25519") || name.equals("local.properties") ||
            name.endsWith(".pem") || name.endsWith(".key") || name.endsWith(".jks") ||
            name.endsWith(".keystore") || name.endsWith(".p12") || name.endsWith(".pfx") ||
            p.contains("/.git/") || p.contains("/.gradle/") || name.equals(".git") ||
            name.equals(".gradle") || name.startsWith(".kent-")) {
            throw new IOException("Файл с секретами/служебными данными не доступен агенту");
        }
    }

    public synchronized String read(String path) throws IOException {
        File file = resolve(path);
        rejectSecret(path);
        if (!file.isFile()) throw new IOException("Файл не существует: " + path);
        if (file.length() > MAX_READ_BYTES) throw new IOException("Слишком большой файл для чтения (>120 KB)");
        byte[] bytes = Files.readAllBytes(file.toPath());
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IOException("Доступны текстовые файлы UTF-8");
        }
    }

    public String readPreview(String path) throws IOException { return read(path); }

    public synchronized void write(String path, String content) throws IOException {
        File file = resolve(path);
        rejectSecret(path);
        if (file.exists() && !file.isFile()) throw new IOException("Путь занят папкой");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_WRITE_BYTES) throw new IOException("Слишком большой файл (>250 KB)");
        File parent = file.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) throw new IOException("Не удалось создать папку");
        // Avoid partially written files when possible.
        File temp = File.createTempFile(".kent-", ".tmp", parent);
        try {
            Files.write(temp.toPath(), bytes);
            try {
                Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            temp.delete();
        }
    }

    synchronized String currentText(String path) throws IOException {
        File file = resolve(path);
        rejectSecret(path);
        return file.exists() ? read(path) : null;
    }

    synchronized boolean applyReviewedChange(String path, String before, String after) throws IOException {
        if (!Objects.equals(currentText(path), before)) return false;
        if (after == null) delete(path);
        else write(path, after);
        return true;
    }

    public synchronized void delete(String path) throws IOException {
        File f = resolve(path);
        rejectSecret(path);
        if (!f.isFile()) throw new IOException("Можно удалять только обычные файлы");
        Files.delete(f.toPath());
    }

    public String editPreview(String path, String oldText, String newText, boolean replaceAll) throws IOException {
        String current = read(path);
        if (oldText == null || oldText.isEmpty() || !current.contains(oldText)) {
            throw new IOException("Исходный фрагмент не найден: изменения не применены");
        }
        return replaceAll ? current.replace(oldText, newText) : current.replaceFirst(
                java.util.regex.Pattern.quote(oldText), java.util.regex.Matcher.quoteReplacement(newText));
    }

    public String tree() throws IOException {
        StringBuilder sb = new StringBuilder();
        int[] total = {0};
        walk(root, "", sb, total, 0);
        if (sb.length() == 0) return "Пока пусто. Создай файл или импортируй его.";
        if (total[0] >= 240) sb.append("\n…список ограничен первыми 240 элементами");
        return sb.toString();
    }

    private void walk(File dir, String prefix, StringBuilder sb, int[] count, int depth) throws IOException {
        if (depth > 8 || count[0] >= 240) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File file : files) {
            if (count[0] >= 240) break;
            String name = file.getName();
            if (name.equals(".git") || name.equals(".gradle") || name.equals("node_modules") || name.equals("build")) continue;
            String relative = prefix + name;
            if (Files.isSymbolicLink(file.toPath())) continue;
            try { rejectSecret(relative); } catch (IOException e) { continue; }
            sb.append(relative).append(file.isDirectory() ? "/" : "").append('\n');
            count[0]++;
            if (file.isDirectory()) walk(file, relative + "/", sb, count, depth + 1);
        }
    }

    public List<String> filePaths() throws IOException {
        List<String> list = new ArrayList<>();
        String tree = tree();
        if (tree.startsWith("Пока пусто.")) return list;
        for (String l : tree.split("\n")) {
            if (!l.trim().isEmpty() && !l.endsWith("/") && !l.startsWith("…")) list.add(l);
        }
        return list;
    }

    public String search(String query) throws IOException {
        if (query == null || query.trim().isEmpty()) return "Пустой запрос";
        String needle = query.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        int found = 0;
        for (String path : filePaths()) {
            String content;
            try { content = read(path); } catch (IOException ignored) { continue; }
            String[] lines = content.split("\n", -1);
            for (int i = 0; i < lines.length && found < 40; i++) {
                if (lines[i].toLowerCase(Locale.ROOT).contains(needle)) {
                    out.append(path).append(':').append(i + 1).append(": ")
                       .append(lines[i], 0, Math.min(180, lines[i].length())).append('\n');
                    found++;
                }
            }
            if (found >= 40) break;
        }
        return out.length() == 0 ? "Ничего не найдено" : out.toString();
    }
}
