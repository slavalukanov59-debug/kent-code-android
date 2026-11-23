package ai.kent.code;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
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
    static final int MAX_PROJECT_EDIT_BYTES = 8 * 1024 * 1024;
    private final File root;
    private final Path exportAnchor;
    private final Path exportLocation;

    public SafeWorkspace(File rootDir) throws IOException {
        if (!rootDir.exists() && !rootDir.mkdirs()) throw new IOException("Не удалось создать рабочую папку");
        root = rootDir.getCanonicalFile();
        Path location = rootDir.getAbsoluteFile().toPath().normalize();
        Path anchor = location;
        for (int i = 0; i < 2 && anchor.getParent() != null; i++) anchor = anchor.getParent();
        // Resolve Android's system-managed aliases above the app's private data directory.
        exportAnchor = anchor.toFile().getCanonicalFile().toPath();
        exportLocation = anchor.relativize(location);
    }

    public File root() { return root; }

    Path exportAnchor() { return exportAnchor; }

    Path exportLocation() { return exportLocation; }

    public File resolve(String path) throws IOException {
        if (path == null || path.trim().isEmpty() || path.startsWith("/") || path.contains("\\") || path.indexOf('\u0000') != -1) {
            throw new IOException("Нужен относительный путь внутри рабочего каталога");
        }
        for (int i = 0; i < path.length(); i++) {
            if (Character.isISOControl(path.charAt(i))) throw new IOException("Недопустимый символ в пути");
        }
        Path target = root.toPath();
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Рабочая папка недоступна");
        for (String component : path.split("/", -1)) {
            if (component.isEmpty() || ".".equals(component) || "..".equals(component)) {
                throw new IOException("Нужен относительный путь внутри рабочего каталога");
            }
            target = target.resolve(component);
            if (Files.isSymbolicLink(target)) throw new IOException("Символические ссылки недоступны");
        }
        return target.toFile();
    }

    File projectPath(String path) throws IOException {
        requireProjectPath(path);
        return resolve(path);
    }

    static void requireProjectPath(String path) throws IOException {
        if (path == null || path.isEmpty()) throw new IOException("Выберите путь внутри рабочей папки");
        for (String name : path.split("/", -1)) {
            if (!safeComponent(name)) throw new IOException("Недоступный путь внутри рабочей папки: " + path);
        }
    }

    static boolean validComponent(String name) {
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) if (Character.isISOControl(name.charAt(i))) return false;
        return true;
    }

    static boolean safeComponent(String name) {
        if (!validComponent(name)) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return !isCredentialName(lower) && !lower.equals(".git") && !lower.equals(".gradle") &&
                !lower.equals("kent_settings.xml") && !lower.equals("kent_settings.xml.bak") &&
                !lower.equals("kent_settings.xml.new") && !lower.startsWith(".kent-");
    }

    static boolean isCredentialName(String name) {
        return name.equals(".env") || name.startsWith(".env.") || name.equals(".npmrc") ||
            name.equals(".netrc") || name.equals("credentials.json") || name.equals("secrets.json") ||
            name.equals("id_rsa") || name.equals("id_ed25519") || name.equals("local.properties") ||
            name.endsWith(".pem") || name.endsWith(".key") || name.endsWith(".jks") ||
            name.endsWith(".keystore") || name.endsWith(".p12") || name.endsWith(".pfx");
    }

    public synchronized String read(String path) throws IOException {
        return read(path, MAX_READ_BYTES);
    }

    private String read(String path, int limit) throws IOException {
        Path file = projectPath(path).toPath();
        BasicFileAttributes attrs = requireSingleFile(file);
        if (attrs.size() > limit) throw new IOException("Файл превышает предел одной текстовой операции: " + limit + " байт");
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) attrs.size());
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > limit - output.size()) throw new IOException("Файл вырос во время чтения");
                output.write(buffer, 0, read);
            }
        }
        BasicFileAttributes after = requireSingleFile(file);
        if (!Objects.equals(after.fileKey(), attrs.fileKey()) || !after.lastModifiedTime().equals(attrs.lastModifiedTime()) ||
                after.size() != attrs.size() || output.size() != attrs.size()) {
            throw new IOException("Файл изменился во время чтения. Повторите операцию.");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IOException("Доступны текстовые файлы UTF-8");
        }
    }

    public String readPreview(String path) throws IOException { return read(path); }

    public synchronized void write(String path, String content) throws IOException {
        write(path, content, MAX_WRITE_BYTES);
    }

    private void write(String path, String content, int limit) throws IOException {
        File file = projectPath(path);
        if (file.exists()) requireSingleFile(file.toPath());
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limit) throw new IOException("Файл превышает предел одной записи: " + limit + " байт");
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
        return currentText(path, MAX_READ_BYTES);
    }

    private String currentText(String path, int limit) throws IOException {
        return projectPath(path).exists() ? read(path, limit) : null;
    }

    synchronized String projectCurrentText(String path) throws IOException {
        return currentText(path, MAX_PROJECT_EDIT_BYTES);
    }

    synchronized boolean applyProjectText(String path, String before, String after) throws IOException {
        if (!Objects.equals(projectCurrentText(path), before)) return false;
        write(path, after, MAX_PROJECT_EDIT_BYTES);
        return true;
    }

    synchronized boolean applyReviewedChange(String path, String before, String after) throws IOException {
        if (!Objects.equals(currentText(path), before)) return false;
        if (after == null) delete(path);
        else write(path, after);
        return true;
    }

    public synchronized void delete(String path) throws IOException {
        Path file = projectPath(path).toPath();
        requireSingleFile(file);
        Files.delete(file);
    }

    private static BasicFileAttributes requireSingleFile(Path file) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || !Integer.valueOf(1).equals(Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Доступны только обычные файлы без ссылок");
        }
        return attrs;
    }

    public String editPreview(String path, String oldText, String newText, boolean replaceAll) throws IOException {
        return replaceFragment(read(path), oldText, newText, replaceAll);
    }

    static String replaceFragment(String current, String oldText, String newText, boolean replaceAll) throws IOException {
        if (current == null || oldText == null || oldText.isEmpty() || !current.contains(oldText)) {
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
            if (!safeComponent(name)) continue;
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

    public static final class TextPage {
        public final String text, revision;
        public final long nextOffset, bytes;
        public final boolean eof;

        TextPage(String text, long nextOffset, boolean eof, long bytes, String revision) {
            this.text = text;
            this.nextOffset = nextOffset;
            this.eof = eof;
            this.bytes = bytes;
            this.revision = revision;
        }
    }
}
