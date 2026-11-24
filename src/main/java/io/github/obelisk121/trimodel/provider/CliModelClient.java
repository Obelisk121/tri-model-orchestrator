package io.github.obelisk121.trimodel.provider;

import com.intellij.ide.BrowserUtil;
import io.github.obelisk121.trimodel.model.ChatMessage;
import io.github.obelisk121.trimodel.model.ModelProviderType;
import io.github.obelisk121.trimodel.model.ModelRequest;
import io.github.obelisk121.trimodel.model.ModelResponse;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes a local AI CLI tool (e.g., claude, codex, gemini) without shell interpolation.
 * Automatically resolves macOS/Linux Homebrew and user-local executable paths even when
 * PyCharm launches with a minimal GUI PATH.
 */
public final class CliModelClient implements ModelClient {
    private static final Pattern URL_PATTERN = Pattern.compile("https://[^\\s\"'<>\\])]+");

    private final ModelProviderType providerType;
    private final String executable;
    private final String argsTemplate;
    private final String modelLabel;

    public CliModelClient(ModelProviderType providerType, String executable, String argsTemplate, String modelLabel) {
        this.providerType = providerType;
        this.executable = (executable == null || executable.isBlank())
                ? providerType.getDefaultCliExecutable() : executable.trim();
        this.argsTemplate = argsTemplate == null ? providerType.getDefaultCliArgs() : argsTemplate.trim();
        this.modelLabel = (modelLabel == null || modelLabel.isBlank())
                ? "cli:" + this.executable : modelLabel.trim();
    }

    @Override
    public ModelProviderType getProviderType() {
        return providerType;
    }

    @Override
    public String getModelId() {
        return modelLabel;
    }

    @Override
    public ModelResponse generate(ModelRequest request, BooleanSupplier isCancelled) {
        long startNanos = System.nanoTime();
        if (isCancelled != null && isCancelled.getAsBoolean()) {
            return ModelResponse.error(providerType, modelLabel, "Cancelled", 0);
        }

        String resolvedExe = resolveExecutable(executable);
        if (resolvedExe == null) {
            return ModelResponse.error(providerType, modelLabel,
                    "CLI '" + executable + "' was not found. Install it with: " + providerType.getCliInstallHint(),
                    elapsedMs(startNanos));
        }

        String combinedPrompt = formatCombinedPrompt(request);
        List<String> command = new ArrayList<>();
        command.add(resolvedExe);

        boolean promptInjectedViaArg = false;
        for (String token : tokenizeArgs(argsTemplate)) {
            if (token.contains("{prompt}")) {
                command.add(token.replace("{prompt}", combinedPrompt));
                promptInjectedViaArg = true;
            } else {
                command.add(token);
            }
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        enrichPathForGuiApps(pb.environment());

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return ModelResponse.error(providerType, modelLabel,
                    "Failed to launch CLI '" + resolvedExe + "': " + e.getMessage(),
                    elapsedMs(startNanos));
        }

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread outThread = startReaderThread(process.getInputStream(), stdout);
        Thread errThread = startReaderThread(process.getErrorStream(), stderr);

        try {
            if (!promptInjectedViaArg) {
                try (OutputStream stdin = process.getOutputStream()) {
                    stdin.write(combinedPrompt.getBytes(StandardCharsets.UTF_8));
                    stdin.flush();
                } catch (IOException ignored) {
                }
            } else {
                try {
                    process.getOutputStream().close();
                } catch (IOException ignored) {
                }
            }

            long deadlineNanos = startNanos + TimeUnit.SECONDS.toNanos(Math.max(15, request.timeoutSeconds()));
            while (process.isAlive()) {
                if (isCancelled != null && isCancelled.getAsBoolean()) {
                    process.destroyForcibly();
                    return ModelResponse.error(providerType, modelLabel, "Cancelled", elapsedMs(startNanos));
                }
                if (System.nanoTime() > deadlineNanos) {
                    process.destroyForcibly();
                    return ModelResponse.error(providerType, modelLabel,
                            "CLI '" + executable + "' timed out after " + request.timeoutSeconds() + "s",
                            elapsedMs(startNanos));
                }
                process.waitFor(120, TimeUnit.MILLISECONDS);
            }

            outThread.join(2000);
            errThread.join(2000);

            int exitCode = process.exitValue();
            String outputText = stdout.toString().trim();
            String errText = stderr.toString().trim();

            if (exitCode != 0) {
                String detail = !errText.isBlank() ? errText : outputText;
                if (detail.length() > 500) {
                    detail = detail.substring(0, 500) + "...";
                }
                return ModelResponse.error(providerType, modelLabel,
                        "CLI '" + executable + "' exited with code " + exitCode + (detail.isBlank() ? "" : ": " + detail),
                        elapsedMs(startNanos));
            }
            if (outputText.isBlank()) {
                return ModelResponse.error(providerType, modelLabel,
                        "CLI '" + executable + "' produced no output." + (errText.isBlank() ? "" : " Stderr: " + errText),
                        elapsedMs(startNanos));
            }

            return ModelResponse.ok(providerType, modelLabel, outputText, "", 0, 0, elapsedMs(startNanos));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return ModelResponse.error(providerType, modelLabel, "Interrupted", elapsedMs(startNanos));
        }
    }

