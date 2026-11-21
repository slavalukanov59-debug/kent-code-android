package ai.kent.code;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class GitHubWorkspaceTransferTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void preservesTextAndNeverReplacesAnExistingPath() throws Exception {
        SafeWorkspace workspace = new SafeWorkspace(temp.newFolder("workspace"));
        String content = "исходник\r\n";
        GitHubWorkspaceTransfer.importFile(workspace, "docs/readme.md", content);
        assertEquals(content, GitHubWorkspaceTransfer.readFile(workspace, "docs/readme.md"));
        assertThrows(IOException.class,
                () -> GitHubWorkspaceTransfer.importFile(workspace, "docs/readme.md", "замена"));
        assertEquals(content, workspace.read("docs/readme.md"));
    }

    @Test public void blocksCredentialTextAndSensitivePathsInBothDirections() throws Exception {
        SafeWorkspace workspace = new SafeWorkspace(temp.newFolder("workspace"));
        String credential = "token = ghp_" + "example".repeat(6);
        assertThrows(IOException.class,
                () -> GitHubWorkspaceTransfer.importFile(workspace, "pasted.txt", credential));
        assertThrows(IOException.class,
                () -> GitHubWorkspaceTransfer.importFile(workspace, ".env", "public text"));
        assertThrows(IOException.class,
                () -> GitHubWorkspaceTransfer.importFile(workspace, "../outside.txt", "public text"));
        workspace.write("settings.txt", credential);
        assertThrows(IOException.class,
                () -> GitHubWorkspaceTransfer.readFile(workspace, "settings.txt"));
    }
}
