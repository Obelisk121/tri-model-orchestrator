package io.github.obelisk121.trimodel.provider;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes a local AI CLI tool (claude, codex, or Google Antigravity agy) without shell interpolation.
 * Automatically resolves macOS, Linux, and Windows executable paths, normalizes per-CLI arguments
 * and live-selected model flags, checks authentication status, and runs browser sign-in/sign-out.
 */
public final class CliModelClient implements ModelClient {
    private static final Pattern URL_PATTERN = Pattern.compile("https://[^\\s\"'<>\\])]+");
    private static final Pattern ANSI_ESCAPE_PATTERN = Pattern.compile("\\u001B\\[[;?\\d]*[ -/]*[@-~]|\\u001B\\][^\\u0007]*\\u0007");

    public enum AuthState {
        NOT_INSTALLED,
        SIGNED_IN,
        SIGNED_OUT,
        UNKNOWN
    }

    public record CliAuthStatus(boolean installed, String resolvedPath, AuthState state, String detail) {
    }

    public record CliCommandResult(
            int exitCode,
            String stdout,
            String stderr,
            boolean timedOut,
            boolean cancelled,
            String launchError
    ) {
    }

    private final ModelProviderType providerType;
    private final String executable;
    private final String argsTemplate;
    private final String selectedModelId;
    private final String modelLabel;

    public CliModelClient(ModelProviderType providerType, String executable, String argsTemplate, String modelLabel) {
        this(providerType, executable, argsTemplate, "", modelLabel);
    }

