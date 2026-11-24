package io.github.obelisk121.trimodel.ui;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.UIUtil;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight, theme-aware Markdown-to-HTML renderer for PyCharm's Swing JEditorPane.
 */
public final class MarkdownHtmlRenderer {
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern ITALIC = Pattern.compile("(?<!\\*)\\*([^*]+)\\*(?!\\*)");

    private MarkdownHtmlRenderer() {
    }

    public static String toHtmlDocument(String markdown) {
        return toHtmlDocument(markdown, isDarkThemeSafe());
    }

    public static String toHtmlDocument(String markdown, boolean dark) {
        String bg = dark ? "#1E1F22" : "#FFFFFF";
        String fg = dark ? "#DFE1E5" : "#1E1F22";
        String muted = dark ? "#9DA0A8" : "#5A5D6B";
        String codeBg = dark ? "#2B2D30" : "#F3F4F6";
        String border = dark ? "#393B40" : "#DFE1E5";
        String accent = dark ? "#60A5FA" : "#2563EB";
        String quoteBg = dark ? "#252830" : "#F7F8FA";

        String body = renderBody(markdown == null ? "" : markdown, dark);

        return "<html><head><style>"
                + "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; font-size: 12px; "
                + "color: " + fg + "; background-color: " + bg + "; margin: 10px 12px; line-height: 1.45; }"
                + "h1 { font-size: 16px; margin: 12px 0 6px 0; border-bottom: 1px solid " + border + "; padding-bottom: 4px; }"
                + "h2 { font-size: 14px; margin: 12px 0 5px 0; color: " + accent + "; }"
                + "h3 { font-size: 13px; margin: 10px 0 4px 0; }"
                + "h4 { font-size: 12px; margin: 8px 0 4px 0; color: " + muted + "; }"
                + "p { margin: 5px 0; }"
                + "ul, ol { margin: 4px 0 6px 18px; padding: 0; }"
                + "li { margin: 2px 0; }"
                + "code { font-family: 'JetBrains Mono', Menlo, Consolas, monospace; font-size: 11px; "
                + "background-color: " + codeBg + "; padding: 1px 4px; }"
                + "pre { font-family: 'JetBrains Mono', Menlo, Consolas, monospace; font-size: 11px; "
                + "background-color: " + codeBg + "; border: 1px solid " + border + "; padding: 8px 10px; margin: 6px 0; }"
                + ".code-lang { font-family: -apple-system, sans-serif; font-size: 10px; color: " + muted + "; "
                + "margin-top: 6px; margin-bottom: -4px; font-weight: bold; }"
                + "blockquote { margin: 6px 0; padding: 6px 10px; border-left: 3px solid " + accent + "; background-color: " + quoteBg + "; }"
                + "table { border-collapse: collapse; margin: 8px 0; width: 100%; }"
                + "th, td { border: 1px solid " + border + "; padding: 4px 8px; text-align: left; font-size: 11px; }"
                + "th { background-color: " + codeBg + "; }"
                + "hr { border: 0; border-top: 1px solid " + border + "; margin: 10px 0; }"
                + ".badge-crit { color: #FFFFFF; background-color: #DC2626; padding: 1px 4px; font-weight: bold; }"
                + ".badge-high { color: #FFFFFF; background-color: #D97706; padding: 1px 4px; font-weight: bold; }"
                + ".badge-med { color: #FFFFFF; background-color: #2563EB; padding: 1px 4px; font-weight: bold; }"
                + ".badge-ok { color: #FFFFFF; background-color: #059669; padding: 1px 4px; font-weight: bold; }"
                + "</style></head><body>"
                + body
                + "</body></html>";
    }

