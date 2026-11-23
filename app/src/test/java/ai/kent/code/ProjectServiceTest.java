package ai.kent.code;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class ProjectServiceTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void importsAndVerifiesLegacyExportsWithBinaryAndEmptyDirectories() throws Exception {
        SafeWorkspace workspace = new SafeWorkspace(folder.newFolder());
        File cache = folder.newFolder();
        ProjectService service = new ProjectService(workspace, cache);
        Path source = Files.createDirectory(workspace.root().toPath().resolve("старый"));
        Path binary = Files.createDirectories(source.resolve("assets")).resolve("blob.bin");
        byte[] expected = {(byte) 0xfe, 0, 1, 9};
        Files.write(binary, expected);
        Files.createDirectory(source.resolve("empty"));
        workspace.write("старый/src/Main.kt", "fun main() = println(\"Привет\")\n");
        workspace.write("legacy-root.txt", "kept");

        try (WorkspaceZipExporter.Archive archive = service.exporter().stage("старый", cache);
             InputStream input = Files.newInputStream(archive.file.toPath());
             ProjectService.PreparedImport prepared = service.prepareZip(input, archive.sha256)) {
            assertEquals(2, prepared.fileCount);
            assertEquals(archive.sha256, prepared.sha256);
            assertEquals("старый", service.open("старый"));
            assertEquals(List.of("старый"), service.list());
            ProjectService.ImportResult imported = service.publish(prepared, service.prepareTarget("restore"), false);
            assertEquals(2, imported.fileCount);
            assertNull(imported.previousProject);
            assertArrayEquals(expected, Files.readAllBytes(workspace.resolve("restore/assets/blob.bin").toPath()));
            assertTrue(workspace.resolve("restore/empty").isDirectory());
            assertEquals(workspace.read("старый/src/Main.kt"), workspace.read("restore/src/Main.kt"));
            assertEquals("kept", workspace.read("legacy-root.txt"));
            blocked(() -> service.publish(prepared, service.prepareTarget("another"), false));
        }
    }

    @Test public void requiresExactOverwriteReviewAndKeepsRestorablePreviousProject() throws Exception {
        SafeWorkspace workspace = new SafeWorkspace(folder.newFolder());
        ProjectService service = new ProjectService(workspace, folder.newFolder());
        workspace.write("existing/old.txt", "old");
        byte[] archive = zip("latest.txt", "new".getBytes(StandardCharsets.UTF_8));
        try (ProjectService.PreparedImport prepared = service.prepareZip(new ByteArrayInputStream(archive), "")) {
            ProjectService.ImportTarget first = service.prepareTarget("existing");
            assertTrue(first.exists);
            blocked(() -> service.publish(prepared, first, false));
            assertEquals("old", workspace.read("existing/old.txt"));

            workspace.write("existing/old.txt", "edited during review");
            blocked(() -> service.publish(prepared, first, true));
            assertEquals("edited during review", workspace.read("existing/old.txt"));

            ProjectService.ImportTarget stop = service.prepareTarget("existing");
            AtomicInteger checks = new AtomicInteger();
            blocked(() -> service.publish(prepared, stop, true, () -> checks.incrementAndGet() == 2));
            assertEquals("edited during review", workspace.read("existing/old.txt"));
            assertTrue(service.trash().isEmpty());

            ProjectService.ImportResult result = service.publish(prepared, service.prepareTarget("existing"), true);
            assertEquals("new", workspace.read("existing/latest.txt"));
            assertFalse(workspace.resolve("existing/old.txt").exists());
            assertNotNull(result.previousProject);
            assertEquals(1, service.trash().size());
            blocked(() -> service.restore(result.previousProject.id, ""));
            assertEquals("restored", service.restore(result.previousProject.id, "restored"));
            assertEquals("edited during review", workspace.read("restored/old.txt"));
            assertTrue(service.trash().isEmpty());
        }
    }

    @Test public void rejectsRacingImportAndStaleDeleteApprovals() throws Exception {
        SafeWorkspace workspace = new SafeWorkspace(folder.newFolder());
        ProjectService service = new ProjectService(workspace, folder.newFolder());
        byte[] archive = zip("src/main.py", new byte[] {65});
        try (ProjectService.PreparedImport one = service.prepareZip(new ByteArrayInputStream(archive), "");
             ProjectService.PreparedImport two = service.prepareZip(new ByteArrayInputStream(archive), "")) {
            ProjectService.ImportTarget first = service.prepareTarget("example");
            ProjectService.ImportTarget second = service.prepareTarget("example");
            service.publish(one, first, false);
            blocked(() -> service.publish(two, second, true));
        }
        ProjectService.DeletePlan plan = service.prepareDelete("example");
        workspace.write("example/src/main.py", "modified");
        blocked(() -> service.delete(plan));
        ProjectService.TrashEntry deleted = service.delete(service.prepareDelete("example"));
        assertTrue(deleted.directory);
        assertFalse(workspace.resolve("example").exists());
        assertEquals("example", service.restore(deleted.id, ""));
        assertEquals("modified", workspace.read("example/src/main.py"));
    }

    @Test public void rejectsZipSlipCorruptEntriesWrongHashAndBombs() throws Exception {
        SafeWorkspace workspace = new SafeWorkspace(folder.newFolder());
        File cache = folder.newFolder();
        ProjectService service = new ProjectService(workspace, cache);
        for (String unsafe : List.of("../escape", "prefix/../../escape", "/absolute", "C:/outside", "a\\..\\b")) {
            byte[] archive = zip(unsafe, new byte[] {65});
            blocked(() -> service.prepareZip(new ByteArrayInputStream(archive), ""));
        }

        byte[] data = "example corruption target".getBytes(StandardCharsets.UTF_8);
        byte[] valid = zip("file.txt", data);
        blocked(() -> service.prepareZip(new ByteArrayInputStream(valid), "0".repeat(64)));
        byte[] oversizedDirectory = valid.clone();
        ByteBuffer.wrap(oversizedDirectory).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(oversizedDirectory.length - 22 + 12, 24 * 1024 * 1024);
        blocked(() -> service.prepareZip(new ByteArrayInputStream(oversizedDirectory), ""));
        byte[] corrupt = valid.clone();
        int position = 30 + "file.txt".length();
        assertEquals(data[0], corrupt[position]);
        corrupt[position] ^= 1;
        blocked(() -> service.prepareZip(new ByteArrayInputStream(corrupt), ""));

        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(encoded)) {
            output.setLevel(9);
            output.putNextEntry(new ZipEntry("bomb.txt"));
            byte[] zeros = new byte[1024 * 1024];
            for (int mib = 0; mib < 65; mib++) output.write(zeros);
            output.closeEntry();
        }
        blocked(() -> service.prepareZip(new ByteArrayInputStream(encoded.toByteArray()), ""));
        blocked(() -> {
            Thread.currentThread().interrupt();
            try { return service.prepareZip(new ByteArrayInputStream(valid), ""); }
            finally { Thread.interrupted(); }
        });
        try (java.util.stream.Stream<Path> files = Files.list(cache.toPath().resolve("project-imports"))) {
            assertEquals(0, files.count());
        }
        assertTrue(service.list().isEmpty());
    }

    @Test public void listsLargeTreesAndReadsUnicodeAtActualByteOffsets() throws Exception {
        SafeWorkspace workspace = new SafeWorkspace(folder.newFolder());
        ProjectService service = new ProjectService(workspace, folder.newFolder());
        service.create("example");
        for (int i = 0; i < 275; i++) workspace.write("example/src/p/" + i + ".kt", "class K");
        String prefix = "A🌱Б";
        String text = prefix + "x".repeat(150_000);
        assertTrue(service.applyText("example", "long.kt", null, text));
        assertFalse(service.applyText("example", "long.kt", prefix, "stale"));
        SafeWorkspace.TextPage first = service.read("example", "long.kt", 0, 2);
        assertEquals("A🌱", first.text);
        assertEquals(5, first.nextOffset);
        SafeWorkspace.TextPage second = service.read("example", "long.kt", first.nextOffset, 3);
        assertEquals("Бxx", second.text);
        assertEquals(first.revision, second.revision);
        SafeWorkspace.TextPage last = service.read("example", "long.kt", first.bytes - 3, 100);
        assertEquals("xxx", last.text);
        assertTrue(last.eof);
        blocked(() -> service.read("example", "long.kt", 2, 10));

        ProjectService.TreePage tree = service.tree("example", "", 0, 240, true);
        assertEquals(240, tree.paths.size());
        assertTrue(tree.hasMore);
        ProjectService.TreePage remaining = service.tree("example", "", tree.nextOffset, 100, true);
        assertEquals(38, remaining.paths.size());
        assertFalse(remaining.hasMore);
        assertTrue(remaining.paths.contains("long.kt"));
    }

    @Test public void excludesPrivateFilesAndNeverFollowsProjectLinks() throws Exception {
        SafeWorkspace workspace = new SafeWorkspace(folder.newFolder());
        ProjectService service = new ProjectService(workspace, folder.newFolder());
        service.create("project");
        Path outside = Files.write(folder.newFolder().toPath().resolve("outside.txt"),
                "private".getBytes(StandardCharsets.UTF_8));
        Files.createLink(workspace.resolve("project/alias.txt").toPath(), outside);
        Files.createSymbolicLink(workspace.resolve("project/target").toPath(), outside.getParent());
        assertTrue(service.entries("project").isEmpty());
        blocked(() -> service.read("project", "alias.txt", 0, 10));
        blocked(() -> service.currentText("project", "alias.txt"));
        blocked(() -> service.prepareTarget("project/target/subdir"));
        blocked(() -> service.applyText("project", ".kent-trash/path.txt", null, "tampering"));
        byte[] archive = zip("src/.env", "private".getBytes(StandardCharsets.UTF_8));
        try (ProjectService.PreparedImport prepared = service.prepareZip(new ByteArrayInputStream(archive), "")) {
            assertEquals(1, prepared.skippedCount);
            assertEquals(0, prepared.fileCount);
            assertTrue(prepared.entries.isEmpty());
        }
    }

    @Test public void importsPinnedGitHubSourceAndSkipsLargeBinariesAndLinks() throws Exception {
        SafeWorkspace workspace = new SafeWorkspace(folder.newFolder());
        ProjectService service = new ProjectService(workspace, folder.newFolder());
        String commit = "1".repeat(40);
        String tree = "2".repeat(40);
        String skipped = "3".repeat(40);
        byte[] content = "class Main {}\n".getBytes(StandardCharsets.UTF_8);
        String blob = GitHubClient.blobId(content);
        String repository = "/repos/kent/source";
        List<String> requests = new ArrayList<>();
        String commitResponse = new JSONObject().put("sha", commit).put("commit",
                new JSONObject().put("tree", new JSONObject().put("sha", tree))).toString();
        String treeResponse = new JSONObject().put("tree", new JSONArray()
                .put(treeEntry("src", "tree", "040000", tree, -1))
                .put(treeEntry("src/Main.java", "blob", "100644", blob, content.length))
                .put(treeEntry("copy.java", "blob", "100644", blob, content.length))
                .put(treeEntry("large.apk", "blob", "100644", skipped, 524288))
                .put(treeEntry("linked", "blob", "120000", skipped, 5))
                .put(treeEntry(".env", "blob", "100644", skipped, 5))).toString();
        String blobResponse = new JSONObject().put("sha", blob).put("size", content.length).put("encoding", "base64")
                .put("content", Base64.getEncoder().encodeToString(content)).toString();
        GitHubClient github = new GitHubClient("", (method, path, body, token) -> {
            requests.add(path);
            assertEquals("GET", method);
            if (path.equals(repository + "/commits/feature")) return commitResponse;
            if (path.equals(repository + "/git/trees/" + tree + "?recursive=1")) return treeResponse;
            if (path.equals(repository + "/git/blobs/" + blob)) return blobResponse;
            throw new AssertionError(path);
        });
        try (ProjectService.PreparedImport prepared = service.prepareGitHub(github, "kent/source", "feature")) {
            assertTrue(prepared.source.endsWith(commit));
            assertEquals(2, prepared.fileCount);
            assertEquals(3, prepared.skippedCount);
            assertEquals(3, requests.size());
            assertTrue(service.list().isEmpty());
            service.publish(prepared, service.prepareTarget("from-gh"), false);
            assertEquals("class Main {}\n", workspace.read("from-gh/src/Main.java"));
            assertEquals(workspace.read("from-gh/src/Main.java"), workspace.read("from-gh/copy.java"));
        }
    }

    private static JSONObject treeEntry(String path, String type, String mode, String sha, long size) throws JSONException {
        return new JSONObject().put("path", path).put("type", type).put("mode", mode).put("sha", sha).put("size", size);
    }

    private static byte[] zip(String name, byte[] bytes) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        CRC32 crc = new CRC32();
        crc.update(bytes);
        try (ZipOutputStream output = new ZipOutputStream(encoded)) {
            ZipEntry entry = new ZipEntry(name);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(bytes.length);
            entry.setCrc(crc.getValue());
            output.putNextEntry(entry);
            output.write(bytes);
            output.closeEntry();
        }
        return encoded.toByteArray();
    }

    private static void blocked(Callable<?> operation) throws Exception {
        try {
            Object result = operation.call();
            if (result instanceof AutoCloseable) ((AutoCloseable) result).close();
            fail("Invalid project operation was accepted");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
