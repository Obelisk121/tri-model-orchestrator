package io.github.obelisk121.trimodel.orchestration;

import io.github.obelisk121.trimodel.model.ProposedFileChange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses fenced code blocks and target file paths from model Markdown outputs.
 */
public final class CodeChangeExtractor {

    private static final Pattern FENCE_OPEN = Pattern.compile("^\\s*```\\s*([^\\s`]*)(?:\\s+(.*))?$");
    private static final Pattern HEADER_FILE = Pattern.compile(
            "^(?:#{1,6}\\s*)?(?:\\*\\*)?(?:FILE|FILEPATH|PATH)\\s*:\\s*`?([^`*\\r\\n]+?)`?(?:\\*\\*)?\\s*$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern FIRST_LINE_COMMENT_FILE = Pattern.compile(
            "^\\s*(?:#|//|--|<!--)\\s*(?:FILE|FILEPATH|PATH)\\s*:\\s*([^\\r\\n*>]+?)(?:-->)?\\s*$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern META_FILE_ATTR = Pattern.compile(
            "(?:file|path|filename)\\s*=\\s*\"?([^\\s\"`]+)\"?",
            Pattern.CASE_INSENSITIVE
    );

    private CodeChangeExtractor() {
    }

    public static List<ProposedFileChange> extract(String markdown, String sourceLabel) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }

        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        List<ProposedFileChange> raw = new ArrayList<>();

        String pendingFilePath = null;
        int blankLinesSinceHeader = 0;

        int i = 0;
        while (i < lines.length) {
            String line = lines[i];

            Matcher headerMatcher = HEADER_FILE.matcher(line.trim());
            if (headerMatcher.matches()) {
                pendingFilePath = sanitizePath(headerMatcher.group(1));
                blankLinesSinceHeader = 0;
                i++;
                continue;
            }

            Matcher fenceMatcher = FENCE_OPEN.matcher(line);
            if (fenceMatcher.matches()) {
                String infoHead = fenceMatcher.group(1) == null ? "" : fenceMatcher.group(1).trim();
                String infoRest = fenceMatcher.group(2) == null ? "" : fenceMatcher.group(2).trim();

                String language = infoHead;
                String inlinePath = null;

                // Handle ```python:src/app.py
                int colonIdx = infoHead.indexOf(':');
                if (colonIdx > 0 && colonIdx < infoHead.length() - 1) {
                    language = infoHead.substring(0, colonIdx).trim();
                    inlinePath = sanitizePath(infoHead.substring(colonIdx + 1));
                }

                // Handle ```python file=src/app.py
                if (inlinePath == null && !infoRest.isBlank()) {
                    Matcher attr = META_FILE_ATTR.matcher(infoRest);
                    if (attr.find()) {
                        inlinePath = sanitizePath(attr.group(1));
                    } else if (looksLikeRelativeFilePath(infoRest)) {
                        inlinePath = sanitizePath(infoRest);
                    }
                }

                StringBuilder codeBuf = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith("```")) {
                    codeBuf.append(lines[i]).append("\n");
                    i++;
                }
                // Consume closing fence if present
                if (i < lines.length && lines[i].trim().startsWith("```")) {
                    i++;
                }

                String code = codeBuf.toString();
                if (code.endsWith("\n")) {
                    code = code.substring(0, code.length() - 1);
                }

                // Check first line comment inside block: # FILE: path/to/file.py
                String commentPath = null;
                if (!code.isBlank()) {
                    int nl = code.indexOf('\n');
                    String firstLine = nl >= 0 ? code.substring(0, nl) : code;
                    Matcher cm = FIRST_LINE_COMMENT_FILE.matcher(firstLine);
                    if (cm.matches()) {
                        commentPath = sanitizePath(cm.group(1));
                    }
                }

                String resolvedPath = firstNonBlank(inlinePath, pendingFilePath, commentPath);
                pendingFilePath = null;
                blankLinesSinceHeader = 0;

                if (!code.isBlank()) {
                    boolean explicit = resolvedPath != null && !resolvedPath.isBlank();
                    raw.add(new ProposedFileChange(
                            explicit ? resolvedPath : "",
                            normalizeLanguage(language),
                            code,
                            explicit,
                            sourceLabel
                    ));
                }
                continue;
            }

            if (line.isBlank()) {
                blankLinesSinceHeader++;
                if (blankLinesSinceHeader > 2) {
                    pendingFilePath = null;
                }
            } else {
                // Non-empty prose line breaks association with an earlier FILE header
                pendingFilePath = null;
                blankLinesSinceHeader = 0;
            }
            i++;
        }

        // Deduplicate explicit paths (keep the latest version for each path) while preserving order
        Map<String, ProposedFileChange> byExplicitPath = new LinkedHashMap<>();
        List<ProposedFileChange> unnamed = new ArrayList<>();
        for (ProposedFileChange change : raw) {
            if (change.hasExplicitPath()) {
                byExplicitPath.put(change.filePath(), change);
            } else {
                unnamed.add(change);
            }
        }

        List<ProposedFileChange> merged = new ArrayList<>(byExplicitPath.values());
        // Keep unnamed blocks if there are no explicit blocks, or include significant unnamed snippets too
        if (merged.isEmpty()) {
            merged.addAll(unnamed);
        } else {
            for (ProposedFileChange u : unnamed) {
                // Skip tiny bash/shell verification snippets when explicit source files exist
                String lang = u.language().toLowerCase();
                if (!lang.equals("bash") && !lang.equals("sh") && !lang.equals("shell") && !lang.equals("text")) {
                    merged.add(u);
                }
            }
        }
        return merged;
    }

    public static String sanitizePath(String rawPath) {
        if (rawPath == null) return null;
        String cleaned = rawPath.trim()
                .replace('\\', '/')
                .replaceAll("^`+|`+$", "")
                .replaceAll("^\"+|\"+$", "")
                .replaceAll("^'+|'+$", "")
                .trim();

        while (cleaned.startsWith("./")) {
            cleaned = cleaned.substring(2);
        }
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.isBlank()) {
            return null;
        }
        // Prevent path traversal outside project root
        for (String segment : cleaned.split("/")) {
            if ("..".equals(segment)) {
                return null;
            }
        }
        if (cleaned.contains("\0") || cleaned.length() > 300) {
            return null;
        }
        return cleaned;
    }

    private static boolean looksLikeRelativeFilePath(String token) {
        if (token.contains(" ") || token.length() > 200) {
            return false;
        }
        return token.contains("/") || token.matches("^[A-Za-z0-9_.-]+\\.[A-Za-z0-9]{1,10}$");
    }

    private static String normalizeLanguage(String lang) {
        if (lang == null) return "";
        String l = lang.trim().toLowerCase();
        if ("py".equals(l)) return "python";
        if ("js".equals(l)) return "javascript";
        if ("ts".equals(l)) return "typescript";
        if ("yml".equals(l)) return "yaml";
        return l;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
