package ai.kent.code;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Read-only access to project files and verified ZIP staging in the app's cache. */
public final class WorkspaceZipExporter {
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final Set<OpenOption> READ_NOFOLLOW = new HashSet<>(Arrays.asList(
            StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
    private static final Set<OpenOption> CREATE_STAGE = new HashSet<>(Arrays.asList(
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS));
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path anchor;
    private final Path workspaceLocation;
    private final Path root;
    private final Path currentDirectory;
    private final Path emptyPath;

    public WorkspaceZipExporter(SafeWorkspace workspace) {
        anchor = workspace.exportAnchor();
        workspaceLocation = workspace.exportLocation();
        root = anchor.resolve(workspaceLocation);
        currentDirectory = root.getFileSystem().getPath(".");
        emptyPath = root.getFileSystem().getPath("");
    }

    public static final class Entry {
        public final String name;
        public final String relativePath;
        public final boolean directory;
        public final long bytes;

        private Entry(Source source) {
            name = source.path.getFileName().toString();
            relativePath = source.path.toString();
            directory = source.attributes.isDirectory();
            bytes = directory ? 0 : source.attributes.size();
        }
    }

    public static final class Archive implements AutoCloseable {
        public final File file;
        public final String suggestedName;
        public final long fileCount;
        public final long availableFileCount;
        public final long bytes;
        public final String sha256;

        Archive(File file, String suggestedName, long fileCount, long availableFileCount,
                long bytes, String sha256) {
            this.file = file;
            this.suggestedName = suggestedName;
            this.fileCount = fileCount;
            this.availableFileCount = availableFileCount;
            this.bytes = bytes;
            this.sha256 = sha256;
        }

        /** Checks that the saved document matches the validated staging archive byte for byte. */
        public void verifyCopy(InputStream input) throws IOException {
            checkInterrupted();
            MessageDigest digest = sha256Digest();
            long remaining = bytes;
            byte[] buffer = new byte[BUFFER_BYTES];
            int read;
            while ((read = input.read(buffer)) != -1) {
                checkInterrupted();
                if (read > remaining) throw new IOException("Размер сохранённого ZIP отличается от ожидаемого");
                digest.update(buffer, 0, read);
                remaining -= read;
            }
            if (remaining != 0 || !toHex(digest.digest()).equals(sha256)) {
                throw new IOException("Содержимое сохранённого ZIP отличается от проверенного архива");
            }
        }

        @Override public void close() throws IOException {
            Files.deleteIfExists(file.toPath());
        }
    }

    private static final class Source {
        final Path path;
        final Path absolute;
        final BasicFileAttributes attributes;

        Source(Path path, Path absolute, BasicFileAttributes attributes) {
            this.path = path;
            this.absolute = absolute;
            this.attributes = attributes;
        }

        String zipName() {
            return attributes.isDirectory() ? path + "/" : path.toString();
        }
    }

    public List<Entry> listDirectory(String relativePath) throws IOException {
        Path relative = checkedPath(relativePath);
        List<Entry> entries = new ArrayList<>();
        try (SecureDirectoryStream<Path> workspace = openWorkspace();
             SecureDirectoryStream<Path> directory = openDirectory(workspace, relative)) {
            for (Source source : children(directory, relative, root.resolve(relative), Collections.emptyList())) {
                entries.add(new Entry(source));
            }
        }
        entries.sort(Comparator.comparing((Entry entry) -> !entry.directory)
                .thenComparing(entry -> entry.name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(entry -> entry.name));
        return entries;
    }

    public long directorySize(String relativePath) throws IOException {
        Path relative = checkedPath(relativePath);
        try (SecureDirectoryStream<Path> workspace = openWorkspace();
             SecureDirectoryStream<Path> directory = openDirectory(workspace, relative)) {
            return snapshot(directory, root.resolve(relative), Collections.emptyList()).values().stream()
                    .filter(source -> source.attributes.isRegularFile())
                    .mapToLong(source -> source.attributes.size()).sum();
        }
    }

    public void requireFile(String relativePath) throws IOException {
        Path path = checkedFilePath(relativePath);
        try (SecureDirectoryStream<Path> workspace = openWorkspace();
             SecureDirectoryStream<Path> directory = openDirectory(workspace, parentOf(path))) {
            requireRegularFile(directory, path.getFileName(), root.resolve(path));
        }
    }

    public byte[] readPreview(String relativePath) throws IOException {
        Path path = checkedFilePath(relativePath);
        Path name = path.getFileName();
        try (SecureDirectoryStream<Path> workspace = openWorkspace();
             SecureDirectoryStream<Path> directory = openDirectory(workspace, parentOf(path))) {
            BasicFileAttributes attributes = requireRegularFile(directory, name, root.resolve(path));
            if (attributes.size() > SafeWorkspace.MAX_READ_BYTES) throw previewSizeError();
            try (InputStream input = openFile(directory, name)) {
                ByteArrayOutputStream output = new ByteArrayOutputStream((int) attributes.size());
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkInterrupted();
                    if (output.size() + read > SafeWorkspace.MAX_READ_BYTES) throw previewSizeError();
                    output.write(buffer, 0, read);
                }
                if (!sameRevision(attributes, requireRegularFile(directory, name, root.resolve(path)))) {
                    throw workspaceChanged();
                }
                return output.toByteArray();
            }
        }
    }

    public SafeWorkspace.TextPage readTextPage(String relativePath, long offset, int maxChars) throws IOException {
        if (offset < 0 || maxChars < 1 || maxChars > 128 * 1024) throw new IOException("Недопустимые параметры страницы текста");
        Path path = checkedFilePath(relativePath);
        Path name = path.getFileName();
        Path absolute = root.resolve(path);
        try (SecureDirectoryStream<Path> workspace = openWorkspace();
             SecureDirectoryStream<Path> directory = openDirectory(workspace, parentOf(path))) {
            BasicFileAttributes attrs = requireRegularFile(directory, name, absolute);
            if (offset > attrs.size()) throw workspaceChanged();
            ByteBuffer input = ByteBuffer.allocate((int) Math.min(attrs.size() - offset, maxChars * 4L + 4));
            try (SeekableByteChannel file = directory.newByteChannel(name, READ_NOFOLLOW)) {
                file.position(offset);
                while (input.hasRemaining()) {
                    checkInterrupted();
                    if (file.read(input) < 0) throw workspaceChanged();
                }
            }
            input.flip();
            CharBuffer text = CharBuffer.allocate(maxChars + 1);
            text.limit(maxChars);
            boolean lastChunk = offset + input.remaining() == attrs.size();
            try {
                var decoder = StandardCharsets.UTF_8.newDecoder();
                CoderResult status = decoder.decode(input, text, lastChunk);
                if (status.isOverflow() && text.remaining() == 1) {
                    // A supplementary code point needs two UTF-16 code units and stays on one page.
                    text.limit(maxChars + 1);
                    status = decoder.decode(input, text, lastChunk);
                }
                if (status.isError()) status.throwException();
            } catch (java.nio.charset.CharacterCodingException e) {
                throw new IOException("Доступны страницы текста UTF-8. Используйте позицию следующей страницы.", e);
            }
            if (!sameRevision(attrs, requireRegularFile(directory, name, absolute))) throw workspaceChanged();
            text.flip();
            String output = text.toString();
            if (output.indexOf('\u0000') >= 0) throw new IOException("Доступны текстовые файлы UTF-8");
            long nextOffset = offset + input.position();
            String revision = attrs.fileKey() + ":" + attrs.lastModifiedTime() + ":" + attrs.size();
            return new SafeWorkspace.TextPage(output, nextOffset, nextOffset == attrs.size(), attrs.size(), revision);
        }
    }

    private static IOException previewSizeError() {
        return new IOException("Слишком большой файл для просмотра (>120 KB)");
    }

    public String suggestedName(String relativePath) throws IOException {
        Path relative = checkedPath(relativePath);
        return relative.toString().isEmpty() ? "workspace.zip" : relative.getFileName() + ".zip";
    }

    public Archive stage(String relativePath, File cacheDir, String... excludedValues) throws IOException {
        checkInterrupted();
        Path relative = checkedPath(relativePath);
        Path cache = cacheDir.getCanonicalFile().toPath();
        if (cache.startsWith(root)) {
            throw new IOException("Для ZIP нужна временная папка вне рабочей области");
        }
        List<SecretPattern> secrets = new ArrayList<>();
        for (String value : excludedValues) {
            if (value != null && !value.isEmpty()) secrets.add(new SecretPattern(value));
        }
        byte[] buffer = new byte[BUFFER_BYTES];
        Path staged = null;
        try (SecureDirectoryStream<Path> workspace = openWorkspace();
             SecureDirectoryStream<Path> selected = openDirectory(workspace, relative)) {
            String name = suggestedName(relativePath);
            if (containsSecret(name, secrets)) name = "workspace.zip";
            Path selectedPath = root.resolve(relative);
            BasicFileAttributes selectedAttributes = directoryAttributes(selected);
            Map<String, Source> sources = snapshot(selected, selectedPath, secrets);
            Map<String, Source> included = new LinkedHashMap<>();
            long available = 0;
            for (Source source : sources.values()) {
                if (source.attributes.isDirectory()) {
                    included.put(source.zipName(), source);
                } else if (secrets.isEmpty() || transfer(selected, source, buffer, secrets, null) >= 0) {
                    included.put(source.zipName(), source);
                    available++;
                }
            }
            Files.createDirectories(cache);
            Path candidate = cache.resolve("kent-workspace-" + (RANDOM.nextLong() & Long.MAX_VALUE) + ".zip");
            String sha256;
            try (OutputStream output = Channels.newOutputStream(Files.newByteChannel(candidate, CREATE_STAGE,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))))) {
                staged = candidate;
                sha256 = writeArchive(output, selected, included, secrets, buffer);
            }
            long bytes = Files.size(staged);
            long files = verifyZip(staged, included, buffer);
            if (files != available) throw new IOException("Количество файлов в ZIP не совпадает с папкой");
            ensureUnchanged(relative, selectedAttributes, sources, secrets);
            Archive archive = new Archive(staged.toFile(), name, files, available, bytes, sha256);
            try (InputStream input = Files.newInputStream(staged, LinkOption.NOFOLLOW_LINKS)) {
                archive.verifyCopy(input);
            }
            return archive;
        } catch (IOException | RuntimeException | Error failure) {
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        } finally {
            Arrays.fill(buffer, (byte) 0);
            for (SecretPattern secret : secrets) Arrays.fill(secret.bytes, (byte) 0);
        }
    }

