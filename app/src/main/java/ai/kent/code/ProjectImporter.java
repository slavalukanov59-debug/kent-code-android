package ai.kent.code;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static ai.kent.code.ProjectService.checkInterrupted;

/** Validate an import in private staging before making the project visible. */
final class ProjectImporter {
    private static final long MIB = 1024L * 1024;
    private static final long ZIP_INPUT_LIMIT = 512 * MIB;
    private static final long ZIP_EXPANDED_LIMIT = 2 * 1024 * MIB;
    private static final long ZIP_RATIO_THRESHOLD = 64 * MIB;
    private static final long ZIP_MAX_RATIO = 1000;
    private static final long ZIP_DIRECTORY_LIMIT = 16 * MIB;
    private static final int ZIP_ENTRY_LIMIT = 100_000;
    private static final int GITHUB_BLOB_LIMIT = 4 * 1024 * 1024;
    private static final int GITHUB_BINARY_LIMIT = 128 * 1024;
    private static final Set<String> BINARY_EXTENSIONS = new HashSet<>(Arrays.asList("apk", "aab", "aar", "jar", "class", "dex",
            "so", "exe", "dll", "bin", "zip", "gz", "xz", "7z", "pdf", "psd", "png", "jpg", "jpeg", "gif",
            "webp", "ico", "mp4", "mp3", "wav", "woff", "woff2", "ttf"));

    private ProjectImporter() {}