    public CliModelClient(
            ModelProviderType providerType,
            String executable,
            String argsTemplate,
            String selectedModelId,
            String modelLabel
    ) {
        this.providerType = providerType;
        this.executable = (executable == null || executable.isBlank())
                ? providerType.getDefaultCliExecutable() : executable.trim();
        this.argsTemplate = argsTemplate == null ? providerType.getDefaultCliArgs() : argsTemplate.trim();
        this.selectedModelId = selectedModelId == null ? "" : selectedModelId.trim();
        if (modelLabel != null && !modelLabel.isBlank()) {
            this.modelLabel = modelLabel.trim();
        } else {
            String base = "cli:" + canonicalCliName(this.executable);
            this.modelLabel = isConcreteModelId(this.selectedModelId) ? base + "/" + this.selectedModelId : base;
        }
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

        List<String> parsedArgs = normalizeArgsForExecution(
                providerType, executable, tokenizeArgs(argsTemplate), selectedModelId);
        boolean promptInjectedViaArg = false;
        for (String token : parsedArgs) {
            if (token.contains("{prompt}")) {
                command.add(token.replace("{prompt}", combinedPrompt));
                promptInjectedViaArg = true;
            } else {
                command.add(token);
            }
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        String userHome = System.getProperty("user.home", "");
        if (!userHome.isBlank() && new File(userHome).isDirectory()) {
            pb.directory(new File(userHome));
        }
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
            String outputText = stripAnsiAndControl(stdout.toString()).trim();
            String errText = stripAnsiAndControl(stderr.toString()).trim();

            if (exitCode != 0) {
                String detail = extractCliErrorDetail(providerType, executable, errText, outputText);
                if (detail.length() > 500) {
                    detail = detail.substring(0, 500) + "...";
                }
                return ModelResponse.error(providerType, modelLabel,
                        "CLI '" + executable + "' exited with code " + exitCode + (detail.isBlank() ? "" : ": " + detail),
                        elapsedMs(startNanos));
            }
            if (outputText.isBlank()) {
                String detail = extractCliErrorDetail(providerType, executable, errText, "");
                return ModelResponse.error(providerType, modelLabel,
                        "CLI '" + executable + "' produced no output." + (detail.isBlank() ? "" : " " + detail),
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
     * Runs a short-lived non-interactive utility command (e.g., listing models or checking auth status).
     */
    public static CliCommandResult runUtilityCommand(
            String resolvedExe,
            List<String> args,
            int timeoutSeconds,
            BooleanSupplier isCancelled
    ) {
        if (isCancelled != null && isCancelled.getAsBoolean()) {
            return new CliCommandResult(-1, "", "", false, true, "Cancelled");
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(resolvedExe);
        if (args != null) {
            cmd.addAll(args);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        String userHome = System.getProperty("user.home", "");
        if (!userHome.isBlank() && new File(userHome).isDirectory()) {
            pb.directory(new File(userHome));
        }
        enrichPathForGuiApps(pb.environment());

        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            return new CliCommandResult(-1, "", "", false, false, "Could not run '" + resolvedExe + "': " + e.getMessage());
        }
        try {
            p.getOutputStream().close();
        } catch (IOException ignored) {
        }

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread outThread = startReaderThread(p.getInputStream(), stdout);
        Thread errThread = startReaderThread(p.getErrorStream(), stderr);

        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(3, timeoutSeconds));
        try {
            while (p.isAlive()) {
                if (isCancelled != null && isCancelled.getAsBoolean()) {
                    p.destroyForcibly();
                    return new CliCommandResult(-1, stdout.toString(), stderr.toString(), false, true, "Cancelled");
                }
                if (System.nanoTime() > deadlineNanos) {
                    p.destroyForcibly();
                    return new CliCommandResult(-1, stdout.toString(), stderr.toString(), true, false,
                            "Command timed out after " + timeoutSeconds + "s");
                }
                p.waitFor(80, TimeUnit.MILLISECONDS);
            }
            outThread.join(1000);
            errThread.join(1000);
            return new CliCommandResult(p.exitValue(), stdout.toString(), stderr.toString(), false, false, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return new CliCommandResult(-1, stdout.toString(), stderr.toString(), false, true, "Interrupted");
        }
    }

    /**
     * Checks whether the configured CLI binary is installed and signed in.
     */
    public static CliAuthStatus checkCliAuthStatus(ModelProviderType type, String executable) {
        String binName = (executable == null || executable.isBlank())
                ? type.getDefaultCliExecutable() : executable.trim();
        String resolved = resolveExecutable(binName);
        if (resolved == null) {
            return new CliAuthStatus(false, "", AuthState.NOT_INSTALLED,
                    binName + " CLI not installed");
        }

        String canonical = canonicalCliName(binName);
        if ("claude".equals(canonical)) {
            CliCommandResult res = runUtilityCommand(resolved, List.of("auth", "status", "--json"), 6, () -> false);
            if (res.launchError() == null && !res.stdout().isBlank()) {
                try {
                    String cleaned = stripAnsiAndControl(res.stdout()).trim();
                    int start = cleaned.indexOf('{');
                    int end = cleaned.lastIndexOf('}');
                    if (start >= 0 && end > start) {
                        JsonObject obj = JsonParser.parseString(cleaned.substring(start, end + 1)).getAsJsonObject();
                        boolean loggedIn = obj.has("loggedIn") && !obj.get("loggedIn").isJsonNull()
                                && obj.get("loggedIn").getAsBoolean();
                        if (loggedIn) {
                            String method = HttpTransport.getString(obj, "authMethod");
                            String email = HttpTransport.getString(obj, "email");
                            String detail = !email.isBlank()
                                    ? "claude CLI signed in (" + email + ")"
                                    : (!method.isBlank() ? "claude CLI signed in (" + method + ")" : "claude CLI signed in");
                            return new CliAuthStatus(true, resolved, AuthState.SIGNED_IN, detail);
                        }
                        return new CliAuthStatus(true, resolved, AuthState.SIGNED_OUT,
                                "claude CLI installed — sign in required");
                    }
                } catch (Exception ignored) {
                }
            }
            return new CliAuthStatus(true, resolved, AuthState.UNKNOWN, "claude CLI ready");
        }

        if ("codex".equals(canonical)) {
            CliCommandResult res = runUtilityCommand(resolved, List.of("login", "status"), 6, () -> false);
            if (res.launchError() == null) {
                String combined = stripAnsiAndControl((res.stdout() + "\n" + res.stderr())).trim();
                if (res.exitCode() == 0 && combined.toLowerCase(Locale.ROOT).contains("logged in")) {
                    String line = firstNonEmptyLine(combined);
                    return new CliAuthStatus(true, resolved, AuthState.SIGNED_IN,
                            "codex CLI — " + line);
                }
                if (res.exitCode() != 0 || combined.toLowerCase(Locale.ROOT).contains("not logged in")) {
                    return new CliAuthStatus(true, resolved, AuthState.SIGNED_OUT,
                            "codex CLI installed — sign in required");
                }
            }
            return new CliAuthStatus(true, resolved, AuthState.UNKNOWN, "codex CLI ready");
        }

        if ("agy".equals(canonical)) {
            CliCommandResult res = runUtilityCommand(resolved, List.of("models"), 10, () -> false);
            if (res.launchError() == null && res.exitCode() == 0) {
                List<ModelDiscoveryService.DiscoveredModel> models =
                        ModelDiscoveryService.parseAgyCliModelsText(res.stdout(), type);
                if (!models.isEmpty()) {
                    return new CliAuthStatus(true, resolved, AuthState.SIGNED_IN,
                            "Antigravity (agy) signed in (" + models.size() + " " + type.getShortName() + " models)");
                }
            }
            if (res.launchError() == null && res.exitCode() != 0) {
                String errLower = stripAnsiAndControl(res.stderr() + " " + res.stdout()).toLowerCase(Locale.ROOT);
                if (errLower.contains("auth") || errLower.contains("login") || errLower.contains("sign in")
                        || errLower.contains("unauthorized") || errLower.contains("unauthenticated")) {
                    return new CliAuthStatus(true, resolved, AuthState.SIGNED_OUT,
                            "Antigravity (agy) installed — sign in required");
                }
            }
            return new CliAuthStatus(true, resolved, AuthState.UNKNOWN, "Antigravity (agy) CLI ready");
        }

        return new CliAuthStatus(true, resolved, AuthState.UNKNOWN, binName + " CLI ready");
    }

    public static boolean supportsCliLogout(ModelProviderType type, String executable) {
        String binName = (executable == null || executable.isBlank())
                ? type.getDefaultCliExecutable() : executable.trim();
        String canonical = canonicalCliName(binName);
        if ("agy".equals(canonical)) {
            return false;
        }
        return !type.getDefaultCliLogoutArgs().isBlank();
    }

    /**
     * Resolves an executable name (claude, codex, or agy) to an absolute path
     * across PATH and OS-specific macOS, Linux, and Windows install directories. Returns null if not found.
     */
    public static String resolveExecutable(String executable) {
        return resolveExecutable(
                executable,
                System.getProperty("os.name", ""),
                candidateBinDirectories(),
                System.getenv()
        );
    }

    public static String resolveExecutable(
            String executable,
            String osName,
            List<String> searchDirs,
            Map<String, String> env
    ) {
        if (executable == null || executable.isBlank()) {
            return null;
        }
        String trimmed = executable.trim();
        boolean isWindows = isWindowsOs(osName);

        File direct = new File(trimmed);
        if (direct.isAbsolute() || trimmed.contains("/") || trimmed.contains("\\")) {
            if (isWindows) {
                String winDirect = resolveWindowsInFileOrParent(direct);
                if (winDirect != null) return winDirect;
            }
            return (direct.isFile() && direct.canExecute()) ? direct.getAbsolutePath() : null;
        }

        List<String> dirs = searchDirs == null ? List.of() : searchDirs;
        if (isWindows) {
            List<String> exts = windowsExecutableExtensions(env);
            boolean hasExt = trimmed.contains(".");
            for (String dir : dirs) {
                if (dir == null || dir.isBlank()) continue;
                // On Windows, prefer .exe/.cmd/.bat over extensionless POSIX shell shims created by npm
                if (!hasExt) {
                    for (String ext : exts) {
                        Path winCand = Path.of(dir, trimmed + ext);
                        if (Files.isRegularFile(winCand)) {
                            return winCand.toAbsolutePath().toString();
                        }
                    }
                }
                Path directCand = Path.of(dir, trimmed);
                if (Files.isRegularFile(directCand) && hasExt) {
                    return directCand.toAbsolutePath().toString();
                }
            }
            return null;
        }

        for (String dir : dirs) {
            if (dir == null || dir.isBlank()) continue;
            Path candidate = Path.of(dir, trimmed);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }
        return null;
    }

    private static String resolveWindowsInFileOrParent(File direct) {
        String name = direct.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".exe") || name.endsWith(".cmd") || name.endsWith(".bat")) {
            return direct.isFile() ? direct.getAbsolutePath() : null;
        }
        File parent = direct.getParentFile();
        if (parent != null && parent.isDirectory()) {
            for (String ext : List.of(".exe", ".cmd", ".bat")) {
                File withExt = new File(parent, direct.getName() + ext);
                if (withExt.isFile()) {
                    return withExt.getAbsolutePath();
                }
            }
        }
        return direct.isFile() ? direct.getAbsolutePath() : null;
    }

    static List<String> windowsExecutableExtensions(Map<String, String> env) {
        LinkedHashSet<String> exts = new LinkedHashSet<>();
        exts.add(".exe");
        exts.add(".cmd");
        exts.add(".bat");
        String pathExt = getEnvCaseInsensitive(env, "PATHEXT");
        if (pathExt != null && !pathExt.isBlank()) {
            for (String raw : pathExt.split(";")) {
                String e = raw.trim().toLowerCase(Locale.ROOT);
                if (e.equals(".com")) {
                    exts.add(e);
                }
            }
        }
        return new ArrayList<>(exts);
    }

    public static boolean isWindowsOs(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
    }

    public static boolean isMacOs(String osName) {
        if (osName == null) return false;
        String lower = osName.toLowerCase(Locale.ROOT);
        return lower.contains("mac") || lower.contains("darwin") || lower.contains("osx");
    }

    public static boolean isLinuxOs(String osName) {
        if (osName == null) return false;
        String lower = osName.toLowerCase(Locale.ROOT);
        return lower.contains("linux") || lower.contains("nix") || lower.contains("nux");
    }

    public static boolean isCliInstalled(ModelProviderType type, String customExecutable) {
        String exe = (customExecutable == null || customExecutable.isBlank())
                ? type.getDefaultCliExecutable() : customExecutable.trim();
        return resolveExecutable(exe) != null;
    }

    /**
     * Launches the vendor's official CLI browser login command (claude auth login, codex login, or agy).
     * Lets CLIs open the default browser natively (avoiding duplicate tabs) and falls back to
     * BrowserUtil.browse() only if a CLI explicitly fails to launch the browser.
     */
    public static String launchOfficialCliLogin(ModelProviderType type, String executable, Consumer<String> statusOut) {
        Consumer<String> status = statusOut == null ? s -> {} : statusOut;
        String binName = (executable == null || executable.isBlank()) ? type.getDefaultCliExecutable() : executable.trim();
        String resolved = resolveExecutable(binName);
        if (resolved == null) {
            return "CLI '" + binName + "' is not installed. Run: " + type.getCliInstallHint();
        }

        String canonical = canonicalCliName(binName);
        List<String> cmd = new ArrayList<>();
        cmd.add(resolved);

        if ("agy".equals(canonical)) {
            cmd.addAll(List.of("--output-format", "text", "--disable-slash-commands", "-p", "Reply OK"));
        } else if ("claude".equals(canonical)) {
            cmd.addAll(List.of("auth", "login"));
        } else if ("codex".equals(canonical)) {
            cmd.add("login");
        } else {
            cmd.addAll(tokenizeArgs(type.getDefaultCliLoginArgs()));
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        String userHome = System.getProperty("user.home", "");
        if (!userHome.isBlank() && new File(userHome).isDirectory()) {
            pb.directory(new File(userHome));
        }
        enrichPathForGuiApps(pb.environment());

        try {
            status.accept("Starting browser sign-in for " + type.getShortName() + " (" + canonical + ")...");
            Process p = pb.start();
            AtomicBoolean openedBrowser = new AtomicBoolean(false);
            AtomicBoolean browserNotified = new AtomicBoolean(false);
            StringBuilder output = new StringBuilder();

            Thread watcher = new Thread(() -> {
                try (InputStreamReader isr = new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)) {
                    char[] buf = new char[1024];
                    int n;
                    while ((n = isr.read(buf)) != -1) {
                        synchronized (output) {
                            output.append(buf, 0, n);
                        }
                        String snapshot;
                        synchronized (output) {
                            snapshot = stripAnsiAndControl(output.toString());
                        }

                        String authUrl = extractAuthUrl(snapshot);
                        if (!browserNotified.get() && (authUrl != null
                                || snapshot.toLowerCase(Locale.ROOT).contains("opening browser")
                                || snapshot.toLowerCase(Locale.ROOT).contains("starting local login server"))) {
                            if (browserNotified.compareAndSet(false, true)) {
                                status.accept("Browser opened for " + type.getShortName() + " — complete sign-in in your browser...");
                            }
                        }

                        if (authUrl != null && !openedBrowser.get()
                                && shouldAutoOpenPrintedUrl(canonical, snapshot, authUrl)
                                && openedBrowser.compareAndSet(false, true)) {
                            status.accept("Opening browser to complete " + type.getShortName() + " sign-in...");
                            try {
                                BrowserUtil.browse(authUrl);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                } catch (IOException ignored) {
                }
            }, "TriModel-CliLogin-" + type.name());
            watcher.setDaemon(true);
            watcher.start();

            if (p.waitFor(180, TimeUnit.SECONDS)) {
                watcher.join(1500);
                int code = p.exitValue();
                if (code == 0) {
                    ModelDiscoveryService.invalidateCache(type);
                    return "Signed in to " + type.getFullName() + " successfully!";
                }
                String fullOut;
                synchronized (output) {
                    fullOut = stripAnsiAndControl(output.toString());
                }
                String detail = extractCliErrorDetail(type, binName, fullOut, "");
                return "CLI exited (code " + code + ")" + (detail.isBlank() ? "" : ": " + detail);
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
        String binName = (executable == null || executable.isBlank()) ? type.getDefaultCliExecutable() : executable.trim();
        String canonical = canonicalCliName(binName);

        if ("agy".equals(canonical)) {
            return "To sign out of Antigravity (agy) CLI, run 'agy' in Terminal and enter /logout.";
        }

        String logoutArgs = type.getDefaultCliLogoutArgs();
        if (logoutArgs == null || logoutArgs.isBlank()) {
            return "To sign out of " + type.getShortName() + " CLI, run '" + binName + "' in Terminal.";
        }
        String resolved = resolveExecutable(binName);
        if (resolved == null) {
            return "CLI '" + binName + "' is not installed.";
        }
        CliCommandResult res = runUtilityCommand(resolved, tokenizeArgs(logoutArgs), 15, () -> false);
        if (res.launchError() != null) {
            return "Sign-out failed: " + res.launchError();
        }
        if (res.exitCode() == 0) {
            ModelDiscoveryService.invalidateCache(type);
            return "Signed out of " + type.getShortName() + " CLI.";
        }
        String detail = extractCliErrorDetail(type, binName, res.stderr(), res.stdout());
        return "Sign-out exited with code " + res.exitCode() + (detail.isBlank() ? "" : ": " + detail);
    }

    public static String canonicalCliName(String executable) {
        if (executable == null || executable.isBlank()) {
            return "";
        }
        String name = new File(executable.trim()).getName().toLowerCase(Locale.ROOT);
        for (String ext : List.of(".exe", ".cmd", ".bat")) {
            if (name.endsWith(ext)) {
                name = name.substring(0, name.length() - ext.length());
                break;
            }
        }
        return name;
    }

    public static boolean isConcreteModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        String trimmed = modelId.trim();
        return !trimmed.equalsIgnoreCase(ModelDiscoveryService.AUTO_MODEL_ID)
                && !trimmed.equalsIgnoreCase("default");
    }

    /**
     * Backward-compatible provider-level normalization (delegates to executable-aware normalization).
     */
    public static List<String> normalizeArgsForProvider(ModelProviderType provider, List<String> args) {
        return normalizeArgsForExecution(provider, provider.getDefaultCliExecutable(), args, "");
    }

    /**
     * Normalizes CLI arguments for claude, codex, and Google Antigravity (agy)
     * and injects the dynamically selected model flag (-m / --model) when a concrete model is chosen.
     */
    public static List<String> normalizeArgsForExecution(
            ModelProviderType provider,
            String executable,
            List<String> args,
            String selectedModelId
    ) {
        String canonical = canonicalCliName(executable == null || executable.isBlank()
                ? provider.getDefaultCliExecutable() : executable);
        List<String> out = new ArrayList<>(args == null ? List.of() : args);
        boolean concreteModel = isConcreteModelId(selectedModelId);

        if ("codex".equals(canonical)) {
            if (out.isEmpty()) {
                out.addAll(tokenizeArgs(ModelProviderType.CHATGPT.getDefaultCliArgs()));
            }
            if (!out.isEmpty() && "exec".equals(out.get(0))) {
                if (!out.contains("--skip-git-repo-check")) {
                    out.add(1, "--skip-git-repo-check");
                }
                if (!out.contains("--color")) {
                    out.add(1, "never");
                    out.add(1, "--color");
                }
                if (!out.contains("--ephemeral")) {
                    out.add(1, "--ephemeral");
                }
                if (concreteModel && !containsFlag(out, "-m", "--model")) {
                    out.add(1, selectedModelId.trim());
                    out.add(1, "-m");
                }
            }
            return out;
        }

        if ("claude".equals(canonical)) {
            if (out.isEmpty()) {
                out.addAll(tokenizeArgs(ModelProviderType.CLAUDE.getDefaultCliArgs()));
            }
            if (concreteModel && !containsFlag(out, "--model", "-m")) {
                out.add("--model");
                out.add(selectedModelId.trim());
            }
            return out;
        }

        if ("agy".equals(canonical)) {
            // Antigravity CLI uses --output-format text and -p "{prompt}" (rejects -o and empty -p "")
            boolean hasPromptPlaceholder = out.stream().anyMatch(t -> t.contains("{prompt}"));
            if (out.isEmpty() || !hasPromptPlaceholder) {
                List<String> agyArgs = new ArrayList<>();
                agyArgs.add("--output-format");
                agyArgs.add("text");
                agyArgs.add("--disable-slash-commands");
                if (concreteModel) {
                    agyArgs.add("--model");
                    agyArgs.add(selectedModelId.trim());
                }
                agyArgs.add("-p");
                agyArgs.add("{prompt}");
                return agyArgs;
            }
            for (int i = 0; i < out.size(); i++) {
                if ("-o".equals(out.get(i))) {
                    out.set(i, "--output-format");
                }
            }
            if (concreteModel && !containsFlag(out, "--model", "-m")) {
                out.add(0, selectedModelId.trim());
                out.add(0, "--model");
            }
            return out;
        }

        return out;
    }

    /**
     * Extracts the most actionable error detail from CLI stderr/stdout, stripping ANSI codes,
     * spinner progress lines, and verbose startup banners (e.g., Codex CLI's metadata header).
     */
    public static String extractCliErrorDetail(
            ModelProviderType provider,
            String executable,
            String stderr,
            String stdout
    ) {
        String raw = (stderr != null && !stderr.isBlank()) ? stderr : (stdout == null ? "" : stdout);
        String cleaned = stripAnsiAndControl(raw).trim();
        if (cleaned.isBlank()) {
            return "";
        }

        String canonical = canonicalCliName(executable == null || executable.isBlank()
                ? provider.getDefaultCliExecutable() : executable);

        List<String> lines = new ArrayList<>();
        for (String line : cleaned.split("\\R")) {
            int lastCr = line.lastIndexOf('\r');
            String t = (lastCr >= 0 ? line.substring(lastCr + 1) : line).trim();
            if (!t.isBlank() && !t.contains("Fetching available models")) {
                lines.add(t);
            }
        }
        if (lines.isEmpty()) {
            return "";
        }

        if ("codex".equals(canonical)) {
            List<String> nonBanner = new ArrayList<>();
            for (String line : lines) {
                if (isCodexBannerLine(line)) continue;
                nonBanner.add(line);
            }
            if (!nonBanner.isEmpty()) {
                lines = nonBanner;
            }
        }

        List<String> prioritized = new ArrayList<>();
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("error") || lower.contains("failed") || lower.contains("unauthorized")
                    || lower.contains("forbidden") || lower.contains("expired") || lower.contains("not defined")
                    || lower.contains("not found") || lower.contains("invalid") || lower.contains("denied")
                    || lower.contains("authenticate") || lower.contains("login") || lower.contains("sign in")) {
                prioritized.add(line);
            }
        }
        List<String> chosen = prioritized.isEmpty() ? lines : prioritized;
        return String.join(" | ", chosen.subList(0, Math.min(3, chosen.size())));
    }

    private static boolean isCodexBannerLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.startsWith("openai codex ")
                || line.matches("^-+$")
                || lower.startsWith("workdir:")
                || lower.startsWith("model:")
                || lower.startsWith("provider:")
                || lower.startsWith("approval:")
                || lower.startsWith("sandbox:")
                || lower.startsWith("reasoning effort:")
                || lower.startsWith("reasoning summaries:")
                || lower.startsWith("session id:")
                || lower.startsWith("mcp startup:")
                || lower.equals("user")
                || lower.equals("thinking...");
    }

    public static String stripAnsiAndControl(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return ANSI_ESCAPE_PATTERN.matcher(s).replaceAll("");
    }

    public static boolean shouldAutoOpenPrintedUrl(String canonicalCli, String cliOutput, String authUrl) {
        if (authUrl == null || authUrl.isBlank()) {
            return false;
        }
        String lowerUrl = authUrl.toLowerCase(Locale.ROOT);
        // Claude's printed fallback URL uses platform.claude.com/oauth/code/callback (often URL-encoded as %2F),
        // which is a manual paste-code flow, while claude auth login already opened the real localhost loopback callback.
        if (lowerUrl.contains("/oauth/code/callback") || lowerUrl.contains("%2foauth%2fcode%2fcallback")) {
            return false;
        }
        String lowerOut = cliOutput == null ? "" : cliOutput.toLowerCase(Locale.ROOT);
        boolean cliReportedOpenFailure = lowerOut.contains("failed to open browser")
                || lowerOut.contains("could not open browser")
                || lowerOut.contains("unable to open browser")
                || lowerOut.contains("cannot open browser")
                || lowerOut.contains("error opening browser");
        if (cliReportedOpenFailure) {
            return true;
        }
        // claude and codex open the OS default browser natively; opening a second time creates duplicate tabs.
        if ("claude".equals(canonicalCli) || "codex".equals(canonicalCli)) {
            return false;
        }
        // For agy or custom CLIs, only open if they did not already report opening the browser themselves.
        return !lowerOut.contains("opening browser") && !lowerOut.contains("attempting to open");
    }

    static String extractAuthUrl(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String fallbackUrl = null;
        Matcher m = URL_PATTERN.matcher(text);
        while (m.find()) {
            String url = trimUrlPunctuation(m.group());
            String lower = url.toLowerCase(Locale.ROOT);
            if (lower.contains("youtube.com") || lower.contains("youtu.be")
                    || lower.contains("/docs/") || lower.contains("release-notes")
                    || lower.contains("changelog") || lower.contains("auth_success")
                    || lower.contains("auth_failure")) {
                continue;
            }
            if (lower.contains("oauth") || lower.contains("authorize") || lower.contains("accounts.google.com")
                    || lower.contains("auth.openai.com") || lower.contains("claude.ai")
                    || lower.contains("console.anthropic.com") || lower.contains("antigravity.google")
                    || lower.contains("client_id=") || lower.contains("redirect_uri=")
                    || lower.contains("/login") || lower.contains("/signin") || lower.contains("/auth")) {
                return url;
            }
            if (fallbackUrl == null) {
                fallbackUrl = url;
            }
        }
        return fallbackUrl;
    }

    private static String trimUrlPunctuation(String url) {
        String res = url;
        while (!res.isEmpty()) {
            char last = res.charAt(res.length() - 1);
            if (last == '.' || last == ',' || last == ';' || last == ')' || last == ']') {
                res = res.substring(0, res.length() - 1);
            } else {
                break;
            }
        }
        return res;
    }

    private static boolean containsFlag(List<String> args, String... flags) {
        for (String token : args) {
            for (String f : flags) {
                if (token.equals(f) || token.startsWith(f + "=")) {
                    return true;
                }
            }
        }
        return false;
    }

    public static List<String> candidateBinDirectories() {
        return candidateBinDirectories(
                System.getProperty("os.name", ""),
                System.getProperty("user.home", ""),
                System.getenv()
        );
    }

    /**
     * Builds an ordered list of candidate executable directories for macOS, Linux, and Windows,
     * covering PATH, Homebrew/Linuxbrew/MacPorts, Antigravity, npm/pnpm/yarn/bun, nvm/fnm/volta/asdf/mise,
     * WinGet/Scoop/Chocolatey, Snap/Flatpak, Cargo, and user-local CLI directories.
     */
    public static List<String> candidateBinDirectories(String osName, String homeDir, Map<String, String> env) {
        Set<String> dirs = new LinkedHashSet<>();
        boolean isWindows = isWindowsOs(osName);
        boolean isMac = isMacOs(osName);
        String pathSep = isWindows ? ";" : ":";
        String home = homeDir == null ? "" : homeDir.trim();

        String envPath = getEnvCaseInsensitive(env, "PATH");
        if (envPath != null && !envPath.isBlank()) {
            for (String p : envPath.split(Pattern.quote(pathSep))) {
                if (!p.isBlank()) dirs.add(p.trim());
            }
        }

        // Custom npm global prefix from env or ~/.npmrc
        String npmPrefix = getEnvCaseInsensitive(env, "npm_config_prefix");
        if ((npmPrefix == null || npmPrefix.isBlank()) && !home.isBlank()) {
            npmPrefix = readNpmrcPrefix(Path.of(home, ".npmrc"));
        }
        if (npmPrefix != null && !npmPrefix.isBlank()) {
            String p = expandHomePrefix(npmPrefix.trim(), home);
            dirs.add(isWindows ? p : p + "/bin");
            if (isWindows) {
                dirs.add(p + "\\bin");
            }
        }

        if (isWindows) {
            String systemRoot = getEnvCaseInsensitive(env, "SystemRoot");
            if (systemRoot == null || systemRoot.isBlank()) {
                systemRoot = getEnvCaseInsensitive(env, "WINDIR");
            }
            addChildIfNotBlank(dirs, systemRoot, "System32");
            addChildIfNotBlank(dirs, systemRoot, "System32\\WindowsPowerShell\\v1.0");
            String appData = getEnvCaseInsensitive(env, "APPDATA");
            String localAppData = getEnvCaseInsensitive(env, "LOCALAPPDATA");
            String programFiles = getEnvCaseInsensitive(env, "ProgramFiles");
            String programFilesX86 = getEnvCaseInsensitive(env, "ProgramFiles(x86)");
            String programData = getEnvCaseInsensitive(env, "ProgramData");
            String nvmSymlink = getEnvCaseInsensitive(env, "NVM_SYMLINK");
            String nvmHome = getEnvCaseInsensitive(env, "NVM_HOME");
            String pnpmHome = getEnvCaseInsensitive(env, "PNPM_HOME");
            String voltaHome = getEnvCaseInsensitive(env, "VOLTA_HOME");
            String bunInstall = getEnvCaseInsensitive(env, "BUN_INSTALL");
            String scoop = getEnvCaseInsensitive(env, "SCOOP");

            addIfNotBlank(dirs, pnpmHome);
            addChildIfNotBlank(dirs, appData, "npm");
            addChildIfNotBlank(dirs, appData, "pnpm");
            addChildIfNotBlank(dirs, localAppData, "pnpm");
            addChildIfNotBlank(dirs, localAppData, "Yarn\\bin");
            addChildIfNotBlank(dirs, localAppData, "Microsoft\\WinGet\\Links");
            addChildIfNotBlank(dirs, localAppData, "Programs\\claude-code");
            addChildIfNotBlank(dirs, localAppData, "Programs\\Claude");
            addChildIfNotBlank(dirs, localAppData, "Programs\\Antigravity\\bin");
            addChildIfNotBlank(dirs, localAppData, "Antigravity\\bin");
            addChildIfNotBlank(dirs, localAppData, "Volta\\bin");
            addChildIfNotBlank(dirs, localAppData, "mise\\shims");
            addChildIfNotBlank(dirs, voltaHome, "bin");
            addChildIfNotBlank(dirs, bunInstall, "bin");
            addIfNotBlank(dirs, nvmSymlink);
            addChildIfNotBlank(dirs, programFiles, "nodejs");
            addChildIfNotBlank(dirs, programFilesX86, "nodejs");
            addChildIfNotBlank(dirs, programData, "chocolatey\\bin");
            if (scoop != null && !scoop.isBlank()) {
                dirs.add(scoop.trim() + "\\shims");
            }
            if (!home.isBlank()) {
                dirs.add(home + "\\.local\\bin");
                dirs.add(home + "\\.claude\\local");
                dirs.add(home + "\\.claude\\bin");
                dirs.add(home + "\\.antigravity\\bin");
                dirs.add(home + "\\scoop\\shims");
                dirs.add(home + "\\.bun\\bin");
                dirs.add(home + "\\.cargo\\bin");
                dirs.add(home + "\\.volta\\bin");
                dirs.add(home + "\\.mise\\shims");
                addVersionedSubdirs(dirs, new File(home + "\\AppData\\Roaming\\nvm"), "");
                addVersionedSubdirs(dirs, new File(home + "\\.fnm\\node-versions"), "installation");
            }
            if (nvmHome != null && !nvmHome.isBlank()) {
                addVersionedSubdirs(dirs, new File(nvmHome.trim()), "");
            }
            return new ArrayList<>(dirs);
        }

        // macOS & Linux common system and user directories
        if (isMac) {
            dirs.add("/opt/homebrew/bin");
            dirs.add("/opt/homebrew/sbin");
            dirs.add("/opt/local/bin");
            dirs.add("/Applications/Antigravity.app/Contents/Resources/app/bin");
        } else {
            dirs.add("/home/linuxbrew/.linuxbrew/bin");
            dirs.add("/snap/bin");
            dirs.add("/var/lib/flatpak/exports/bin");
            dirs.add("/opt/antigravity/bin");
            dirs.add("/nix/var/nix/profiles/default/bin");
        }
        dirs.add("/usr/local/bin");
        dirs.add("/usr/bin");
        dirs.add("/bin");

        String xdgBinHome = getEnvCaseInsensitive(env, "XDG_BIN_HOME");
        String pnpmHome = getEnvCaseInsensitive(env, "PNPM_HOME");
        String voltaHome = getEnvCaseInsensitive(env, "VOLTA_HOME");
        String bunInstall = getEnvCaseInsensitive(env, "BUN_INSTALL");
        String nvmDir = getEnvCaseInsensitive(env, "NVM_DIR");

        addIfNotBlank(dirs, xdgBinHome);
        addIfNotBlank(dirs, pnpmHome);
        addChildIfNotBlank(dirs, voltaHome, "bin");
        addChildIfNotBlank(dirs, bunInstall, "bin");

        if (!home.isBlank()) {
            dirs.add(home + "/.local/bin");
            dirs.add(home + "/.claude/local");
            dirs.add(home + "/.claude/bin");
            dirs.add(home + "/.antigravity/bin");
            dirs.add(home + "/bin");
            dirs.add(home + "/.npm-global/bin");
            dirs.add(home + "/.npm/bin");
            dirs.add(home + "/.yarn/bin");
            dirs.add(home + "/.config/yarn/global/node_modules/.bin");
            dirs.add(home + "/.bun/bin");
            dirs.add(home + "/.cargo/bin");
            dirs.add(home + "/.volta/bin");
            dirs.add(home + "/.asdf/shims");
            dirs.add(home + "/.mise/shims");
            dirs.add(home + "/.local/share/mise/shims");
            dirs.add(home + "/.nodenv/shims");
            dirs.add(home + "/.nix-profile/bin");
            if (isMac) {
                dirs.add(home + "/Library/pnpm");
                dirs.add(home + "/Applications/Antigravity.app/Contents/Resources/app/bin");
                addVersionedSubdirs(dirs, new File(home + "/Library/Application Support/fnm/node-versions"), "installation/bin");
            } else {
                dirs.add(home + "/.linuxbrew/bin");
                dirs.add(home + "/.local/share/pnpm");
                dirs.add(home + "/.local/share/flatpak/exports/bin");
                addVersionedSubdirs(dirs, new File(home + "/.local/share/fnm/node-versions"), "installation/bin");
            }
            addVersionedSubdirs(dirs, new File(home + "/.fnm/node-versions"), "installation/bin");
            addVersionedSubdirs(dirs, new File(home + "/.nvm/versions/node"), "bin");
        }
        if (nvmDir != null && !nvmDir.isBlank()) {
            addVersionedSubdirs(dirs, new File(nvmDir.trim(), "versions/node"), "bin");
        }
        return new ArrayList<>(dirs);
    }

    private static void addIfNotBlank(Set<String> dirs, String value) {
        if (value != null && !value.isBlank()) {
            dirs.add(value.trim());
        }
    }

    private static void addChildIfNotBlank(Set<String> dirs, String base, String child) {
        if (base != null && !base.isBlank()) {
            String b = base.trim();
            String sep = (b.contains("\\") || child.contains("\\")) ? "\\" : "/";
            while (b.endsWith("/") || b.endsWith("\\")) {
                b = b.substring(0, b.length() - 1);
            }
            dirs.add(b + sep + child);
        }
    }

    private static void addVersionedSubdirs(Set<String> dirs, File parent, String relativeBin) {
        if (parent == null || !parent.isDirectory()) return;
        File[] children = parent.listFiles();
        if (children == null || children.length == 0) return;
        Arrays.sort(children, Comparator.comparing(File::getName, CliModelClient::compareSemverDescending));
        for (File child : children) {
            if (!child.isDirectory()) continue;
            File target = (relativeBin == null || relativeBin.isBlank()) ? child : new File(child, relativeBin);
            if (target.isDirectory()) {
                dirs.add(target.getAbsolutePath());
            }
        }
    }

    private static int compareSemverDescending(String a, String b) {
        String[] pa = a.replaceFirst("^[vV]", "").split("\\.");
        String[] pb = b.replaceFirst("^[vV]", "").split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int na = i < pa.length ? parseLeadingInt(pa[i]) : 0;
            int nb = i < pb.length ? parseLeadingInt(pb[i]) : 0;
            if (na != nb) return Integer.compare(nb, na);
        }
        return b.compareToIgnoreCase(a);
    }

    private static int parseLeadingInt(String s) {
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        if (i == 0) return 0;
        try {
            return Integer.parseInt(s.substring(0, i));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String readNpmrcPrefix(Path npmrcPath) {
        if (npmrcPath == null || !Files.isRegularFile(npmrcPath)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(npmrcPath, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue;
                int eq = trimmed.indexOf('=');
                if (eq > 0 && "prefix".equalsIgnoreCase(trimmed.substring(0, eq).trim())) {
                    String val = trimmed.substring(eq + 1).trim();
                    if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                        val = val.substring(1, val.length() - 1);
                    }
                    return val;
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static String expandHomePrefix(String path, String home) {
        if (home == null || home.isBlank()) return path;
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return home + path.substring(1);
        }
        return path;
    }

    static String getEnvCaseInsensitive(Map<String, String> env, String key) {
        if (env == null || key == null) return null;
        String direct = env.get(key);
        if (direct != null) return direct;
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (key.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
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
        String pathKey = "PATH";
        for (String existingKey : env.keySet()) {
            if ("PATH".equalsIgnoreCase(existingKey)) {
                pathKey = existingKey;
                break;
            }
        }
        env.put(pathKey, String.join(sep, dirs));
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