    static String renderBody(String markdown, boolean dark) {
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        StringBuilder html = new StringBuilder();
        boolean inCodeBlock = false;
        boolean inUl = false;
        boolean inOl = false;
        StringBuilder codeBuf = new StringBuilder();
        String codeLang = "";

        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();

            if (inCodeBlock) {
                if (trimmed.startsWith("```")) {
                    if (!codeLang.isBlank()) {
                        html.append("<div class='code-lang'>").append(escapeHtml(codeLang)).append("</div>");
                    }
                    html.append("<pre>").append(escapeHtml(codeBuf.toString())).append("</pre>");
                    codeBuf.setLength(0);
                    codeLang = "";
                    inCodeBlock = false;
                } else {
                    codeBuf.append(line).append("\n");
                }
                i++;
                continue;
            }

            if (trimmed.startsWith("```")) {
                inUl = closeList(html, inUl, "ul");
                inOl = closeList(html, inOl, "ol");
                inCodeBlock = true;
                codeLang = trimmed.substring(3).trim();
                codeBuf.setLength(0);
                i++;
                continue;
            }

            // Markdown table block
            if (trimmed.startsWith("|") && trimmed.endsWith("|") && i + 1 < lines.length
                    && isTableSeparator(lines[i + 1].trim())) {
                inUl = closeList(html, inUl, "ul");
                inOl = closeList(html, inOl, "ol");
                List<String> tableLines = new ArrayList<>();
                tableLines.add(trimmed);
                i += 2; // skip separator
                while (i < lines.length && lines[i].trim().startsWith("|") && lines[i].trim().endsWith("|")) {
                    tableLines.add(lines[i].trim());
                    i++;
                }
                renderTable(html, tableLines);
                continue;
            }

            if (trimmed.isEmpty()) {
                inUl = closeList(html, inUl, "ul");
                inOl = closeList(html, inOl, "ol");
                i++;
                continue;
            }

            if (trimmed.matches("^[-*_]{3,}$")) {
                inUl = closeList(html, inUl, "ul");
                inOl = closeList(html, inOl, "ol");
                html.append("<hr/>");
                i++;
                continue;
            }

            if (trimmed.startsWith("#")) {
                inUl = closeList(html, inUl, "ul");
                inOl = closeList(html, inOl, "ol");
                int level = 0;
                while (level < trimmed.length() && level < 4 && trimmed.charAt(level) == '#') {
                    level++;
                }
                String title = trimmed.substring(level).trim();
                html.append("<h").append(level).append(">")
                        .append(formatInline(title))
                        .append("</h").append(level).append(">");
                i++;
                continue;
            }

            if (trimmed.startsWith(">")) {
                inUl = closeList(html, inUl, "ul");
                inOl = closeList(html, inOl, "ol");
                String quoteText = trimmed.substring(1).trim();
                html.append("<blockquote>").append(formatInline(quoteText)).append("</blockquote>");
                i++;
                continue;
            }

            if (trimmed.matches("^[-*+]\\s+.*")) {
                inOl = closeList(html, inOl, "ol");
                if (!inUl) {
                    html.append("<ul>");
                    inUl = true;
                }
                html.append("<li>").append(formatInline(trimmed.substring(2).trim())).append("</li>");
                i++;
                continue;
            }

            if (trimmed.matches("^\\d+\\.\\s+.*")) {
                inUl = closeList(html, inUl, "ul");
                if (!inOl) {
                    html.append("<ol>");
                    inOl = true;
                }
                String item = trimmed.replaceFirst("^\\d+\\.\\s+", "");
                html.append("<li>").append(formatInline(item)).append("</li>");
                i++;
                continue;
            }

            inUl = closeList(html, inUl, "ul");
            inOl = closeList(html, inOl, "ol");
            html.append("<p>").append(formatInline(trimmed)).append("</p>");
            i++;
        }

        if (inCodeBlock) {
            html.append("<pre>").append(escapeHtml(codeBuf.toString())).append("</pre>");
        }
        closeList(html, inUl, "ul");
        closeList(html, inOl, "ol");
        return html.toString();
    }

    private static boolean closeList(StringBuilder html, boolean open, String tag) {
        if (open) {
            html.append("</").append(tag).append(">");
        }
        return false;
    }

    private static boolean isTableSeparator(String line) {
        return line.startsWith("|") && line.endsWith("|") && line.replaceAll("[|:\\-\\s]", "").isEmpty();
    }

    private static void renderTable(StringBuilder html, List<String> rows) {
        html.append("<table>");
        for (int r = 0; r < rows.size(); r++) {
            String row = rows.get(r);
            String inner = row.substring(1, row.length() - 1);
            String[] cells = inner.split("\\|", -1);
            html.append("<tr>");
            String tag = (r == 0) ? "th" : "td";
            for (String cell : cells) {
                html.append("<").append(tag).append(">")
                        .append(formatInline(cell.trim()))
                        .append("</").append(tag).append(">");
            }
            html.append("</tr>");
        }
        html.append("</table>");
    }

    static String formatInline(String text) {
        String escaped = escapeHtml(text);

        // Inline code spans first using placeholders so bold/italic don't corrupt code
        List<String> codeSpans = new ArrayList<>();
        Matcher codeMatcher = INLINE_CODE.matcher(escaped);
        StringBuilder afterCode = new StringBuilder();
        while (codeMatcher.find()) {
            int idx = codeSpans.size();
            codeSpans.add("<code>" + codeMatcher.group(1) + "</code>");
            codeMatcher.appendReplacement(afterCode, "@@CODESPAN_" + idx + "@@");
        }
        codeMatcher.appendTail(afterCode);

        String formatted = BOLD.matcher(afterCode.toString()).replaceAll("<b>$1</b>");
        formatted = ITALIC.matcher(formatted).replaceAll("<i>$1</i>");

        // Severity & verdict badges
        formatted = formatted
                .replace("[CRITICAL]", "<span class='badge-crit'>CRITICAL</span>")
                .replace("[HIGH]", "<span class='badge-high'>HIGH</span>")
                .replace("[MAJOR]", "<span class='badge-high'>MAJOR</span>")
                .replace("[MEDIUM]", "<span class='badge-med'>MEDIUM</span>")
                .replace("[MINOR]", "<span class='badge-med'>MINOR</span>")
                .replace("[LOW]", "<span class='badge-ok'>LOW</span>")
                .replace("VERDICT: APPROVE", "<span class='badge-ok'>VERDICT: APPROVE</span>")
                .replace("VERDICT: REVISE", "<span class='badge-high'>VERDICT: REVISE</span>");

        for (int i = 0; i < codeSpans.size(); i++) {
            formatted = formatted.replace("@@CODESPAN_" + i + "@@", codeSpans.get(i));
        }
        return formatted;
    }

    public static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static boolean isDarkThemeSafe() {
        try {
            Color bg = UIUtil.getPanelBackground();
            if (bg != null) {
                double luminance = 0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue();
                return luminance < 128.0;
            }
            return !JBColor.isBright();
        } catch (Throwable ignored) {
            return true;
        }
    }
}
