package ai.kent.code;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;

/** Publishes an already verified ZIP to a document created by the system picker. */
final class WorkspaceZipDestination {
    private WorkspaceZipDestination() { }

    static void save(ContentResolver resolver, Uri target, WorkspaceZipExporter.Archive archive)
            throws IOException {
        if (!ContentResolver.SCHEME_CONTENT.equals(target.getScheme())) {
            throw new IOException("Android не вернул доступный документ для сохранения ZIP.");
        }
        try {
            try (FileChannel staged = FileChannel.open(archive.file.toPath(), StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS)) {
                InputStream source = Channels.newInputStream(staged);
                archive.verifyCopy(source);
                staged.position(0);
                try (OutputStream destination = resolver.openOutputStream(target, "wt")) {
                    if (destination == null) throw new IOException("Не удалось открыть документ для записи.");
                    byte[] buffer = new byte[64 * 1024];
                    long remaining = archive.bytes;
                    int count;
                    while ((count = source.read(buffer)) != -1) {
                        if (count > remaining) throw new IOException("Подготовленный ZIP изменился.");
                        destination.write(buffer, 0, count);
                        remaining -= count;
                    }
                    if (remaining != 0) throw new IOException("Подготовленный ZIP изменился.");
                }
            }
            try (InputStream saved = resolver.openInputStream(target)) {
                if (saved == null) throw new IOException("Не удалось прочитать сохранённый документ.");
                archive.verifyCopy(saved);
            }
        } catch (IOException | RuntimeException failure) {
            if (discard(resolver, target)) {
                throw new IOException("Не удалось записать или проверить ZIP. Документ удалён.", failure);
            }
            throw new IOException("Не удалось записать или проверить ZIP. Хранилище не удалило "
                    + "незавершённый документ. Его можно удалить в приложении «Файлы».", failure);
        }
    }

    static boolean discard(ContentResolver resolver, Uri target) {
        if (!ContentResolver.SCHEME_CONTENT.equals(target.getScheme())) return false;
        try {
            return DocumentsContract.deleteDocument(resolver, target);
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }
}