    static ProjectService.PreparedImport fromZip(ProjectService owner, Path cache, InputStream input,
            String expectedSha256) throws IOException {
        if (input == null) throw new IOException("Система не смогла открыть ZIP");
        String expected = expectedSha256 == null ? "" : expectedSha256.trim().toLowerCase(Locale.ROOT);
        if (!expected.isEmpty() && !expected.matches("[0-9a-f]{64}")) {
            throw new IOException("SHA-256 должен содержать 64 шестнадцатеричных символа");
        }
        Path archive = Files.createTempFile(cache, "zip-", ".zip");
        try {
            String sha256 = copyArchive(cache, input, archive);
            if (!expected.isEmpty() && !expected.equals(sha256)) {
                throw new IOException("SHA-256 ZIP не совпадает с резервной копией");
            }
            checkZipDirectory(archive);
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                if (zip.size() > ZIP_ENTRY_LIMIT) throw new IOException("Слишком много записей в ZIP");
                return extractZip(owner, cache, zip, sha256);
            }
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    /** Bound the central-directory allocation before invoking the platform ZIP reader. */
    private static void checkZipDirectory(Path archive) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(archive.toFile(), "r")) {
            int length = (int) Math.min(input.length(), 22 + 65535);
            long tailPosition = input.length() - length;
            ByteBuffer tail = readAt(input, tailPosition, length);
            for (int at = length - 22; at >= 0; at--) {
                if (tail.getInt(at) != 0x06054b50 || at + 22 + unsignedShort(tail, at + 20) != length) continue;
                if (unsignedShort(tail, at + 4) != 0 || unsignedShort(tail, at + 6) != 0 ||
                        unsignedShort(tail, at + 8) != unsignedShort(tail, at + 10)) {
                    throw new IOException("Составные ZIP-архивы недоступны");
                }
                long entries = unsignedShort(tail, at + 10);
                long size = Integer.toUnsignedLong(tail.getInt(at + 12));
                long offset = Integer.toUnsignedLong(tail.getInt(at + 16));
                long end = tailPosition + at;
                if (entries == 65535 || size == 0xffffffffL || offset == 0xffffffffL) {
                    if (end < 20) throw invalidDirectory();
                    ByteBuffer locator = readAt(input, end - 20, 20);
                    if (locator.getInt(0) == 0x07064b50 && locator.getInt(4) == 0 && locator.getInt(16) == 1) {
                        end = locator.getLong(8);
                        if (end < 0 || end > tailPosition + at - 76) throw invalidDirectory();
                        ByteBuffer large = readAt(input, end, 56);
                        if (large.getInt(0) != 0x06064b50 || large.getLong(4) < 44 ||
                                large.getInt(16) != 0 || large.getInt(20) != 0 || large.getLong(24) != large.getLong(32)) {
                            throw invalidDirectory();
                        }
                        entries = large.getLong(32);
                        size = large.getLong(40);
                        offset = large.getLong(48);
                    } else if (size == 0xffffffffL || offset == 0xffffffffL) {
                        throw invalidDirectory();
                    }
                }
                if (entries < 0 || entries > ZIP_ENTRY_LIMIT || size < entries * 46 ||
                        size > ZIP_DIRECTORY_LIMIT || offset < 0 || offset > end - size) {
                    throw new IOException("Недопустимый размер каталога ZIP");
                }
                return;
            }
            throw invalidDirectory();
        }
    }

    private static ByteBuffer readAt(RandomAccessFile input, long position, int count) throws IOException {
        input.seek(position);
        byte[] bytes = new byte[count];
        input.readFully(bytes);
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static int unsignedShort(ByteBuffer buffer, int offset) {
        return Short.toUnsignedInt(buffer.getShort(offset));
    }

    private static IOException invalidDirectory() {
        return new IOException("Каталог ZIP повреждён");
    }

    private static String copyArchive(Path cache, InputStream input, Path archive) throws IOException {
        long cap = Math.min(ZIP_INPUT_LIMIT, cache.toFile().getUsableSpace());
        MessageDigest digest = WorkspaceZipExporter.sha256Digest();
        try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(archive))) {
            byte[] buffer = new byte[65536];
            long copied = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                checkInterrupted();
                if (read > cap - copied) throw new IOException("ZIP превышает допустимый резерв места для импорта");
                copied += read;
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        return WorkspaceZipExporter.toHex(digest.digest());
    }

    private static ProjectService.PreparedImport extractZip(ProjectService owner, Path cache, ZipFile zip,
            String sha256) throws IOException {
        Path stage = Files.createTempDirectory(cache, "project-");
        try {
            List<ProjectService.ImportEntry> entries = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            long expanded = 0;
            long compressedTotal = 0;
            long files = 0;
            int skipped = 0;
            long cap = Math.min(ZIP_EXPANDED_LIMIT, cache.toFile().getUsableSpace());
            byte[] buffer = new byte[65536];
            Enumeration<? extends ZipEntry> archiveEntries = zip.entries();
            while (archiveEntries.hasMoreElements()) {
                checkInterrupted();
                ZipEntry entry = archiveEntries.nextElement();
                boolean directory = entry.isDirectory();
                String path = directory ? entry.getName().substring(0, entry.getName().length() - 1) : entry.getName();
                boolean accepted = checkPath(path);
                if (!seen.add(path)) throw new IOException("Повторяющаяся запись ZIP: " + path);
                if (!accepted) {
                    skipped++;
                    continue;
                }
                long bytes = entry.getSize();
                long compressed = entry.getCompressedSize();
                if (bytes < 0 || compressed < 0 || compressed > ZIP_INPUT_LIMIT || directory && bytes != 0 ||
                        bytes > cap - expanded || unsafeExpansion(bytes, compressed)) {
                    throw new IOException("Небезопасный размер распакованной записи ZIP: " + path);
                }
                compressedTotal += compressed;
                if (unsafeExpansion(expanded + bytes, compressedTotal)) {
                    throw new IOException("ZIP имеет небезопасную степень сжатия");
                }
                Path target = stage.resolve(path);
                if (directory) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    extractFile(zip, entry, target, buffer);
                    expanded += bytes;
                    files++;
                }
                entries.add(new ProjectService.ImportEntry(path, bytes, directory));
            }
            return prepared(owner, stage, entries, expanded, files, skipped, sha256, "ZIP");
        } catch (IOException | RuntimeException failure) {
            cleanup(stage, failure);
            throw failure;
        }
    }

    private static boolean unsafeExpansion(long expanded, long compressed) {
        return expanded > ZIP_RATIO_THRESHOLD && compressed < expanded / ZIP_MAX_RATIO;
    }

    private static void extractFile(ZipFile zip, ZipEntry entry, Path target, byte[] buffer) throws IOException {
        long written = 0;
        CRC32 crc = new CRC32();
        try (InputStream input = zip.getInputStream(entry);
             OutputStream output = new BufferedOutputStream(Files.newOutputStream(target,
                     StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                checkInterrupted();
                if (read > entry.getSize() - written) throw new IOException("ZIP распаковал больше данных, чем заявлено");
                written += read;
                crc.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        if (written != entry.getSize() || crc.getValue() != entry.getCrc()) {
            throw new IOException("Повреждена запись ZIP: " + entry.getName());
        }
    }

    static ProjectService.PreparedImport fromGitHub(ProjectService owner, Path cache, GitHubClient client,
            String repository, String ref) throws Exception {
        GitHubClient.RepositorySnapshot snapshot = client.snapshot(repository, ref);
        Path stage = Files.createTempDirectory(cache, "project-");
        try {
            List<ProjectService.ImportEntry> entries = new ArrayList<>();
            Map<String, Path> downloads = new HashMap<>();
            Set<String> seen = new HashSet<>();
            long total = 0;
            long files = 0;
            int skipped = 0;
            long usableSpace = cache.toFile().getUsableSpace();
            for (GitHubClient.TreeEntry entry : snapshot.entries) {
                checkInterrupted();
                boolean accepted = checkPath(entry.path);
                if (!seen.add(entry.path)) throw new IOException("Повторяющийся путь GitHub: " + entry.path);
                if (!accepted) {
                    skipped++;
                    continue;
                }
                Path target = stage.resolve(entry.path);
                if ("tree".equals(entry.type)) {
                    Files.createDirectories(target);
                    entries.add(new ProjectService.ImportEntry(entry.path, 0, true));
                    continue;
                }
                boolean regular = "blob".equals(entry.type) &&
                        ("100644".equals(entry.mode) || "100755".equals(entry.mode));
                if (!regular || entry.size < 0 || entry.size > GITHUB_BLOB_LIMIT ||
                        entry.size > GITHUB_BINARY_LIMIT && isBinaryPath(entry.path)) {
                    skipped++;
                    continue;
                }
                if (entry.size > usableSpace - total) throw new IOException("Не хватает места для проекта GitHub");
                Files.createDirectories(target.getParent());
                Path previous = downloads.get(entry.sha);
                if (previous == null) {
                    byte[] bytes = client.readBlob(snapshot.repository, entry.sha, GITHUB_BLOB_LIMIT);
                    if (bytes.length != entry.size) throw new IOException("Размер файла GitHub изменился: " + entry.path);
                    Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    downloads.put(entry.sha, target);
                } else {
                    if (Files.size(previous) != entry.size) throw new IOException("Размер файла GitHub изменился: " + entry.path);
                    Files.copy(previous, target);
                }
                total += entry.size;
                files++;
                entries.add(new ProjectService.ImportEntry(entry.path, entry.size, false));
            }
            String source = snapshot.repository + " · " + snapshot.ref + " · " + snapshot.commitSha;
            return prepared(owner, stage, entries, total, files, skipped, "", source);
        } catch (Exception failure) {
            cleanup(stage, failure);
            throw failure;
        }
    }

    private static boolean checkPath(String path) throws IOException {
        if (path == null || path.getBytes(StandardCharsets.UTF_8).length > 4096) {
            throw new IOException("Путь записи превышает возможности рабочей папки");
        }
        boolean accepted = true;
        for (String component : path.split("/", -1)) {
            if (!SafeWorkspace.validComponent(component) || component.getBytes(StandardCharsets.UTF_8).length > 255) {
                throw new IOException("Недопустимый путь в источнике импорта: " + path);
            }
            if (!SafeWorkspace.safeComponent(component)) accepted = false;
        }
        return accepted;
    }

    private static boolean isBinaryPath(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 && BINARY_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static ProjectService.PreparedImport prepared(ProjectService owner, Path stage,
            List<ProjectService.ImportEntry> entries, long bytes, long files, int skipped, String sha, String source) {
        entries.sort(Comparator.comparing(entry -> entry.path));
        return new ProjectService.PreparedImport(owner, stage, entries, bytes, files, skipped, sha, source);
    }

    private static void cleanup(Path stage, Exception failure) {
        try { ProjectService.removeStagedTree(stage); }
        catch (IOException cleanup) { failure.addSuppressed(cleanup); }
    }
}