    /**
     * Resolves an executable name (such as 'claude', 'codex', or 'gemini') to an absolute path
     * across PATH and common macOS/Linux/Windows install directories. Returns null if not found.
     */
    public static String resolveExecutable(String executable) {
        if (executable == null || executable.isBlank()) {
            return null;
        }
        String trimmed = executable.trim();
        File direct = new File(trimmed);
        if (direct.isAbsolute() || trimmed.contains("/") || trimmed.contains("\\")) {
            return (direct.isFile() && direct.canExecute()) ? direct.getAbsolutePath() : null;
        }

        for (String dir : candidateBinDirectories()) {
            Path candidate = Path.of(dir, trimmed);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
            // Windows .cmd/.exe support
            for (String ext : List.of(".cmd", ".exe", ".bat")) {
                Path winCand = Path.of(dir, trimmed + ext);
                if (Files.isRegularFile(winCand)) {
                    return winCand.toAbsolutePath().toString();
                }
            }
        }
        return null;
    }

    public static boolean isCliInstalled(ModelProviderType type, String customExecutable) {
        String exe = (customExecutable == null || customExecutable.isBlank())
                ? type.getDefaultCliExecutable() : customExecutable.trim();
        return resolveExecutable(exe) != null;
    }

    /**
     * Launches the vendor's official CLI browser login command (e.g., 'claude auth login',
     * 'codex login', 'gemini'). If the CLI prints an authorization URL, opens it automatically
     * in the user's default web browser.
     */
    public static String launchOfficialCliLogin(ModelProviderType type, String executable, Consumer<String> statusOut) {
        Consumer<String> status = statusOut == null ? s -> {} : statusOut;
        String binName = (executable == null || executable.isBlank()) ? type.getDefaultCliExecutable() : executable.trim();
        String resolved = resolveExecutable(binName);
        if (resolved == null) {
            return "CLI '" + binName + "' is not installed. Run: " + type.getCliInstallHint();
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(resolved);
        cmd.addAll(tokenizeArgs(type.getDefaultCliLoginArgs()));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        enrichPathForGuiApps(pb.environment());
        try {
            status.accept("Starting browser sign-in for " + type.getShortName() + "...");
            Process p = pb.start();
            AtomicBoolean openedBrowser = new AtomicBoolean(false);
            StringBuilder output = new StringBuilder();

            Thread watcher = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        output.append(line).append("\n");
                        Matcher m = URL_PATTERN.matcher(line);
                        if (m.find() && openedBrowser.compareAndSet(false, true)) {
                            String url = m.group();
                            status.accept("Opening browser to complete " + type.getShortName() + " sign-in...");
                            try {
                                BrowserUtil.browse(url);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                } catch (IOException ignored) {
                }
            }, "TriModel-CliLogin-" + type.name());
            watcher.setDaemon(true);
            watcher.start();

            if (p.waitFor(45, TimeUnit.SECONDS)) {
                watcher.join(1000);
                int code = p.exitValue();
                if (code == 0) {
                    return "Signed in to " + type.getFullName() + " successfully!";
                }
                String snippet = firstNonEmptyLine(output.toString());
                return "CLI exited (code " + code + ")" + (snippet.isBlank() ? "" : ": " + snippet);
            }
            return "Browser sign-in window is open for " + type.getShortName()
                    + ". Finish authorizing in your browser, then click Test.";
        } catch (IOException e) {
            return "Could not start '" + binName + "': " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Sign-in interrupted.";
        }
    }

