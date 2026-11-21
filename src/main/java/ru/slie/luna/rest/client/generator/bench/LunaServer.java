package ru.slie.luna.rest.client.generator.bench;

import ru.slie.luna.rest.client.generator.data.GeneratorConfig;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Luna used by the up/bench commands: the one at server.url if it was started from this workdir,
 * otherwise a new one started with {@code luna-sdk run -w <workdir>/luna}.
 * The started luna-sdk keeps running after this process exits; {@link #stop} or the down command stops it.
 */
public class LunaServer {
    private final GeneratorConfig config;
    private final Workdir workdir;
    private final PrintWriter out;
    private Workdir.ServerInfo info;
    private boolean startedNow;

    public LunaServer(GeneratorConfig config, Workdir workdir, PrintWriter out) {
        this.config = config;
        this.workdir = workdir;
        this.out = out;
    }

    /**
     * Makes sure Luna is running.
     *
     * @param attach use a server at server.url not started from this workdir (no JMX unless configured)
     */
    public Workdir.ServerInfo ensureRunning(boolean attach) {
        Optional<Workdir.ServerInfo> saved = workdir.readServerInfo().filter(Workdir.ServerInfo::isAlive);
        boolean responds = responds(config.server.url);
        if (saved.isPresent() && saved.get().url.equals(config.server.url)) {
            if (!responds) {
                waitForServer(saved.get().pid, Duration.ofSeconds(config.sdk.startTimeout));
            }
            info = saved.get();
            out.printf("Луна уже запущена из этого каталога: %s (luna-sdk pid %d)%n", info.url, info.pid);
            return info;
        }
        if (responds) {
            if (!attach) {
                throw new IllegalStateException("По адресу " + config.server.url + " уже работает Луна, запущенная не из "
                        + workdir.root() + ". Остановите её, укажите другой server.url или добавьте --attach, "
                        + "чтобы работать с ней.");
            }
            info = new Workdir.ServerInfo();
            info.pid = -1;
            info.url = config.server.url;
            info.jmxPort = config.sdk.jmxPort;
            out.printf("Используется уже запущенная Луна: %s%n", info.url);
            return info;
        }
        return start();
    }

    public boolean isStartedNow() {
        return startedNow;
    }

    private Workdir.ServerInfo start() {
        URI uri = URI.create(config.server.url);
        String host = uri.getHost();
        if (!"localhost".equals(host) && !"127.0.0.1".equals(host)) {
            throw new IllegalStateException("Луна по адресу " + config.server.url + " не отвечает, а запустить её можно только локально");
        }
        int port = uri.getPort() > 0 ? uri.getPort() : 80;

        List<String> jvmArgs = new ArrayList<>();
        if (config.sdk.jvmArgs != null && !config.sdk.jvmArgs.isBlank()) {
            jvmArgs.add(config.sdk.jvmArgs.trim());
        }
        if (config.sdk.jmxPort > 0) {
            int jmx = config.sdk.jmxPort;
            jvmArgs.add("-Dcom.sun.management.jmxremote.port=" + jmx);
            jvmArgs.add("-Dcom.sun.management.jmxremote.rmi.port=" + jmx);
            jvmArgs.add("-Dcom.sun.management.jmxremote.host=127.0.0.1");
            jvmArgs.add("-Djava.rmi.server.hostname=127.0.0.1");
            jvmArgs.add("-Dcom.sun.management.jmxremote.authenticate=false");
            jvmArgs.add("-Dcom.sun.management.jmxremote.ssl=false");
        }

        List<String> command = new ArrayList<>(List.of(config.sdk.command, "run",
                "-w", workdir.sdkState().toString(), "-p", String.valueOf(port), "--no-plugin",
                "--timeout", String.valueOf(config.sdk.startTimeout)));
        if (!jvmArgs.isEmpty()) {
            command.add("-j");
            command.add(String.join(" ", jvmArgs));
        }
        if (config.sdk.lunaVersion != null && !config.sdk.lunaVersion.isBlank()) {
            command.add("--luna-version");
            command.add(config.sdk.lunaVersion);
        }
        if (config.sdk.dbUri != null && !config.sdk.dbUri.isBlank()) {
            command.add("-d");
            command.add(config.sdk.dbUri);
        }
        if (config.sdk.offline) {
            command.add("-o");
        }
        command.addAll(config.sdk.extraArgs);

        out.printf("Запуск Луны: %s%n", String.join(" ", command));
        out.printf("Вывод luna-sdk: %s%n", workdir.sdkLog());
        out.flush();
        Process process;
        try {
            Files.writeString(workdir.sdkLog(), "");
            process = new ProcessBuilder(command)
                    .directory(workdir.root().toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(workdir.sdkLog().toFile()))
                    .redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось запустить " + config.sdk.command + ": " + e.getMessage()
                    + ". Укажите путь к luna-sdk в sdk.command", e);
        }

        info = new Workdir.ServerInfo();
        info.pid = process.pid();
        info.url = config.server.url;
        info.jmxPort = config.sdk.jmxPort;
        info.startedAt = OffsetDateTime.now().toString();
        info.jvmArgs = String.join(" ", jvmArgs);
        workdir.writeServerInfo(info);

        long started = System.nanoTime();
        try {
            waitForServer(process.pid(), Duration.ofSeconds(config.sdk.startTimeout + 30L));
        } catch (RuntimeException e) {
            process.destroy();
            workdir.deleteServerInfo();
            throw e;
        }
        startedNow = true;
        out.printf("Луна запущена за %.0f с: %s%n", (System.nanoTime() - started) / 1e9, info.url);
        return info;
    }

    private void waitForServer(long pid, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (responds(config.server.url)) {
                return;
            }
            if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                throw new IllegalStateException("luna-sdk завершился, не запустив Луну. Последние строки " + workdir.sdkLog()
                        + ":\n" + tail(workdir.sdkLog(), 15));
            }
            sleep(2000);
        }
        throw new IllegalStateException("Луна не запустилась за " + timeout.toSeconds() + " с, см. " + workdir.sdkLog());
    }

    /** Stops luna-sdk started from the workdir (it stops Tomcat and the database container). */
    public static boolean stop(Workdir workdir, PrintWriter out) {
        Optional<Workdir.ServerInfo> saved = workdir.readServerInfo();
        if (saved.isEmpty() || !saved.get().isAlive()) {
            workdir.deleteServerInfo();
            return false;
        }
        ProcessHandle handle = ProcessHandle.of(saved.get().pid).orElseThrow();
        out.printf("Остановка Луны (luna-sdk pid %d)...%n", saved.get().pid);
        out.flush();
        // A launcher script without exec does not pass SIGTERM to java: stop the luna-sdk JVM itself,
        // its shutdown hook stops Tomcat and the database container.
        List<ProcessHandle> targets = new ArrayList<>(handle.descendants()
                .filter(p -> p.info().commandLine().map(c -> c.contains("ru.slie.luna.sdk.LunaCliMain")).orElse(false))
                .toList());
        targets.add(handle);
        for (ProcessHandle target : targets) {
            target.destroy();
            try {
                target.onExit().get(3, TimeUnit.MINUTES);
            } catch (Exception e) {
                throw new IllegalStateException("luna-sdk не остановился за 3 минуты, pid " + target.pid());
            }
        }
        workdir.deleteServerInfo();
        out.println("Луна остановлена");
        return true;
    }

    /** Server answers an authenticated request. */
    public boolean responds(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url + "/rest/licenses/self").toURL().openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(5000);
            String credentials = config.server.user + ":" + config.server.password;
            connection.setRequestProperty("Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
            int code = connection.getResponseCode();
            connection.disconnect();
            return code == 200;
        } catch (IOException e) {
            return false;
        }
    }

    private static String tail(Path file, int lines) {
        try {
            List<String> all = Files.readAllLines(file);
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }
}
