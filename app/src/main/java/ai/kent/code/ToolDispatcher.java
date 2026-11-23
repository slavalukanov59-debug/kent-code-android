package ai.kent.code;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.function.Supplier;

/** Connects Developer Mode to the existing workspace, GitHub account and Termux transport. */
final class ToolDispatcher {
    static final class Result {
        final String text;
        final boolean review;
        final boolean failed;
        final String verificationOutcome;
        final String failureKey;

        Result(String text, boolean review, boolean failed) {
            this(text, review, failed, "", "");
        }

        Result(String text, boolean review, boolean failed, String verificationOutcome, String failureKey) {
            this.text = text;
            this.review = review;
            this.failed = failed;
            this.verificationOutcome = verificationOutcome;
            this.failureKey = failureKey;
        }
    }

    private final GitHubAgentTools github;
    private final ProjectTools project;
    private final ProjectService projects;
    private final TerminalTools terminal;
    private final MissionStore missions;
    private final KentAgent.Events events;
    private final String missionId;

    ToolDispatcher(ProjectService projects, TerminalTools terminal, MissionStore missions, KentAgent.Events events,
                   String missionId, GitHubAgentTools.ClientSource githubSource, File exportCache,
                   Supplier<String[]> secretValues) {
        this.projects = projects;
        this.terminal = terminal;
        this.missions = missions;
        this.events = events;
        this.missionId = missionId;
        github = new GitHubAgentTools(githubSource, missions, events);
        project = new ProjectTools(projects, missions, events, githubSource, exportCache, secretValues);
    }

    void addTools(JSONArray output) throws JSONException {
        GitHubAgentTools.addSchemas(output);
        ProjectTools.addTools(output);
        TerminalTools.addTools(output);
    }

    boolean handles(String name) {
        return name.startsWith("github_") || name.startsWith("project_") || TerminalTools.handles(name);
    }

    Result execute(String name, JSONObject args, String apiKey) {
        if (name.startsWith("github_")) {
            GitHubAgentTools.Result result = github.execute(name, args, apiKey);
            return new Result(result.text, result.review, result.failed);
        }
        if (name.startsWith("project_")) return project.execute(name, args, apiKey);
        return executeTerminal(name, args, apiKey);
    }

    void cancel() {
        github.cancel();
        project.cancel();
        terminal.cancelMission(missionId);
    }

    private Result executeTerminal(String name, JSONObject args, String apiKey) {
        MissionStore.Mission mission = missions.active();
        String projectId = mission == null ? "" : mission.projectId;
        boolean run = "terminal_run".equals(name);
        String action = missions.startAction(name, projectId, run);
        events.missionChanged();
        JSONObject response;
        try {
            boolean localProject = run && "project".equals(args.optString("source", "project"));
            SafeWorkspace workspace = localProject && !projectId.isEmpty() ? projects.projectWorkspace(projectId) : null;
            response = terminal.execute(name, args, workspace, events, missionId);
        } catch (Exception e) {
            String detail = e instanceof IOException ? redact(e.getMessage(), apiKey)
                    : "Не удалось выполнить запрос Termux.";
            missions.finishAction(action, MissionStore.FAILED, detail, false);
            return new Result(detail, false, true);
        }
        String status = response.optString("status");
        boolean inProgress = "PREPARING".equals(status) || "RUNNING".equals(status);
        boolean unknown = "UNKNOWN".equals(status) || !inProgress && response.optBoolean("may_still_be_running");
        boolean denied = "DENIED".equals(status);
        boolean ok = response.optBoolean("ok") && !unknown;
        String outcome = unknown ? MissionStore.UNKNOWN : denied ? MissionStore.DENIED
                : ok ? MissionStore.SUCCEEDED : MissionStore.FAILED;
        String result = redact(response.toString(), apiKey);
        missions.finishAction(action, outcome, result, false);
        boolean checkResult = !unknown && (run || "terminal_get_exit_code".equals(name) || "terminal_get_output".equals(name))
                && "EXITED".equals(status) && !response.isNull("exit_code");
        String verified = checkResult ? (response.optInt("exit_code", -1) == 0
                ? MissionStore.SUCCEEDED : MissionStore.FAILED) : "";
        return new Result(result, unknown || denied, !ok, verified, response.optString("failure_key"));
    }

    private static String redact(String text, String apiKey) {
        return SensitiveData.redact(text == null || text.isEmpty() ? "Termux не вернул описание." : text, apiKey);
    }
}