    private Path checkedPath(String input) throws IOException {
        if (input == null) throw new IOException("Нужен относительный путь внутри рабочей папки");
        if (input.isEmpty()) return emptyPath;
        for (String name : input.split("/", -1)) {
            if (!safeName(name)) throw new IOException("Недоступный путь внутри рабочей папки");
        }
        return root.getFileSystem().getPath(input);
    }

    private Path checkedFilePath(String input) throws IOException {
        Path path = checkedPath(input);
        if (path.toString().isEmpty()) throw new IOException("Выберите файл в рабочей папке");
        return path;
    }

    private Path parentOf(Path path) {
        Path parent = path.getParent();
        return parent == null ? emptyPath : parent;
    }

    private static boolean safeName(String name) {
        return SafeWorkspace.safeComponent(name);
    }

    private SecureDirectoryStream<Path> openWorkspace() throws IOException {
        BasicFileAttributes expected = Files.readAttributes(anchor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!expected.isDirectory()) throw workspaceChanged();
        DirectoryStream<Path> stream = Files.newDirectoryStream(anchor);
        if (!(stream instanceof SecureDirectoryStream)) {
            stream.close();
            throw new IOException("Файловая система не поддерживает безопасный экспорт ZIP");
        }
        try (SecureDirectoryStream<Path> filesystem = (SecureDirectoryStream<Path>) stream) {
            if (!Objects.equals(expected.fileKey(), directoryAttributes(filesystem).fileKey())) {
                throw workspaceChanged();
            }
            return openDirectory(filesystem, workspaceLocation);
        }
    }

