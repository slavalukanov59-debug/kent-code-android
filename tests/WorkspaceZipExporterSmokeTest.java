package ai.kent.code;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class WorkspaceZipExporterSmokeTest {
    private static final String PROJECT = "KENT_CODE_CLONE";

    public static void main(String[] args) throws Exception {
        Path sandbox = Files.createTempDirectory("kent-zip-tests-");
        try {
            Path root = Files.createDirectory(sandbox.resolve("workspace"));
            Path cache = Files.createDirectory(sandbox.resolve("cache"));
            Path outside = Files.createDirectory(sandbox.resolve("workspace-sibling"));
            Files.writeString(outside.resolve("external.txt"), "Outside the workspace");
            Path privateSettings = Files.createDirectory(sandbox.resolve("shared_prefs"));
            Files.writeString(privateSettings.resolve("kent_settings.xml"), "Private app data");
            WorkspaceZipExporter exporter = new WorkspaceZipExporter(new SafeWorkspace(root.toFile()));

            roundTrip(exporter, root, cache, outside);
            emptyProject(exporter, root, cache);
            largeProject(exporter, root, cache);
            workspaceRoot(exporter, cache);
            knownSecretContent(exporter, root, cache);
            rejectedExports(exporter, root, cache, outside);
            rejectedWorkspaceAlias(sandbox, outside, cache);
        } finally {
            deleteTree(sandbox);
        }
        System.out.println("PASS: workspace ZIP round-trip, counts, SHA-256, exclusions, boundaries and cleanup");
    }

    private static void roundTrip(WorkspaceZipExporter exporter, Path root, Path cache, Path outside) throws Exception {
        Path project = Files.createDirectory(root.resolve(PROJECT));
        Path source = Files.createDirectories(project.resolve("src/папка"));
        Files.createDirectory(project.resolve("empty"));
        byte[] binary = {0, 1, (byte) 0xff, 5, (byte) 0x80, 0, 10};
        Files.write(source.resolve("данные.bin"), binary);
        String readme = "# Проект\nВложенные каталоги и двоичные файлы\n";
        Path readmeFile = Files.writeString(project.resolve("README.md"), readme);

        List<String> excluded = List.of(".env", ".ENV.LOCAL", ".npmrc", ".netrc", "credentials.json",
                "secrets.json", "id_ed25519", "local.properties", "store.jks", "client.pem", "signing.key",
                "kent_settings.xml", "kent_settings.xml.bak", ".git/config", ".gradle/cache.properties");
        for (String name : excluded) {
            Path file = project.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "Synthetic credential fixture");
        }
        Files.createSymbolicLink(project.resolve("external-file"), outside.resolve("external.txt"));
        Files.createSymbolicLink(project.resolve("external-dir"), outside);
        Files.createSymbolicLink(project.resolve("internal-file"), readmeFile.getFileName());
        Files.createSymbolicLink(project.resolve("internal-dir"), Path.of("src"));
        Files.createSymbolicLink(project.resolve("internal-loop"), Path.of("."));
        Files.createSymbolicLink(project.resolve("dangling"), Path.of("missing"));
        Files.createLink(project.resolve("outside-hardlink.txt"), outside.resolve("external.txt"));

        check(exporter.suggestedName(PROJECT).equals(PROJECT + ".zip"), "Suggested filename");
        List<WorkspaceZipExporter.Entry> entries = exporter.listDirectory(PROJECT);
        check(entries.size() == 3, "Only exportable files and directories should be listed");
        WorkspaceZipExporter.Entry readmeEntry = entries.stream()
                .filter(entry -> entry.name.equals("README.md"))
                .findFirst().orElseThrow(() -> new AssertionError("README.md missing from listing"));
        check(!readmeEntry.directory, "File was listed as a directory");
        check(readmeEntry.relativePath.equals(PROJECT + "/README.md"), "File path relative to workspace");
        check(readmeEntry.bytes == readme.getBytes(StandardCharsets.UTF_8).length, "UTF-8 file size in bytes");
        check(exporter.directorySize(PROJECT) == readmeEntry.bytes + binary.length,
                "Directory size excludes credential paths and links");
        WorkspaceZipExporter.Entry folder = entries.stream()
                .filter(entry -> entry.name.equals("src"))
                .findFirst().orElseThrow(() -> new AssertionError("Source directory missing from listing"));
        check(folder.directory, "Directory was listed as file");
        check(exporter.listDirectory(folder.relativePath).get(0).name.equals("папка"), "Folder navigation");
        check(Arrays.equals(exporter.readPreview(PROJECT + "/src/папка/данные.bin"), binary), "Preview binary read");

        Path stagedFile;
        try (WorkspaceZipExporter.Archive archive = exporter.stage(PROJECT, cache.toFile())) {
            stagedFile = archive.file.toPath();
            check(stagedFile.getParent().equals(cache), "ZIP should use private staging outside workspace");
            check(archive.suggestedName.equals(PROJECT + ".zip"), "Archive suggested name");
            check(archive.fileCount == 2 && archive.availableFileCount == 2, "Valid file counts agree");
            byte[] archivedBytes = Files.readAllBytes(stagedFile);
            check(archive.bytes == archivedBytes.length && archive.bytes > 0, "Archive size in bytes");
            String calculatedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archivedBytes));
            check(archive.sha256.equals(calculatedHash), "Archive SHA-256");

            try (ZipFile zip = new ZipFile(archive.file)) {
                List<String> names = zip.stream().map(ZipEntry::getName).toList();
                Set<String> expected = Set.of("README.md", "empty/", "src/", "src/папка/", "src/папка/данные.bin");
                check(names.size() == expected.size() && expected.containsAll(names), "Unexpected ZIP entries: " + names);
                check(zip.getEntry("empty/").isDirectory(), "Empty directory must survive ZIP export");
                try (var input = zip.getInputStream(zip.getEntry("README.md"))) {
                    check(new String(input.readAllBytes(), StandardCharsets.UTF_8).equals(readme), "ZIP text differs from source");
                }
                try (var input = zip.getInputStream(zip.getEntry("src/папка/данные.bin"))) {
                    check(Arrays.equals(input.readAllBytes(), binary), "ZIP binary differs from source");
                }
            }

            try (TrackingInput copy = new TrackingInput(archivedBytes)) {
                archive.verifyCopy(copy);
                check(!copy.closed, "Copy validation must leave caller-owned stream open");
            }
            byte[] changed = archivedBytes.clone();
            changed[changed.length / 2] ^= 1;
            expectIOException(() -> archive.verifyCopy(new ByteArrayInputStream(changed)), "Altered copy accepted");
            byte[] shortCopy = Arrays.copyOf(archivedBytes, archivedBytes.length - 1);
            expectIOException(() -> archive.verifyCopy(new ByteArrayInputStream(shortCopy)), "Truncated copy accepted");
            byte[] extraBytes = Arrays.copyOf(archivedBytes, archivedBytes.length + 1);
            expectIOException(() -> archive.verifyCopy(new ByteArrayInputStream(extraBytes)), "Extra bytes accepted");
        }
        check(!Files.exists(stagedFile), "Temporary ZIP leaked after close");
        check(Files.readString(readmeFile).equals(readme), "Source text changed");
        check(Arrays.equals(Files.readAllBytes(source.resolve("данные.bin")), binary), "Source binary changed");
        check(Files.readString(project.resolve(".env")).equals("Synthetic credential fixture"), "Source credential changed");
        check(Files.isSymbolicLink(project.resolve("external-file")), "Source link changed");
    }

    private static void emptyProject(WorkspaceZipExporter exporter, Path root, Path cache) throws Exception {
        Files.createDirectory(root.resolve("empty-project"));
        Path exportCache = cache.resolve("workspace-zip");
        try (WorkspaceZipExporter.Archive archive = exporter.stage("empty-project", exportCache.toFile());
             ZipFile zip = new ZipFile(archive.file)) {
            check(zip.size() == 0, "Empty project should produce a valid empty ZIP");
            check(archive.fileCount == 0 && archive.availableFileCount == 0, "Empty project file counts");
        }
        Files.delete(exportCache);
    }

    private static void largeProject(WorkspaceZipExporter exporter, Path root, Path cache) throws Exception {
        Path project = Files.createDirectory(root.resolve("large-project"));
        for (int i = 0; i < 245; i++) {
            Files.createFile(project.resolve("file-" + i + ".txt"));
        }
        String deepPath = "nested/".repeat(11) + "large.bin";
        Path large = project.resolve(deepPath);
        Files.createDirectories(large.getParent());
        byte[] payload = new byte[300_000];
        Arrays.fill(payload, (byte) 0x92);
        Files.write(large, payload);
        try (WorkspaceZipExporter.Archive archive = exporter.stage("large-project", cache.toFile());
             ZipFile zip = new ZipFile(archive.file)) {
            check(archive.fileCount == 246 && archive.availableFileCount == 246, "Export should exceed agent tree limit");
            check(zip.getEntry(deepPath).getSize() == payload.length, "Export should exceed agent depth and read limits");
            try (var input = zip.getInputStream(zip.getEntry(deepPath))) {
                check(Arrays.equals(input.readAllBytes(), payload), "Large file differs from source");
            }
        }
    }

    private static void workspaceRoot(WorkspaceZipExporter exporter, Path cache) throws Exception {
        check(exporter.suggestedName("").equals("workspace.zip"), "Workspace ZIP filename");
        try (WorkspaceZipExporter.Archive archive = exporter.stage("", cache.toFile());
             ZipFile zip = new ZipFile(archive.file)) {
            check(archive.fileCount == 248 && archive.availableFileCount == 248, "Workspace root file counts");
            check(zip.getEntry(PROJECT + "/README.md") != null, "Project file missing from workspace ZIP");
            check(zip.getEntry("empty-project/").isDirectory(), "Empty project missing from workspace ZIP");
            check(zip.stream().noneMatch(entry -> entry.getName().contains("kent_settings.xml") ||
                    entry.getName().contains("external")), "Private or out-of-workspace files in ZIP");
        }
    }

    private static void knownSecretContent(WorkspaceZipExporter exporter, Path root, Path cache) throws Exception {
        Path project = Files.createDirectory(root.resolve("project-config"));
        Path safe = Files.createDirectory(project.resolve("safe"));
        Path sensitive = Files.createDirectory(project.resolve("sensitive"));
        String key = "synthetic-api-key-not-a-live-credential";
        String token = "synthetic-bridge-token-not-a-live-credential";
        String harmless = "api_key=read_from_environment";
        Files.writeString(safe.resolve("config.txt"), harmless);
        String embedded = "a".repeat(65_530) + key;
        Path original = Files.writeString(sensitive.resolve("config.txt"), embedded);
        Files.writeString(project.resolve("connection.txt"), "Authorization: Bearer " + token);
        Files.writeString(project.resolve(key + ".txt"), "Ordinary contents with a sensitive filename");
        Path namedWithSecret = Files.createDirectory(project.resolve("config-" + token));
        Files.writeString(namedWithSecret.resolve("one.txt"), "Ordinary contents in a sensitive path");
        try (WorkspaceZipExporter.Archive archive = exporter.stage("project-config", cache.toFile(), key, token);
             ZipFile zip = new ZipFile(archive.file)) {
            check(archive.fileCount == 1 && archive.availableFileCount == 1, "Secret-bearing files count as excluded");
            check(zip.getEntry("sensitive/config.txt") == null && zip.getEntry("connection.txt") == null,
                    "Known API key or bridge token was archived");
            check(zip.stream().noneMatch(entry -> entry.getName().contains(key) || entry.getName().contains(token)),
                    "Known API key or bridge token was archived as an entry name");
            try (var input = zip.getInputStream(zip.getEntry("safe/config.txt"))) {
                check(new String(input.readAllBytes(), StandardCharsets.UTF_8).equals(harmless),
                        "Ordinary config with the same basename was not preserved");
            }
        }
        try (WorkspaceZipExporter.Archive archive = exporter.stage("project-config/" + namedWithSecret.getFileName(),
                cache.toFile(), key, token)) {
            check(archive.suggestedName.endsWith(".zip") && !archive.suggestedName.contains(token),
                    "Known token appeared in suggested ZIP filename");
        }
        check(Files.readString(original).equals(embedded), "Secret-bearing source was modified");
    }

    private static void rejectedExports(WorkspaceZipExporter exporter, Path root, Path cache, Path outside) throws Exception {
        List<String> rejected = List.of("../" + outside.getFileName(), outside.toString(), "missing", PROJECT + "/README.md",
                PROJECT + "/external-dir", PROJECT + "/internal-dir", PROJECT + "/external-file",
                PROJECT + "/internal-dir/папка", PROJECT + "/dangling", PROJECT + "/.git");
        for (String path : rejected) {
            expectIOException(() -> exporter.stage(path, cache.toFile()), "Unsafe folder accepted: " + path);
        }
        expectIOException(() -> exporter.listDirectory(PROJECT + "/external-dir"), "Unsafe directory listed");
        expectIOException(() -> exporter.readPreview(PROJECT + "/external-file"), "Unsafe file previewed");
        expectIOException(() -> exporter.readPreview(PROJECT + "/outside-hardlink.txt"), "Hard link previewed");
        expectIOException(() -> exporter.readPreview(PROJECT + "/.env"), "Secret file previewed");
        expectIOException(() -> exporter.readPreview("large-project/" + "nested/".repeat(11) + "large.bin"),
                "Oversized file previewed");
        expectIOException(() -> exporter.stage(PROJECT, root.toFile()), "Staging inside workspace accepted");
        Path cacheLink = Files.createSymbolicLink(cache.resolve("workspace-alias"), root);
        expectIOException(() -> exporter.stage(PROJECT, cacheLink.toFile()), "Aliased workspace cache accepted");
        Files.delete(cacheLink);
        Path cacheFile = Files.writeString(cache.resolve("not-a-directory"), "keep");
        expectIOException(() -> exporter.stage(PROJECT, cacheFile.toFile()), "Non-directory cache accepted");
        check(Files.readString(cacheFile).equals("keep"), "Cache file was modified by failed export");
        Files.delete(cacheFile);
        try (var entries = Files.list(cache)) {
            check(entries.findAny().isEmpty(), "Temporary ZIP leaked after rejected export");
        }
    }

    private static void rejectedWorkspaceAlias(Path sandbox, Path outside, Path cache) throws Exception {
        Path alias = Files.createSymbolicLink(sandbox.resolve("workspace-alias"), outside);
        expectIOException(() -> {
            WorkspaceZipExporter exporter = new WorkspaceZipExporter(new SafeWorkspace(alias.toFile()));
            try (WorkspaceZipExporter.Archive ignored = exporter.stage("", cache.toFile())) {
                throw new AssertionError("Symlinked workspace root was archived");
            }
        }, "Symlinked workspace root accepted");
    }

    private static void deleteTree(Path root) throws IOException {
        List<Path> paths;
        try (var walk = Files.walk(root)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.delete(path);
        }
    }

    private static void expectIOException(IoAction action, String message) throws Exception {
        try {
            action.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws Exception;
    }

    private static final class TrackingInput extends ByteArrayInputStream {
        boolean closed;

        TrackingInput(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