    public static String launchOfficialCliLogout(ModelProviderType type, String executable) {
        String logoutArgs = type.getDefaultCliLogoutArgs();
        if (logoutArgs == null || logoutArgs.isBlank()) {
            return "To sign out of " + type.getShortName() + " CLI, run '" + type.getDefaultCliExecutable() + "' in Terminal.";
        }
        String binName = (executable == null || executable.isBlank()) ? type.getDefaultCliExecutable() : executable.trim();
        String resolved = resolveExecutable(binName);
        if (resolved == null) {
            return "CLI '" + binName + "' is not installed.";
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(resolved);
        cmd.addAll(tokenizeArgs(logoutArgs));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        enrichPathForGuiApps(pb.environment());
        try {
            Process p = pb.start();
            p.waitFor(10, TimeUnit.SECONDS);
            return p.exitValue() == 0
                    ? "Signed out of " + type.getShortName() + " CLI."
                    : "Sign-out command exited with code " + p.exitValue();
        } catch (Exception e) {
            return "Sign-out failed: " + e.getMessage();
        }
    }

    private static List<String> candidateBinDirectories() {
        Set<String> dirs = new LinkedHashSet<>();
        String envPath = System.getenv("PATH");
        if (envPath != null && !envPath.isBlank()) {
            for (String p : envPath.split(Pattern.quote(File.pathSeparator))) {
                if (!p.isBlank()) dirs.add(p.trim());
            }
        }
        String home = System.getProperty("user.home", "");
        dirs.add("/opt/homebrew/bin");
        dirs.add("/usr/local/bin");
        dirs.add("/usr/bin");
        if (!home.isBlank()) {
            dirs.add(home + "/.local/bin");
            dirs.add(home + "/.npm-global/bin");
            dirs.add(home + "/.volta/bin");
            dirs.add(home + "/.asdf/shims");
            // Check latest nvm node bin if installed
            File nvmVersions = new File(home + "/.nvm/versions/node");
            if (nvmVersions.isDirectory()) {
                File[] children = nvmVersions.listFiles();
                if (children != null) {
                    for (File child : children) {
                        File bin = new File(child, "bin");
                        if (bin.isDirectory()) {
                            dirs.add(bin.getAbsolutePath());
                        }
                    }
                }
            }
        }
        return new ArrayList<>(dirs);
    }

    private static String formatCombinedPrompt(ModelRequest request) {
        StringBuilder sb = new StringBuilder();
        if (!request.systemPrompt().isBlank()) {
            sb.append("[SYSTEM INSTRUCTIONS]\n").append(request.systemPrompt().trim()).append("\n\n");
        }
        if (!request.history().isEmpty()) {
            sb.append("[CONVERSATION HISTORY]\n");
            for (ChatMessage msg : request.history()) {
                sb.append(msg.role().toUpperCase()).append(": ").append(msg.content()).append("\n\n");
            }
        }
        sb.append("[TASK]\n").append(request.userPrompt());
        return sb.toString();
    }

    static List<String> tokenizeArgs(String args) {
        List<String> tokens = new ArrayList<>();
        if (args == null || args.isBlank()) {
            return tokens;
        }
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean hasToken = false;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '\\' && !inSingle && i + 1 < args.length()) {
                current.append(args.charAt(++i));
                hasToken = true;
            } else if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                hasToken = true;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                hasToken = true;
            } else if (Character.isWhitespace(c) && !inSingle && !inDouble) {
                if (hasToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    hasToken = false;
                }
            } else {
                current.append(c);
                hasToken = true;
            }
        }
        if (hasToken) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static Thread startReaderThread(InputStream stream, StringBuilder target) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                char[] buf = new char[4096];
                int n;
                while ((n = reader.read(buf)) != -1) {
                    target.append(buf, 0, n);
                }
            } catch (IOException ignored) {
            }
        }, "TriModel-CLI-Reader");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void enrichPathForGuiApps(Map<String, String> env) {
        String sep = File.pathSeparator;
        List<String> dirs = candidateBinDirectories();
        env.put("PATH", String.join(sep, dirs));
    }

    private static String firstNonEmptyLine(String s) {
        if (s == null) return "";
        for (String line : s.split("\\R")) {
            String t = line.trim();
            if (!t.isBlank()) {
                return t.length() > 140 ? t.substring(0, 140) + "..." : t;
            }
        }
        return "";
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
