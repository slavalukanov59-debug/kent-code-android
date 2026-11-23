package ai.kent.code;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Developer-mode commands expressed as a catalog entry and data, never shell text. */
final class TerminalCommand {
    static final List<String> PROGRAMS = Collections.unmodifiableList(Arrays.asList(
            "python3", "java", "javac", "c++", "clang++", "g++", "git", "node", "gradle", "kotlinc"));
    private static final String PYTHON_SYNTAX =
            "import ast,sys,tokenize; p=sys.argv[1]; ast.parse(tokenize.open(p).read(),filename=p)";
    private static final String PYTHON_TESTS = "import sys,unittest\n"
            + "suite=unittest.defaultTestLoader.discover('.')\n"
            + "if not suite.countTestCases():\n print('No tests discovered',file=sys.stderr); sys.exit(4)\n"
            + "result=unittest.TextTestRunner().run(suite)\n"
            + "sys.exit(4 if result.testsRun==len(result.skipped) else 0 if result.wasSuccessful() else 1)";
    private static final List<String> GRADLE_TASKS = Arrays.asList(
            "test", "check", "lint", "testDebugUnitTest", "lintDebug", "assembleDebug");

    final String program;
    final String check;
    final List<String> inputs;
    final List<String> argv;
    final boolean runsProjectCode;
    final boolean needsSnapshot;

    TerminalCommand(String program, String check, List<String> inputs) throws IOException {
        this.program = program;
        this.check = check;
        this.inputs = Collections.unmodifiableList(new ArrayList<>(inputs));
        if (!PROGRAMS.contains(program)) throw new IOException("Исполняемый файл отсутствует в списке инструментов.");
        List<String> command = new ArrayList<>();
        command.add(program);
        boolean exec = false;
        boolean snapshot = false;
        if (check.equals("version") && inputs.isEmpty()) {
            command.add(Arrays.asList("java", "javac", "kotlinc").contains(program) ? "-version" : "--version");
        } else if (program.equals("python3") && check.equals("syntax") && inputs.size() == 1) {
            command.addAll(Arrays.asList("-I", "-c", PYTHON_SYNTAX, source(inputs.get(0), ".py")));
        } else if (program.equals("python3") && check.equals("tests") && inputs.isEmpty()) {
            command.addAll(Arrays.asList("-I", "-c", PYTHON_TESTS));
            snapshot = exec = true;
        } else if (program.equals("node") && check.equals("syntax") && inputs.size() == 1) {
            command.addAll(Arrays.asList("--check", source(inputs.get(0), ".js", ".mjs", ".cjs")));
        } else if (Arrays.asList("c++", "clang++", "g++").contains(program)
                && (check.equals("syntax") || check.equals("compile")) && !inputs.isEmpty()) {
            command.add("-std=c++17");
            if (check.equals("syntax")) command.add("-fsyntax-only");
            else {
                command.addAll(Arrays.asList("-o", ".kent-check-program"));
                snapshot = true;
            }
            for (String file : inputs) command.add(source(file, ".cpp", ".cc", ".cxx", ".c++"));
        } else if (program.equals("javac") && check.equals("compile") && !inputs.isEmpty()) {
            command.addAll(Arrays.asList("-proc:none", "-d", ".kent-check-classes"));
            for (String file : inputs) command.add(source(file, ".java"));
            snapshot = true;
        } else if (program.equals("kotlinc") && check.equals("compile") && !inputs.isEmpty()) {
            command.addAll(Arrays.asList("-d", ".kent-check-classes"));
            for (String file : inputs) command.add(source(file, ".kt"));
            snapshot = true;
        } else if (program.equals("gradle") && check.equals("gradle") && !inputs.isEmpty()
                && GRADLE_TASKS.containsAll(inputs)) {
            command.addAll(Arrays.asList("--no-daemon", "--console=plain", "--rerun-tasks", "--no-build-cache"));
            command.addAll(inputs);
            snapshot = exec = true;
        } else {
            throw new IOException("Для выбранного инструмента недоступна такая проверка или набор входных файлов.");
        }
        long bytes = 0;
        for (String part : command) bytes += part.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1L;
        if (bytes > 16_000) throw new IOException("Аргументы превышают размер запроса к мосту. Разделите запуск.");
        argv = Collections.unmodifiableList(command);
        runsProjectCode = exec;
        needsSnapshot = snapshot;
    }

    private static String source(String value, String... extensions) throws IOException {
        String lower = value.toLowerCase(Locale.ROOT);
        boolean extension = false;
        for (String expected : extensions) extension |= lower.endsWith(expected);
        if (!extension || value.isEmpty() || value.startsWith("-") || value.startsWith("@")
                || value.equals(".") || value.contains(":") || value.contains("\0"))
            throw new IOException("Укажите допустимый относительный путь к исходному файлу.");
        CommandResult.workDir(value, false);
        for (String segment : lower.split("/")) {
            if (segment.startsWith(".kent-") || segment.equals(".git") || segment.equals(".gradle")
                    || SafeWorkspace.isCredentialName(segment))
                throw new IOException("Служебные файлы и файлы с секретами недоступны.");
        }
        return value;
    }

    String display() {
        StringBuilder command = new StringBuilder();
        for (String part : argv) {
            if (command.length() > 0) command.append(' ');
            command.append(part.matches("[a-zA-Z0-9_./+=-]+") ? part : "'" + part.replace("'", "'\\''") + "'");
        }
        return command.toString();
    }
}
