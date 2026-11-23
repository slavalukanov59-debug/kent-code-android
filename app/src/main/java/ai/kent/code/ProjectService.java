package ai.kent.code;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/** Projects are directories in the existing private workspace. */
final class ProjectService {
    private static final String TRASH_DIRECTORY = ".kent-trash";
    private static final String TRASH_ITEM = "item";
    private static final String TRASH_PATH = "path.txt";
    private final SafeWorkspace workspace;
    private final WorkspaceZipExporter exporter;
    private final File cacheDir;

    ProjectService(SafeWorkspace workspace, File cacheDir) {
        this.workspace = workspace;
        this.exporter = new WorkspaceZipExporter(workspace);
        this.cacheDir = cacheDir;
    }

    public static final class TreePage {
        public final List<String> paths;
        public final long nextOffset;
        public final boolean hasMore;

        private TreePage(List<String> paths, long nextOffset, boolean hasMore) {
            this.paths = Collections.unmodifiableList(paths);
            this.nextOffset = nextOffset;
            this.hasMore = hasMore;
        }
    }

    public static final class ImportEntry {
        public final String path;
        public final long bytes;
        public final boolean directory;

        ImportEntry(String path, long bytes, boolean directory) {
            this.path = path;
            this.bytes = bytes;
            this.directory = directory;
        }
    }

    public static final class PreparedImport implements AutoCloseable {
        public final List<ImportEntry> entries;
        public final long bytes, fileCount;
        public final int skippedCount;
        public final String sha256, source;
        private final ProjectService owner;
        private final Path stage;
        private boolean closed;

        PreparedImport(ProjectService owner, Path stage, List<ImportEntry> entries, long bytes,
                long fileCount, int skippedCount, String sha256, String source) {
            this.owner = owner;
            this.stage = stage;
            this.entries = Collections.unmodifiableList(entries);
            this.bytes = bytes;
            this.fileCount = fileCount;
            this.skippedCount = skippedCount;
            this.sha256 = sha256;
            this.source = source;
        }

        @Override public synchronized void close() throws IOException {
            if (closed) return;
            removeStagedTree(stage);
            closed = true;
        }
    }

    public static final class ImportTarget {
        public final String project;
        public final boolean exists;
        private final ProjectService owner;
        private final String revision;

        private ImportTarget(ProjectService owner, String project, String revision) {
            this.owner = owner;
            this.project = project;
            this.exists = revision != null;
            this.revision = revision;
        }
    }

    public static final class ImportResult {
        public final String project;
        public final long fileCount;
        public final TrashEntry previousProject;

        private ImportResult(String project, long fileCount, TrashEntry previousProject) {
            this.project = project;
            this.fileCount = fileCount;
            this.previousProject = previousProject;
        }
    }

    public static final class DeletePlan {
        public final String project;
        public final String path;
        private final ProjectService owner;
        private final String revision;

        private DeletePlan(ProjectService owner, String project, String path, String revision) {
            this.owner = owner;
            this.project = project;
            this.path = path;
            this.revision = revision;
        }
    }

    public static final class TrashEntry {
        public final String id, project;
        public final boolean directory;
        public final long trashedAt;

        private TrashEntry(String id, String project, boolean directory, long trashedAt) {
            this.id = id;
            this.project = project;
            this.directory = directory;
            this.trashedAt = trashedAt;
        }
    }

    public List<String> list() throws IOException {
        List<String> projects = new ArrayList<>();
        for (WorkspaceZipExporter.Entry entry : entries("")) if (entry.directory) projects.add(entry.relativePath);
        return projects;
    }

    public String create(String project) throws IOException {
        synchronized (workspace) {
            Path destination = requireDestination(project);
            Files.createDirectory(destination);
            return project;
        }
    }

    public String open(String project) throws IOException {
        synchronized (workspace) {
            requireDirectory(workspace.projectPath(project).toPath());
            return project;
        }
    }

    public SafeWorkspace projectWorkspace(String project) throws IOException {
        synchronized (workspace) {
            return new SafeWorkspace(workspace.projectPath(open(project)));
        }
    }

    public WorkspaceZipExporter exporter() { return exporter; }

    public List<WorkspaceZipExporter.Entry> entries(String directory) throws IOException {
        return exporter.listDirectory(directory);
    }

    public SafeWorkspace.TextPage read(String project, String path, long offset, int maxChars) throws IOException {
        return exporter.readTextPage(filePath(project, path), offset, maxChars);
    }

