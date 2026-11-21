package ai.kent.code;

import java.io.IOException;

/** User-initiated text transfer between one GitHub file and the private project. */
final class GitHubWorkspaceTransfer {
    private GitHubWorkspaceTransfer() {}

    static void importFile(SafeWorkspace workspace, String path, String text) throws IOException {
        checkText(text);
        if (!workspace.applyReviewedChange(path, null, text))
            throw new IOException("Файл в рабочей области уже существует. Укажите новый путь.");
    }

    static String readFile(SafeWorkspace workspace, String path) throws IOException {
        String text = workspace.read(path);
        checkText(text);
        return text;
    }

    private static void checkText(String text) throws IOException {
        if (!text.equals(SensitiveData.redact(text)))
            throw new IOException("В тексте найдены данные, похожие на секрет. Удалите их перед переносом.");
    }
}
