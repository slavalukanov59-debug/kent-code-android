package ai.kent.code;

import android.content.Context;

import java.io.File;
import java.io.IOException;

/** In-process owner of the local stores so Activity recreation cannot race recovery. */
final class KentLocalData {
    private static KentLocalData instance;
    final SafeWorkspace workspace;
    final SecureKeyStore keys;
    final ChatJournal chat;
    final TokenMeter tokens;
    final MissionStore missions;
    final ProjectService projects;
    final DiagnosticsStore diagnostics;
    final TermuxBridge officialTermux;
    final LocalTermuxBridge localTermux;
    final HeadlessTermux headless;
    final TerminalTools terminal;

    private KentLocalData(Context context) throws IOException {
        workspace = new SafeWorkspace(new File(context.getFilesDir(), "workspace"));
        keys = new SecureKeyStore(context);
        chat = new ChatJournal(context);
        tokens = new TokenMeter(context);
        missions = new MissionStore(context);
        projects = new ProjectService(workspace, context.getCacheDir());
        diagnostics = new DiagnosticsStore(context.getFilesDir());
        officialTermux = new TermuxBridge(context);
        localTermux = new LocalTermuxBridge(keys);
        headless = new HeadlessTermux(keys, officialTermux, localTermux);
        terminal = new TerminalTools(keys, officialTermux, localTermux, headless, diagnostics,
                context.getCacheDir());
        if (!missions.loadFailed()) missions.recoverInterrupted();
        tokens.recoverInterrupted();
    }

    static synchronized KentLocalData get(Context context) throws IOException {
        if (instance == null) instance = new KentLocalData(context.getApplicationContext());
        return instance;
    }
}
