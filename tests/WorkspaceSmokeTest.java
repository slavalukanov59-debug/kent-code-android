package ai.kent.code;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class WorkspaceSmokeTest {
    private static void check(boolean pass, String why) {
        if (!pass) throw new AssertionError(why);
    }
    public static void main(String[] ignored) throws Exception {
        File root = Files.createTempDirectory("kent-workspace-").toFile();
        SafeWorkspace ws = new SafeWorkspace(root);
        ws.write("src/hello.py", "print('Hello, KENT')\n");
        check(ws.read("src/hello.py").contains("Hello, KENT"), "read failed");
        check(ws.tree().contains("src/hello.py"), "tree failed");
        check(ws.search("Hello").contains("src/hello.py"), "search failed");
        String edited = ws.editPreview("src/hello.py", "Hello", "Привет", false);
        ws.write("src/hello.py", edited);
        check(ws.read("src/hello.py").contains("Привет"), "edit failed");
        boolean blocked = false;
        try { ws.write("../secret.txt", "bad"); } catch (IOException good) { blocked = true; }
        check(blocked, "traversal not blocked");
        blocked = false;
        try { ws.write(".env", "sk-test"); } catch (IOException good) { blocked = true; }
        check(blocked, "secret write not blocked");
        blocked = false;
        try { ws.write("huge.txt", "x".repeat(SafeWorkspace.MAX_WRITE_BYTES + 1)); }
        catch (IOException good) { blocked = true; }
        check(blocked, "oversized file not blocked");
        ws.delete("src/hello.py");
        check(!ws.resolve("src/hello.py").exists(), "delete failed");
        System.out.println("PASS: read/write/edit/search/tree/delete, traversal/secret/size protections");
    }
}
