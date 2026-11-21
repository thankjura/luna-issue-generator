package ru.slie.luna.rest.client.generator.bench;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Layout of a test workdir:
 * <pre>
 * config.yaml   effective configuration of the last "up"
 * luna/         luna-sdk state: luna-home, database, tomcat
 * sdk.log       luna-sdk output
 * server.json   Luna started from this workdir (pid of luna-sdk, url, JMX port)
 * data/         generator state and summary.json
 * runs/         one directory per bench run
 * </pre>
 */
public class Workdir {
    private static final DateTimeFormatter RUN_NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private final Path root;

    public Workdir(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public Path config() {
        return root.resolve("config.yaml");
    }

    public Path sdkState() {
        return root.resolve("luna");
    }

    public Path sdkLog() {
        return root.resolve("sdk.log");
    }

    public Path serverInfo() {
        return root.resolve("server.json");
    }

    public Path data() {
        return root.resolve("data");
    }

    public Path summary() {
        return data().resolve("summary.json");
    }

    public Path runs() {
        return root.resolve("runs");
    }

    /** Lucene index of the Luna started from this workdir. */
    public Path index() {
        return sdkState().resolve("luna-home").resolve("index");
    }

    public void create() {
        try {
            Files.createDirectories(root);
            Files.createDirectories(data());
            Files.createDirectories(runs());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create workdir " + root, e);
        }
    }

    /** New directory runs/&lt;date-time&gt;-&lt;kind&gt;. */
    public Path newRun(String kind) {
        String base = LocalDateTime.now().format(RUN_NAME) + "-" + kind;
        try {
            Path dir = runs().resolve(base);
            for (int i = 2; Files.exists(dir); i++) {
                dir = runs().resolve(base + "-" + i);
            }
            return Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<ServerInfo> readServerInfo() {
        if (!Files.isRegularFile(serverInfo())) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readValue(serverInfo().toFile(), ServerInfo.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public void writeServerInfo(ServerInfo info) {
        JSON.writeValue(serverInfo().toFile(), info);
    }

    public void deleteServerInfo() {
        try {
            Files.deleteIfExists(serverInfo());
        } catch (IOException ignored) {
        }
    }

    /** Luna started by luna-sdk from a workdir. */
    public static class ServerInfo {
        /** Process of luna-sdk; stopping it stops Tomcat and the database container. */
        public long pid;
        public String url;
        public int jmxPort;
        public String startedAt;
        public String jvmArgs;

        public boolean isAlive() {
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        }
    }
}