    public TreePage tree(String project, String directory, long offset, int limit, boolean recursive) throws IOException {
        if (offset < 0 || limit < 1 || limit > 1000) throw new IOException("Недопустимые параметры списка файлов");
        if (!project.isEmpty()) open(project);
        if (!directory.isEmpty()) SafeWorkspace.requireProjectPath(directory);
        String root = directory.isEmpty() ? project : joined(project, directory);
        ArrayDeque<Iterator<WorkspaceZipExporter.Entry>> pending = new ArrayDeque<>();
        pending.push(entries(root).iterator());
        List<String> result = new ArrayList<>();
        long position = 0;
        while (!pending.isEmpty()) {
            checkInterrupted();
            Iterator<WorkspaceZipExporter.Entry> current = pending.peek();
            if (!current.hasNext()) {
                pending.pop();
                continue;
            }
            WorkspaceZipExporter.Entry entry = current.next();
            if (position >= offset) {
                if (result.size() == limit) return new TreePage(result, position, true);
                String path = project.isEmpty() ? entry.relativePath : entry.relativePath.substring(project.length() + 1);
                result.add(path + (entry.directory ? "/" : ""));
            }
            position++;
            if (recursive && entry.directory) pending.push(entries(entry.relativePath).iterator());
        }
        return new TreePage(result, position, false);
    }

    String filePath(String project, String path) throws IOException {
        if (!project.isEmpty()) open(project);
        SafeWorkspace.requireProjectPath(path);
        return joined(project, path);
    }

    String currentText(String project, String path) throws IOException {
        synchronized (workspace) { return workspace.projectCurrentText(filePath(project, path)); }
    }

    boolean applyText(String project, String path, String before, String after) throws IOException {
        synchronized (workspace) {
            return workspace.applyProjectText(filePath(project, path), before, after);
        }
    }

    public PreparedImport prepareZip(InputStream input, String expectedSha256) throws IOException {
        return ProjectImporter.fromZip(this, stagingDirectory(), input, expectedSha256);
    }

    PreparedImport prepareWorkspaceZip(String path, String expectedSha256) throws IOException {
        InputStream source;
        synchronized (workspace) {
            exporter.requireFile(path);
            source = Files.newInputStream(workspace.projectPath(path).toPath(), LinkOption.NOFOLLOW_LINKS);
        }
        try (InputStream input = source) { return prepareZip(input, expectedSha256); }
    }

    public PreparedImport prepareGitHub(GitHubClient client, String repository, String ref) throws Exception {
        return ProjectImporter.fromGitHub(this, stagingDirectory(), client, repository, ref);
    }

    public ImportTarget prepareTarget(String destination) throws IOException {
        synchronized (workspace) {
            Path target = requireDestination(destination);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return new ImportTarget(this, destination, null);
            requireDirectory(target);
            return new ImportTarget(this, destination, revision(target));
        }
    }

    public ImportResult publish(PreparedImport prepared, ImportTarget target, boolean replaceApproved) throws IOException {
        return publish(prepared, target, replaceApproved, () -> false);
    }

