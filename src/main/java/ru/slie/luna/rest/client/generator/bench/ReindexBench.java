package ru.slie.luna.rest.client.generator.bench;

import ru.slie.luna.rest.client.LunaRestClient;
import ru.slie.luna.rest.client.generator.data.GeneratorConfig;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Stream;

/**
 * Full blocking reindex: time of the planning and indexing phases, issues per second,
 * index size and server load (CPU, heap, GC, disk, database) during the run.
 */
public class ReindexBench {
    private final GeneratorConfig config;
    private final Workdir workdir;
    private final Path runDir;
    private final ServerMonitor monitor;
    private final PrintWriter out;
    private final LunaRestClient client;

    public ReindexBench(GeneratorConfig config, Workdir workdir, Path runDir, ServerMonitor monitor, PrintWriter out) {
        this.config = config;
        this.workdir = workdir;
        this.runDir = runDir;
        this.monitor = monitor;
        this.out = out;
        this.client = new LunaRestClient(config.server.url, config.server.user, config.server.password, 4);
    }

    public Map<String, Object> run() throws IOException {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("startedAt", OffsetDateTime.now().toString());
        report.put("server", config.server.url);
        JsonNode stats = client.getJson("/rest/index/stats");
        report.put("issuesInDb", stats.path("storedIssuesCount").asLong());
        report.put("indexedBefore", stats.path("indexedIssuesCount").asLong());
        report.put("indexSizeBeforeMb", indexSizeMb());

        List<String> progress = new ArrayList<>(List.of("run,elapsed_s,phase,completed,total,failures"));
        List<Map<String, Object>> runs = new ArrayList<>();
        for (int i = 1; i <= Math.max(1, config.bench.reindex.repeat); i++) {
            runs.add(reindex(i, progress));
        }
        Files.write(runDir.resolve("progress.csv"), progress);
        report.put("runs", runs);

        if (runs.size() > 1) {
            DoubleSummaryStatistics seconds = runs.stream().mapToDouble(r -> ((Number) r.get("seconds")).doubleValue()).summaryStatistics();
            report.put("secondsAvg", ServerMonitor.round(seconds.getAverage()));
            report.put("secondsMin", ServerMonitor.round(seconds.getMin()));
            report.put("secondsMax", ServerMonitor.round(seconds.getMax()));
        }
        JsonNode after = client.getJson("/rest/index/stats");
        report.put("indexedAfter", after.path("indexedIssuesCount").asLong());
        report.put("indexSizeAfterMb", indexSizeMb());
        report.put("finishedAt", OffsetDateTime.now().toString());
        return report;
    }

    private Map<String, Object> reindex(int number, List<String> progress) {
        out.printf("%n== Полная переиндексация с блокировкой, прогон %d ==%n", number);
        double from = monitor.elapsed();
        long start = System.nanoTime();
        JsonNode status = client.postJson("/rest/index/start_reindex", Map.of("background", false));
        String taskId = status.path("id").asString();
        Double indexingStartedAt = null;
        String phase = status.path("phase").asString();
        int pollErrors = 0;
        while (!"done".equals(phase) && !"fail".equals(phase)) {
            LunaServer.sleep(Math.max(100, config.bench.reindex.pollMillis));
            try {
                status = client.getJson("/rest/index/tasks/{id}", taskId);
            } catch (Exception e) {
                if (++pollErrors > 60) {
                    throw new IllegalStateException("Не удаётся получить состояние переиндексации: " + e.getMessage(), e);
                }
                continue;
            }
            phase = status.path("phase").asString();
            double elapsed = (System.nanoTime() - start) / 1e9;
            if (indexingStartedAt == null && !"calc".equals(phase)) {
                indexingStartedAt = elapsed;
            }
            long completed = status.path("completed").asLong();
            long total = status.path("total").asLong();
            progress.add(String.format(Locale.ROOT, "%d,%.1f,%s,%d,%d,%d", number, elapsed, phase, completed, total,
                                       status.path("failures").asLong()));
            printProgress(elapsed, phase, completed, total, indexingStartedAt);
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        out.println();
        long completed = status.path("completed").asLong();
        long total = status.path("total").asLong();
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("run", number);
        run.put("taskId", taskId);
        run.put("result", phase);
        run.put("seconds", ServerMonitor.round(seconds));
        run.put("serverDurationMs", status.path("duration").asLong());
        double calc = indexingStartedAt == null ? seconds : indexingStartedAt;
        run.put("planningSeconds", ServerMonitor.round(calc));
        run.put("indexingSeconds", ServerMonitor.round(seconds - calc));
        run.put("issues", total);
        run.put("indexed", completed);
        run.put("failures", status.path("failures").asLong());
        run.put("issuesPerSecond", ServerMonitor.round(completed / Math.max(0.001, seconds)));
        run.put("indexSizeMb", indexSizeMb());
        Map<String, Object> load = monitor.stats(from, monitor.elapsed());
        run.put("load", load);

        out.printf(Locale.ROOT, "  Результат: %s, %d из %d задач за %.1f с (планирование %.1f с), %.0f задач/с, ошибок %d%n",
                   phase, completed, total, seconds, calc, completed / Math.max(0.001, seconds), status.path("failures").asLong());
        out.printf(Locale.ROOT, "  Нагрузка: CPU Луны %s ядер (макс %s), heap %s МБ (макс %s), паузы GC %s мс (%s%%), CPU БД %s%%, индекс %.0f МБ%n",
                   avg(load, "jvm_cpu_cores"), max(load, "jvm_cpu_cores"), avg(load, "heap_used_mb"), max(load, "heap_used_mb"),
                   load.getOrDefault("gc_pause_ms", "-"), load.getOrDefault("gc_pause_share_pct", "-"), avg(load, "db_cpu_pct"),
                   indexSizeMb());
        if ("fail".equals(phase)) {
            out.println("  Переиндексация завершилась ошибкой, см. журнал Луны");
        }
        return run;
    }

    private void printProgress(double elapsed, String phase, long completed, long total, Double indexingStartedAt) {
        Map<String, Double> last = monitor.latest();
        double rate = indexingStartedAt == null || elapsed <= indexingStartedAt ? 0 : completed / (elapsed - indexingStartedAt);
        out.printf(Locale.ROOT, "\r  %6.0f с  %-8s %,d/%,d (%.0f%%)  %,.0f задач/с  CPU %s  heap %s МБ  БД %s%%    ",
                   elapsed, phase, completed, total, total > 0 ? 100.0 * completed / total : 0, rate,
                   fmt(last.get("jvm_cpu_cores")), fmt0(last.get("heap_used_mb")), fmt0(last.get("db_cpu_pct")));
        out.flush();
    }

    private double indexSizeMb() {
        Path index = workdir.index();
        if (!Files.isDirectory(index)) {
            return Double.NaN;
        }
        try (Stream<Path> files = Files.walk(index)) {
            long bytes = files.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
            return ServerMonitor.round(bytes / 1048576.0);
        } catch (IOException e) {
            return Double.NaN;
        }
    }

    private static Object avg(Map<String, Object> load, String key) {
        return load.get(key) instanceof Map<?, ?> m ? m.get("avg") : "-";
    }

    private static Object max(Map<String, Object> load, String key) {
        return load.get(key) instanceof Map<?, ?> m ? m.get("max") : "-";
    }

    private static String fmt(Double v) {
        return v == null || v.isNaN() ? "-" : String.format(Locale.ROOT, "%.1f", v);
    }

    private static String fmt0(Double v) {
        return v == null || v.isNaN() ? "-" : String.format(Locale.ROOT, "%.0f", v);
    }
}
