package ai.kent.code;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Styles one page at a time so opening source does not lay out the entire file. */
final class CodeViewer {
    private static final int STRING = MainActivity.GREEN;
    private static final int COMMENT = MainActivity.MUTED;
    private static final int NUMBER = MainActivity.GOLD;
    private static final int REMOVED = 0xFFFF8DA1;
    private static final String JAVA = "abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for if implements import instanceof int interface long native new package private protected public record return short static super switch synchronized this throw throws transient try var void volatile while yield";
    private static final String KOTLIN = "abstract actual annotation as break by catch class companion const constructor continue data do else enum expect external final finally for fun get if import in infix init inline interface internal is lateinit noinline object open operator out override package private protected public reified return sealed set suspend tailrec this throw try typealias val var vararg when where while";
    private static final String PYTHON = "and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield True False None";
    private static final String CPP = "alignas auto bool break case catch char class concept const constexpr continue decltype default delete do double else enum explicit extern float for friend if inline int long namespace new noexcept nullptr operator override private protected public register requires return short signed sizeof static struct switch template this throw try typedef typename union unsigned using virtual void volatile while";
    private static final String JS = "async await break case catch class const continue debugger default delete do else export extends finally for function if import in instanceof let new of return static super switch this throw try typeof var void while with yield undefined";
    private static final Pattern XML = Pattern.compile("(?<comment><!--[\\s\\S]*?(?:-->|\\z))|(?<string>\"[^\"\\r\\n]*+\"?|'[^'\\r\\n]*+'?)|(?<tag></?[\\w:.-]+|/?>|(?<![\\w:.-])[\\w:.-]++(?=\\s*=))");
    private static final Pattern HUNK = Pattern.compile("^@@ -[0-9]+(?:,([0-9]{1,9}))? \\+[0-9]+(?:,([0-9]{1,9}))? @@.*$");
    private static final Pattern JAVA_PATTERN = language(JAVA, false);
    private static final Pattern KOTLIN_PATTERN = language(KOTLIN, false);
    private static final Pattern PYTHON_PATTERN = language(PYTHON, true);
    private static final Pattern CPP_PATTERN = language(CPP, false);
    private static final Pattern JS_PATTERN = language(JS, false);

    private CodeViewer() { }

    private static Pattern language(String words, boolean python) {
        String comment = python ? "#[^\\r\\n]*" : "//[^\\r\\n]*|/\\*[\\s\\S]*?(?:\\*/|\\z)";
        return Pattern.compile("(?<comment>" + comment + ")"
                + "|(?<string>\"\"\"[\\s\\S]*?(?:\"\"\"|\\z)|'''[\\s\\S]*?(?:'''|\\z)"
                + "|\"(?:\\\\.|[^\"\\\\\\r\\n])*+\"?|'(?:\\\\.|[^'\\\\\\r\\n])*+'?"
                + "|`(?:\\\\.|[^`\\\\])*+`?)"
                + "|(?<tag>\\b(?:" + words.replace(' ', '|') + "|true|false|null)\\b)"
                + "|(?<number>\\b(?:0[xX][0-9a-fA-F]+|\\d+(?:\\.\\d+)?)\\b)");
    }

    static CharSequence highlight(String path, String source) {
        String name = path.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) return source;
        Pattern lexer = switch (name.substring(dot + 1)) {
            case "java" -> JAVA_PATTERN;
            case "kt", "kts" -> KOTLIN_PATTERN;
            case "py", "pyw" -> PYTHON_PATTERN;
            case "cpp", "cxx", "cc", "c", "h", "hpp", "hxx" -> CPP_PATTERN;
            case "js", "mjs", "cjs", "jsx", "ts", "tsx" -> JS_PATTERN;
            case "xml" -> XML;
            default -> null;
        };
        if (lexer == null) return source;
        SpannableString highlighted = new SpannableString(source);
        Matcher tokens = lexer.matcher(source);
        while (tokens.find()) {
            int color = tokens.start("comment") >= 0 ? COMMENT : tokens.start("string") >= 0 ? STRING
                    : tokens.start("tag") >= 0 ? MainActivity.BLUE : NUMBER;
            color(highlighted, color, tokens.start(), tokens.end());
        }
        return highlighted;
    }

    static TextView sourceText(Activity activity, CharSequence text) {
        TextView body = new TextView(activity);
        body.setText(text);
        body.setTextColor(MainActivity.WHITE);
        body.setTextSize(13);
        body.setTypeface(Typeface.MONOSPACE);
        body.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10));
        body.setHorizontallyScrolling(true);
        body.setTextIsSelectable(true);
        body.setTextDirection(View.TEXT_DIRECTION_LTR);
        return body;
    }

    static void showDiff(Activity activity, String path, String patch, String reason) {
        if (patch.isEmpty()) {
            new AlertDialog.Builder(activity).setTitle(path).setMessage("Сохранённых изменений нет.")
                    .setPositiveButton("Закрыть", null).show();
            return;
        }
        int additions = 0;
        int deletions = 0;
        int oldRemaining = 0;
        int newRemaining = 0;
        SpannableString diff = new SpannableString(patch);
        int start = 0;
        String[] lines = patch.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean fileHeader = line.startsWith("--- ") && i + 2 < lines.length
                    && lines[i + 1].startsWith("+++ ") && lines[i + 2].startsWith("@@ ");
            if (fileHeader || line.startsWith("diff --git ")) {
                oldRemaining = 0;
                newRemaining = 0;
            } else if (line.startsWith("@@")) {
                Matcher header = HUNK.matcher(line);
                boolean valid = header.matches();
                oldRemaining = valid ? hunkSize(header.group(1)) : 0;
                newRemaining = valid ? hunkSize(header.group(2)) : 0;
                color(diff, MainActivity.BLUE, start, start + line.length());
            } else if (newRemaining > 0 && line.startsWith("+")) {
                newRemaining--;
                additions++;
                color(diff, MainActivity.GREEN, start, start + line.length());
            } else if (oldRemaining > 0 && line.startsWith("-")) {
                oldRemaining--;
                deletions++;
                color(diff, REMOVED, start, start + line.length());
            } else if (oldRemaining > 0 && newRemaining > 0 && line.startsWith(" ")) {
                oldRemaining--;
                newRemaining--;
            }
            start += line.length() + 1;
        }
        android.widget.LinearLayout layout = new android.widget.LinearLayout(activity);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        TextView summary = new TextView(activity);
        String heading = "+" + additions + " добавлено    −" + deletions + " удалено";
        if (reason != null && !reason.trim().isEmpty()) heading += "\nПричина изменения\n" + reason;
        summary.setText(heading);
        summary.setPadding(dp(activity, 14), dp(activity, 8), dp(activity, 14), dp(activity, 8));
        layout.addView(summary);
        HorizontalScrollView horizontal = new HorizontalScrollView(activity);
        horizontal.addView(sourceText(activity, diff), ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ScrollView vertical = new ScrollView(activity);
        vertical.addView(horizontal);
        layout.addView(vertical);
        new AlertDialog.Builder(activity).setTitle(path).setView(layout)
                .setPositiveButton("Закрыть", null).show();
    }

    private static void color(SpannableString text, int color, int start, int end) {
        if (end > start) text.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static int hunkSize(String value) {
        return value == null ? 1 : Integer.parseInt(value);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