    ImportResult publish(PreparedImport prepared, ImportTarget target, boolean replaceApproved,
                         BooleanSupplier cancelled) throws IOException {
        if (prepared == null || prepared.owner != this || target == null || target.owner != this) {
            throw new IOException("Сначала подготовьте импорт и выберите папку назначения");
        }
        synchronized (prepared) {
            synchronized (workspace) {
                if (prepared.closed) throw new IOException("Подготовленный импорт больше недоступен");
                Path destination = requireDestination(target.project);
                boolean exists = Files.exists(destination, LinkOption.NOFOLLOW_LINKS);
                if (exists != target.exists || exists && !target.revision.equals(revision(destination))) {
                    throw new IOException("Папка назначения изменилась. Проверьте её ещё раз.");
                }
                if (exists && !replaceApproved) throw new IOException("Для замены проекта требуется подтверждение");
                checkImportCancellation(cancelled);
                TrashEntry old = exists ? moveToTrash(target.project, destination) : null;
                try {
                    checkImportCancellation(cancelled);
                    // Staging and workspace must share a file system. Atomic rename prevents partial imports.
                    Files.move(prepared.stage, destination, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException | RuntimeException failure) {
                    if (old != null) {
                        try { restore(old.id, target.project); }
                        catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                    }
                    throw failure;
                }
                prepared.closed = true;
                return new ImportResult(target.project, prepared.fileCount, old);
            }
        }
    }

    public DeletePlan prepareDelete(String project) throws IOException {
        return prepareDelete(project, "");
    }

    DeletePlan prepareDelete(String project, String relativePath) throws IOException {
        synchronized (workspace) {
            String path = relativePath.isEmpty() ? open(project) : filePath(project, relativePath);
            Path target = workspace.projectPath(path).toPath();
            return new DeletePlan(this, project, path, revision(target));
        }
    }

    public TrashEntry delete(DeletePlan plan) throws IOException {
        if (plan == null || plan.owner != this) throw new IOException("Сначала проверьте путь удаления");
        synchronized (workspace) {
            Path target = workspace.projectPath(plan.path).toPath();
            if (!plan.revision.equals(revision(target))) throw new IOException("Содержимое изменилось после просмотра");
            checkInterrupted();
            return moveToTrash(plan.path, target);
        }
    }

    public List<TrashEntry> trash() throws IOException {
        synchronized (workspace) {
            Path trash = trashDirectory(false);
            List<TrashEntry> entries = new ArrayList<>();
            if (trash == null) return entries;
            try (java.nio.file.DirectoryStream<Path> records = Files.newDirectoryStream(trash)) {
                for (Path record : records) {
                    String id = record.getFileName().toString();
                    if (!isTrashId(id) || !Files.isDirectory(record, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (!Files.exists(record.resolve(TRASH_ITEM), LinkOption.NOFOLLOW_LINKS)) continue;
                    entries.add(readTrash(record));
                }
            }
            entries.sort(Comparator.comparing((TrashEntry entry) -> entry.project)
                    .thenComparing(Comparator.comparingLong((TrashEntry entry) -> entry.trashedAt).reversed()));
            return entries;
        }
    }

    public String restore(String id, String destination) throws IOException {
        synchronized (workspace) {
            Path trash = trashDirectory(false);
            if (trash == null || !isTrashId(id)) throw new IOException("Запись корзины недоступна");
            Path record = trash.resolve(id);
            requireDirectory(record);
            TrashEntry entry = readTrash(record);
            String path = destination == null || destination.isEmpty() ? entry.project : destination;
            Path target = requireDestination(path);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Путь восстановления уже занят");
            Files.move(record.resolve(TRASH_ITEM), target, StandardCopyOption.ATOMIC_MOVE);
            // Cleanup after publication is best effort. A record without an item is ignored.
            try {
                Files.delete(record.resolve(TRASH_PATH));
                Files.delete(record);
            } catch (IOException ignored) { }
            return path;
        }
    }

    private TrashEntry moveToTrash(String path, Path target) throws IOException {
        Path folder = trashDirectory(true);
        boolean directory = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS);
        String id = UUID.randomUUID().toString();
        Path record = Files.createDirectory(folder.resolve(id));
        try {
            Files.write(record.resolve(TRASH_PATH), path.getBytes(StandardCharsets.UTF_8));
            Files.move(target, record.resolve(TRASH_ITEM), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException failure) {
            try { removeStagedTree(record); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        return new TrashEntry(id, path, directory, System.currentTimeMillis());
    }

    private Path requireDestination(String path) throws IOException {
        Path target = workspace.projectPath(path).toPath();
        requireDirectory(target.getParent());
        return target;
    }

    private Path stagingDirectory() throws IOException {
        Path parent = cacheDir.getCanonicalFile().toPath();
        if (parent.startsWith(workspace.root().toPath())) throw new IOException("Для импорта нужна папка кеша");
        Path path = parent.resolve("project-imports");
        Files.createDirectories(path);
        requireDirectory(path);
        return path;
    }

    private Path trashDirectory(boolean create) throws IOException {
        Path root = workspace.root().toPath();
        requireDirectory(root);
        Path path = root.resolve(TRASH_DIRECTORY);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!create) return null;
            Files.createDirectory(path);
        }
        requireDirectory(path);
        return path;
    }

    private static TrashEntry readTrash(Path record) throws IOException {
        Path metadata = record.resolve(TRASH_PATH);
        if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS) || Files.size(metadata) > 16384) {
            throw new IOException("Запись корзины повреждена");
        }
        String project = new String(Files.readAllBytes(metadata), StandardCharsets.UTF_8);
        SafeWorkspace.requireProjectPath(project);
        Path item = record.resolve(TRASH_ITEM);
        if (Files.isSymbolicLink(item) || !Files.exists(item, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Объект корзины недоступен");
        }
        return new TrashEntry(record.getFileName().toString(), project, Files.isDirectory(item, LinkOption.NOFOLLOW_LINKS),
                Files.getLastModifiedTime(metadata, LinkOption.NOFOLLOW_LINKS).toMillis());
    }

    private static boolean isTrashId(String id) {
        return id != null && id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    }

    private static String joined(String project, String path) {
        return project.isEmpty() ? path : project + "/" + path;
    }

    private static void requireDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Рабочая папка не найдена: " + directory.getFileName());
        }
    }

    private static String revision(Path target) throws IOException {
        MessageDigest digest = WorkspaceZipExporter.sha256Digest();
        try (Stream<Path> paths = Files.walk(target)) {
            Iterator<Path> sorted = paths.sorted().iterator();
            while (sorted.hasNext()) {
                checkInterrupted();
                Path path = sorted.next();
                BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                String row = target.relativize(path) + "\u0000" + Objects.toString(attrs.fileKey(), "") + "\u0000" +
                        attrs.lastModifiedTime() + "\u0000" + attrs.creationTime() + "\u0000" + attrs.size() + "\u0000";
                digest.update(row.getBytes(StandardCharsets.UTF_8));
            }
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
        return WorkspaceZipExporter.toHex(digest.digest());
    }

    static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Операция прервана");
    }

    private static void checkImportCancellation(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean()) throw new InterruptedIOException("Импорт остановлен до записи проекта");
        checkInterrupted();
    }

    static void removeStagedTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