    private SecureDirectoryStream<Path> openDirectory(SecureDirectoryStream<Path> start, Path path)
            throws IOException {
        // Open each component relative to an open parent. A path check alone can race a symlink swap.
        SecureDirectoryStream<Path> current = start.newDirectoryStream(currentDirectory, LinkOption.NOFOLLOW_LINKS);
        try {
            for (Path name : path) {
                if (name.toString().isEmpty()) continue;
                checkInterrupted();
                SecureDirectoryStream<Path> next = current.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS);
                SecureDirectoryStream<Path> previous = current;
                current = next;
                previous.close();
            }
            return current;
        } catch (IOException | RuntimeException failure) {
            try {
                current.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static BasicFileAttributes directoryAttributes(SecureDirectoryStream<Path> directory)
            throws IOException {
        return directory.getFileAttributeView(BasicFileAttributeView.class).readAttributes();
    }

    private static BasicFileAttributes readAttributes(SecureDirectoryStream<Path> directory, Path name)
            throws IOException {
        return directory.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                .readAttributes();
    }

    private static BasicFileAttributes requireRegularFile(SecureDirectoryStream<Path> directory,
            Path name, Path absolute) throws IOException {
        BasicFileAttributes attributes = readAttributes(directory, name);
        if (!attributes.isRegularFile() || !singleLink(absolute, attributes)) {
            throw new IOException("Недоступный файл в рабочей папке");
        }
        return attributes;
    }

    private static boolean singleLink(Path path, BasicFileAttributes anchored) throws IOException {
        // Do not export aliases to private files outside the workspace. The identity check binds
        // the pathname's Unix link count to the entry inspected through the secure parent handle.
        Map<String, Object> attributes = Files.readAttributes(path, "unix:fileKey,nlink", LinkOption.NOFOLLOW_LINKS);
        if (anchored.fileKey() == null || !anchored.fileKey().equals(attributes.get("fileKey"))) {
            throw workspaceChanged();
        }
        return Integer.valueOf(1).equals(attributes.get("nlink"));
    }

    private static InputStream openFile(SecureDirectoryStream<Path> directory, Path name) throws IOException {
        return Channels.newInputStream(directory.newByteChannel(name, READ_NOFOLLOW));
    }

    private static List<Source> children(SecureDirectoryStream<Path> directory, Path parent,
            Path absoluteParent, List<SecretPattern> secrets) throws IOException {
        List<Source> children = new ArrayList<>();
        try {
            for (Path child : directory) {
                checkInterrupted();
                Path name = child.getFileName();
                String filename = name.toString();
                if (!safeName(filename) || containsSecret(filename, secrets)) continue;
                BasicFileAttributes attributes = readAttributes(directory, name);
                Path absolute = absoluteParent.resolve(name);
                if (attributes.isDirectory() || attributes.isRegularFile() && singleLink(absolute, attributes)) {
                    children.add(new Source(parent.resolve(name), absolute, attributes));
                }
            }
        } catch (DirectoryIteratorException error) {
            throw error.getCause();
        }
        return children;
    }

    private Map<String, Source> snapshot(SecureDirectoryStream<Path> selected,
            Path absolute, List<SecretPattern> secrets) throws IOException {
        Map<String, Source> sources = new LinkedHashMap<>();
        ArrayDeque<Path> pending = new ArrayDeque<>();
        pending.push(emptyPath);
        while (!pending.isEmpty()) {
            checkInterrupted();
            Path path = pending.pop();
            try (SecureDirectoryStream<Path> directory = openDirectory(selected, path)) {
                for (Source source : children(directory, path, absolute.resolve(path), secrets)) {
                    if (sources.put(source.zipName(), source) != null) {
                        throw new IOException("Совпадающие имена файлов в ZIP");
                    }
                    if (source.attributes.isDirectory()) pending.push(source.path);
                }
            }
        }
        return sources;
    }

    private void ensureUnchanged(Path relative, BasicFileAttributes before,
            Map<String, Source> sources, List<SecretPattern> secrets) throws IOException {
        try (SecureDirectoryStream<Path> workspace = openWorkspace();
             SecureDirectoryStream<Path> selected = openDirectory(workspace, relative)) {
            if (!sameRevision(before, directoryAttributes(selected))) throw workspaceChanged();
            Map<String, Source> current = snapshot(selected, root.resolve(relative), secrets);
            if (!current.keySet().equals(sources.keySet())) throw workspaceChanged();
            for (Map.Entry<String, Source> entry : current.entrySet()) {
                checkInterrupted();
                BasicFileAttributes expected = sources.get(entry.getKey()).attributes;
                if (!sameRevision(expected, entry.getValue().attributes)) throw workspaceChanged();
            }
        }
    }

    private static boolean sameRevision(BasicFileAttributes first, BasicFileAttributes second) {
        return first.isDirectory() == second.isDirectory() && first.isRegularFile() == second.isRegularFile() &&
                Objects.equals(first.fileKey(), second.fileKey()) && first.size() == second.size() &&
                first.lastModifiedTime().equals(second.lastModifiedTime());
    }

    private static IOException workspaceChanged() {
        return new IOException("Выбранная папка изменилась во время экспорта. Повторите попытку");
    }

    private long transfer(SecureDirectoryStream<Path> selected, Source source, byte[] buffer,
            List<SecretPattern> secrets, OutputStream output) throws IOException {
        Path name = source.path.getFileName();
        int[] matched = new int[secrets.size()];
        long size = 0;
        try (SecureDirectoryStream<Path> directory = openDirectory(selected, parentOf(source.path))) {
            if (!sameRevision(source.attributes, requireRegularFile(directory, name, source.absolute))) {
                throw workspaceChanged();
            }
            try (InputStream input = openFile(directory, name)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkInterrupted();
                    for (int i = 0; i < secrets.size(); i++) {
                        matched[i] = secrets.get(i).scan(buffer, read, matched[i]);
                        if (matched[i] < 0) return -1;
                    }
                    size += read;
                    if (size > source.attributes.size()) throw workspaceChanged();
                    if (output != null) output.write(buffer, 0, read);
                }
            }
            if (size != source.attributes.size() ||
                    !sameRevision(source.attributes, requireRegularFile(directory, name, source.absolute))) {
                throw workspaceChanged();
            }
        }
        return size;
    }

    private String writeArchive(OutputStream output, SecureDirectoryStream<Path> selected, Map<String, Source> sources,
            List<SecretPattern> secrets, byte[] buffer) throws IOException {
        MessageDigest digest = sha256Digest();
        try (ZipOutputStream zip = new ZipOutputStream(new DigestOutputStream(
                new BufferedOutputStream(output), digest))) {
            for (Map.Entry<String, Source> item : sources.entrySet()) {
                checkInterrupted();
                Source source = item.getValue();
                ZipEntry entry = new ZipEntry(item.getKey());
                entry.setTime(source.attributes.lastModifiedTime().toMillis());
                zip.putNextEntry(entry);
                if (source.attributes.isRegularFile() && transfer(selected, source, buffer, secrets, zip) < 0) {
                    throw workspaceChanged();
                }
                zip.closeEntry();
            }
        }
        return toHex(digest.digest());
    }

    private static long verifyZip(Path archive, Map<String, Source> sources, byte[] buffer) throws IOException {
        long files = 0;
        Set<String> seen = new HashSet<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
                checkInterrupted();
                ZipEntry entry = entries.nextElement();
                Source source = sources.get(entry.getName());
                if (source == null || !seen.add(entry.getName()) ||
                        entry.isDirectory() != source.attributes.isDirectory()) {
                    throw new IOException("Список элементов ZIP не совпадает с рабочей папкой");
                }
                CRC32 crc = new CRC32();
                long size = 0;
                try (InputStream input = zip.getInputStream(entry)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        checkInterrupted();
                        crc.update(buffer, 0, read);
                        size += read;
                    }
                }
                long expected = entry.isDirectory() ? 0 : source.attributes.size();
                if (crc.getValue() != entry.getCrc() || size != entry.getSize() || size != expected) {
                    throw new IOException("Не удалось проверить целостность ZIP");
                }
                if (!entry.isDirectory()) files++;
            }
        }
        if (seen.size() != sources.size()) throw new IOException("В ZIP отсутствуют файлы или папки");
        return files;
    }

    static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String toHex(byte[] bytes) {
        char[] hex = "0123456789abcdef".toCharArray();
        StringBuilder sha256 = new StringBuilder(64);
        for (byte value : bytes) {
            sha256.append(hex[(value & 0xf0) >>> 4]);
            sha256.append(hex[value & 0x0f]);
        }
        return sha256.toString();
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Экспорт отменён");
    }

    private static boolean containsSecret(String name, List<SecretPattern> secrets) {
        if (secrets.isEmpty()) return false;
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        for (SecretPattern secret : secrets) {
            if (secret.scan(bytes, bytes.length, 0) < 0) return true;
        }
        return false;
    }

    private static final class SecretPattern {
        final byte[] bytes;
        final int[] prefix;

        SecretPattern(String value) {
            bytes = value.getBytes(StandardCharsets.UTF_8);
            prefix = new int[bytes.length];
            for (int i = 1; i < bytes.length; i++) {
                prefix[i] = advance(bytes[i], prefix[i - 1]);
            }
        }

        private int advance(byte value, int matched) {
            while (matched > 0 && value != bytes[matched]) matched = prefix[matched - 1];
            return value == bytes[matched] ? matched + 1 : matched;
        }

        int scan(byte[] buffer, int count, int matched) {
            for (int i = 0; i < count; i++) {
                matched = advance(buffer[i], matched);
                if (matched == bytes.length) return -1;
            }
            return matched;
        }
    }
}
