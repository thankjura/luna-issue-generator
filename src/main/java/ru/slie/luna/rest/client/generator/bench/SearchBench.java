package ru.slie.luna.rest.client.generator.bench;

import org.springframework.web.client.HttpStatusCodeException;
import ru.slie.luna.rest.client.LunaRestClient;
import ru.slie.luna.rest.client.generator.data.FieldKind;
import ru.slie.luna.rest.client.generator.data.GeneratorConfig;
import ru.slie.luna.rest.client.generator.data.TextPool;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Search bench over the data of data/summary.json:
 * <ol>
 *     <li>correctness: every control value finds exactly the issues it was given to;</li>
 *     <li>consistency of every searchable field: "is EMPTY" + "is not EMPTY" = all issues,
 *     for numbers and dates "&lt; X" + "&gt;= X" = "is not EMPTY";</li>
 *     <li>latency of typical queries for every field type, sequentially;</li>
 *     <li>throughput with several parallel clients.</li>
 * </ol>
 */
public class SearchBench {
    private static final Map<FieldKind, String> SPLIT_VALUE = Map.of(
            FieldKind.INTEGER, "500000",
            FieldKind.DOUBLE, "5000",
            FieldKind.DATE, "2023-07-01",
            FieldKind.DATE_TIME, "\"2023-07-01 12:00\"");
    private static final Map<FieldKind, String> RANGE = Map.of(
            FieldKind.INTEGER, "%1$s >= 250000 and %1$s < 750000",
            FieldKind.DOUBLE, "%1$s >= 2500 and %1$s < 7500",
            FieldKind.DATE, "%1$s >= 2021-01-01 and %1$s < 2023-01-01",
            FieldKind.DATE_TIME, "%1$s >= \"2021-01-01 00:00\" and %1$s < \"2023-01-01 00:00\"");

    private final GeneratorConfig config;
    private final GeneratorConfig.Search cfg;
    private final Workdir workdir;
    private final LunaRestClient client;
    private final PrintWriter out;
    private final Path runDir;
    private final ServerMonitor monitor;

    public SearchBench(GeneratorConfig config, Workdir workdir, Path runDir, ServerMonitor monitor, PrintWriter out) {
        this.config = config;
        this.cfg = config.bench.search;
        this.workdir = workdir;
        this.runDir = runDir;
        this.monitor = monitor;
        this.out = out;
        int maxClients = cfg.concurrency.stream().mapToInt(Integer::intValue).max().orElse(1);
        this.client = new LunaRestClient(config.server.url, config.server.user, config.server.password, maxClients + 4);
    }

    public Map<String, Object> run() throws IOException {
        JsonNode summary = Workdir.JSON.readTree(workdir.summary().toFile());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("startedAt", OffsetDateTime.now().toString());
        report.put("server", config.server.url);
        report.put("issuesOnServer", count(""));
        report.put("settings", cfg);

        double t0 = monitor.elapsed();
        report.put("correctness", correctness(summary));
        report.put("consistency", consistency(summary));
        List<QueryResult> latency = latency(summary);
        report.put("latency", summarizeLatency(latency, t0));
        report.put("throughput", throughput(latency));
        report.put("load", monitor.stats(t0, monitor.elapsed()));
        report.put("finishedAt", OffsetDateTime.now().toString());
        return report;
    }

    // ------------------------------------------------------------------ correctness

    private Map<String, Object> correctness(JsonNode summary) throws IOException {
        out.println("\n== Проверка контрольных значений ==");
        Map<String, int[]> byType = new TreeMap<>();
        List<String> lines = new ArrayList<>(List.of("type,field,query,expected,actual,ok,ms"));
        List<Map<String, Object>> failures = new ArrayList<>();
        for (String[] check : correctnessChecks(summary)) {
            String type = check[0];
            String field = check[1];
            String query = check[2];
            long expected = Long.parseLong(check[3]);
            long start = System.nanoTime();
            Object actual = countOrError(query);
            double ms = (System.nanoTime() - start) / 1e6;
            boolean ok = actual instanceof Long l && l == expected;
            int[] counter = byType.computeIfAbsent(type, t -> new int[2]);
            counter[0]++;
            if (!ok) {
                counter[1]++;
                failures.add(Map.of("type", type, "query", query, "expected", expected, "actual", actual));
            }
            lines.add(String.join(",", type, field, csv(query), String.valueOf(expected),
                                  csv(String.valueOf(actual)), String.valueOf(ok), String.format(Locale.ROOT, "%.1f", ms)));
        }
        Files.write(runDir.resolve("correctness.csv"), lines);
        int total = 0;
        int failed = 0;
        for (Map.Entry<String, int[]> e : byType.entrySet()) {
            int[] c = e.getValue();
            total += c[0];
            failed += c[1];
            out.printf("  %-4s %-14s %d/%d%n", c[1] == 0 ? "OK" : "FAIL", e.getKey(), c[0] - c[1], c[0]);
        }
        failures.stream().limit(10).forEach(f -> out.printf("    %s: ожидалось %s, получено %s%n", f.get("query"), f.get("expected"), f.get("actual")));
        out.printf("  Итого: %d проверок, ошибок %d%n", total, failed);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("failed", failed);
        result.put("failures", failures);
        return result;
    }

    /** Result of a count request: a number, an unsupported operator or another error. */
    private record Count(Long value, String error, boolean unsupported) {
        boolean ok() {
            return value != null;
        }

        @Override
        public String toString() {
            return value != null ? value.toString() : error;
        }
    }

    /**
     * Control values of summary.json (type, field, query, expected count) and checks derived from them:
     * {@code =} with the whole value of single-line fields that index it, substrings of the summary token
     * (part of a word) by {@code summary ~} and {@code text ~}.
     */
    private static List<String[]> correctnessChecks(JsonNode summary) {
        Map<String, String> searcherOf = new HashMap<>();
        for (JsonNode f : summary.path("fields").path("list")) {
            searcherOf.put(f.path("id").asString(), f.path("searcher").asString(""));
        }
        List<String[]> checks = new ArrayList<>();
        for (JsonNode c : summary.path("controls")) {
            String type = c.path("type").asString();
            String field = c.path("field").asString();
            String expected = String.valueOf(c.path("expected").asLong());
            checks.add(new String[]{type, field, c.path("query").asString(), expected});
            String value = c.path("value").isString() ? c.path("value").asString() : null;
            if (value == null) {
                continue;
            }
            String searcher = searcherOf.getOrDefault(field, "");
            if ("string".equals(type) && (searcher.endsWith(".StringFieldSearcher") || searcher.endsWith(".SubstringFieldSearcher"))) {
                checks.add(new String[]{"string (=)", field, String.format("%s = \"%s\"", field, value.toUpperCase(Locale.ROOT)), expected});
            }
            if ("summary".equals(type) && value.length() > 6) {
                String part = value.substring(3);
                checks.add(new String[]{"summary (substring)", field, String.format("summary ~ \"%s\"", part), expected});
                checks.add(new String[]{"text (substring)", field, String.format("text ~ \"%s\"", part), expected});
            }
        }
        return checks;
    }

    private Map<String, Object> consistency(JsonNode summary) throws IOException {
        out.println("\n== Согласованность поиска по полям ==");
        long all = count("");
        List<String> lines = new ArrayList<>(List.of("type,field,check,actual,expected,result"));
        List<Map<String, Object>> failures = new ArrayList<>();
        Map<String, int[]> byType = new TreeMap<>();
        for (JsonNode f : summary.path("fields").path("list")) {
            if (f.path("searcher").isNull() || f.path("searcher").isMissingNode()) {
                continue;
            }
            String id = f.path("id").asString();
            String type = f.path("type").asString();
            FieldKind kind = FieldKind.byConfigName(type).orElse(null);
            Long filled = f.has("filled") ? f.path("filled").asLong() : null;
            Count empty = countResult(id + " is EMPTY");
            Count notEmpty = countResult(id + " is not EMPTY");
            if (filled != null) {
                check(byType, lines, failures, type, id, "is not EMPTY = issues with a value", notEmpty, filled);
                check(byType, lines, failures, type, id, "is EMPTY = issues without a value", empty, all - filled);
            } else {
                Count sum = empty.ok() && notEmpty.ok() ? new Count(empty.value + notEmpty.value, null, false)
                        : (empty.ok() ? notEmpty : empty);
                check(byType, lines, failures, type, id, "EMPTY + not EMPTY = all", sum, all);
            }
            String split = kind == null ? null : SPLIT_VALUE.get(kind);
            Long withValue = filled != null ? filled : notEmpty.value;
            if (split != null && withValue != null) {
                Count lt = countResult(id + " < " + split);
                Count gte = countResult(id + " >= " + split);
                Count parts = lt.ok() && gte.ok() ? new Count(lt.value + gte.value, null, false) : (lt.ok() ? gte : lt);
                check(byType, lines, failures, type, id, "< X + >= X = issues with a value", parts, withValue);
            }
        }
        Files.write(runDir.resolve("consistency.csv"), lines);
        int total = 0;
        int failed = 0;
        int unsupported = 0;
        for (Map.Entry<String, int[]> e : byType.entrySet()) {
            int[] c = e.getValue();
            total += c[0];
            failed += c[1];
            unsupported += c[2];
            int checked = c[0] - c[2];
            String status = c[1] > 0 ? "FAIL" : checked > 0 ? "OK" : "-";
            out.printf("  %-4s %-14s %d/%d%s%n", status, e.getKey(), checked - c[1], checked,
                       c[2] > 0 ? "  (не поддерживается: " + c[2] + ")" : "");
        }
        failures.stream().limit(15).forEach(f -> out.printf("    %s %s: %s, а должно быть %s%n", f.get("field"), f.get("check"), f.get("actual"), f.get("expected")));
        out.printf("  Итого: %d проверок, ошибок %d, операторы не поддерживаются: %d%n", total - unsupported, failed, unsupported);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total - unsupported);
        result.put("failed", failed);
        result.put("unsupported", unsupported);
        result.put("failures", failures);
        return result;
    }

    /** counters per type: checks, failed, unsupported */
    private static void check(Map<String, int[]> byType, List<String> lines, List<Map<String, Object>> failures,
                              String type, String field, String check, Count actual, long expected) {
        int[] c = byType.computeIfAbsent(type, t -> new int[3]);
        c[0]++;
        String result;
        if (actual.unsupported()) {
            c[2]++;
            result = "unsupported";
        } else if (actual.ok() && actual.value() == expected) {
            result = "ok";
        } else {
            c[1]++;
            result = "fail";
            failures.add(Map.of("type", type, "field", field, "check", check, "actual", actual.toString(), "expected", expected));
        }
        lines.add(String.join(",", type, field, csv(check), csv(actual.toString()), String.valueOf(expected), result));
    }

    // ------------------------------------------------------------------ latency

    private record BenchQuery(String name, String kind, String qs) {
    }

    private static final class QueryResult {
        final BenchQuery query;
        final List<Double> millis = new ArrayList<>();
        long hits = -1;
        String error;
        boolean unsupported;

        QueryResult(BenchQuery query) {
            this.query = query;
        }
    }

    private List<BenchQuery> buildQueries(JsonNode summary) {
        List<BenchQuery> queries = new ArrayList<>();
        TextPool text = new TextPool(summary.path("seed").asLong(config.seed));
        String word = text.word(0);
        List<String> projects = new ArrayList<>();
        summary.path("projects").forEach(p -> projects.add(p.path("key").asString()));

        queries.add(new BenchQuery("all", "system", ""));
        queries.add(new BenchQuery("all-order-created", "system", "order by created desc"));
        if (!projects.isEmpty()) {
            queries.add(new BenchQuery("project", "system", "project = " + projects.getFirst()));
            queries.add(new BenchQuery("project-created-range", "system",
                    "project = " + projects.getFirst() + " and created >= 2000-01-01 order by updated desc"));
        }
        queries.add(new BenchQuery("summary-word", "system", "summary ~ \"" + word + "\""));

        Map<String, JsonNode> firstControl = new HashMap<>();
        for (JsonNode c : summary.path("controls")) {
            firstControl.putIfAbsent(c.path("field").asString(), c);
        }
        Set<FieldKind> seen = new HashSet<>();
        Map<FieldKind, String> fieldOfKind = new EnumMap<>(FieldKind.class);
        for (JsonNode f : summary.path("fields").path("list")) {
            FieldKind kind = FieldKind.byConfigName(f.path("type").asString()).orElse(null);
            if (kind == null || f.path("searcher").isNull() || f.path("searcher").isMissingNode() || !seen.add(kind)) {
                continue;
            }
            String id = f.path("id").asString();
            String type = kind.configName();
            fieldOfKind.put(kind, id);
            JsonNode control = firstControl.get(id);
            if (control != null) {
                queries.add(new BenchQuery(type + "-eq", type, control.path("query").asString()));
            }
            queries.add(new BenchQuery(type + "-not-empty", type, id + " is not EMPTY"));
            queries.add(new BenchQuery(type + "-sort", type, id + " is not EMPTY order by " + id + " desc"));
            if (RANGE.containsKey(kind)) {
                queries.add(new BenchQuery(type + "-range", type, String.format(RANGE.get(kind), id)));
            }
            if (kind == FieldKind.STRING || kind == FieldKind.TEXT) {
                queries.add(new BenchQuery(type + "-word", type, id + " ~ \"" + word + "\""));
            }
            if (kind == FieldKind.LABEL) {
                queries.add(new BenchQuery(type + "-in", type, id + " in (\"lbl1\", \"lbl2\", \"lbl3\")"));
            }
        }
        String integer = fieldOfKind.get(FieldKind.INTEGER);
        String label = fieldOfKind.get(FieldKind.LABEL);
        String date = fieldOfKind.get(FieldKind.DATE);
        if (integer != null && label != null && date != null) {
            queries.add(new BenchQuery("combined", "combined",
                    String.format("(%s >= 250000 and %s = \"lbl7\") or %s < 2020-06-01 order by %s", integer, label, date, date)));
        }
        for (GeneratorConfig.NamedQuery q : cfg.queries) {
            queries.add(new BenchQuery(q.name, "custom", q.qs));
        }
        return queries;
    }

    private List<QueryResult> latency(JsonNode summary) {
        List<BenchQuery> queries = buildQueries(summary);
        out.printf("%n== Время запросов (прогрев %d, замеров %d, страница %d) ==%n", cfg.warmup, cfg.repeat, cfg.pageSize);
        out.printf("  %-26s %10s %8s %8s %8s %8s%n", "запрос", "найдено", "min", "p50", "p95", "max");
        List<QueryResult> results = new ArrayList<>();
        for (BenchQuery query : queries) {
            QueryResult r = new QueryResult(query);
            results.add(r);
            for (int i = 0; i < cfg.warmup + cfg.repeat && r.error == null; i++) {
                long start = System.nanoTime();
                try {
                    r.hits = search(query.qs);
                    if (i >= cfg.warmup) {
                        r.millis.add((System.nanoTime() - start) / 1e6);
                    }
                } catch (HttpStatusCodeException e) {
                    r.error = e.getStatusCode().value() + " " + shorten(e.getResponseBodyAsString());
                    r.unsupported = isUnsupported(e);
                }
            }
            if (r.unsupported) {
                out.printf("  %-26s не поддерживается%n", query.name);
            } else if (r.error != null) {
                out.printf("  %-26s ошибка: %s%n", query.name, r.error);
            } else {
                double[] s = sorted(r.millis);
                out.printf(Locale.ROOT, "  %-26s %10d %8.1f %8.1f %8.1f %8.1f%n", query.name, r.hits, s[0],
                           percentile(s, 50), percentile(s, 95), s[s.length - 1]);
            }
        }
        return results;
    }

    private Map<String, Object> summarizeLatency(List<QueryResult> results, double t0) throws IOException {
        List<String> lines = new ArrayList<>(List.of("name,kind,qs,hits,min_ms,p50_ms,p95_ms,max_ms,avg_ms,error"));
        List<Map<String, Object>> queries = new ArrayList<>();
        for (QueryResult r : results) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.query.name);
            m.put("kind", r.query.kind);
            m.put("qs", r.query.qs);
            if (r.error != null) {
                m.put(r.unsupported ? "unsupported" : "error", r.error);
                lines.add(String.join(",", r.query.name, r.query.kind, csv(r.query.qs), "", "", "", "", "", "", csv(r.error)));
            } else {
                double[] s = sorted(r.millis);
                double avg = r.millis.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                m.put("hits", r.hits);
                m.put("minMs", ServerMonitor.round(s[0]));
                m.put("p50Ms", ServerMonitor.round(percentile(s, 50)));
                m.put("p95Ms", ServerMonitor.round(percentile(s, 95)));
                m.put("maxMs", ServerMonitor.round(s[s.length - 1]));
                m.put("avgMs", ServerMonitor.round(avg));
                lines.add(String.join(",", r.query.name, r.query.kind, csv(r.query.qs), String.valueOf(r.hits),
                                      fmt(s[0]), fmt(percentile(s, 50)), fmt(percentile(s, 95)), fmt(s[s.length - 1]), fmt(avg), ""));
            }
            queries.add(m);
        }
        Files.write(runDir.resolve("latency.csv"), lines);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queries", queries);
        result.put("errors", results.stream().filter(r -> r.error != null && !r.unsupported).count());
        result.put("unsupported", results.stream().filter(r -> r.unsupported).count());
        return result;
    }

    // ------------------------------------------------------------------ throughput

    private List<Map<String, Object>> throughput(List<QueryResult> latency) throws IOException {
        List<BenchQuery> queries = latency.stream().filter(r -> r.error == null).map(r -> r.query).toList();
        List<Map<String, Object>> levels = new ArrayList<>();
        if (cfg.concurrency.isEmpty() || queries.isEmpty()) {
            return levels;
        }
        out.printf("%n== Пропускная способность (%d с на уровень, все запросы вперемешку) ==%n", cfg.durationSeconds);
        out.printf("  %-8s %10s %8s %8s %8s %8s %8s %8s%n", "клиенты", "запросов", "ошибок", "в сек", "p50", "p95", "p99", "CPU");
        List<String> lines = new ArrayList<>(List.of("clients,requests,errors,per_second,p50_ms,p95_ms,p99_ms,jvm_cpu_cores_avg"));
        for (int clients : cfg.concurrency) {
            ConcurrentLinkedQueue<Double> millis = new ConcurrentLinkedQueue<>();
            AtomicLong errors = new AtomicLong();
            double from = monitor.elapsed();
            long deadline = System.nanoTime() + cfg.durationSeconds * 1_000_000_000L;
            long start = System.nanoTime();
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int c = 0; c < clients; c++) {
                    executor.submit(() -> {
                        ThreadLocalRandom random = ThreadLocalRandom.current();
                        while (System.nanoTime() < deadline) {
                            BenchQuery q = queries.get(random.nextInt(queries.size()));
                            long s = System.nanoTime();
                            try {
                                search(q.qs);
                                millis.add((System.nanoTime() - s) / 1e6);
                            } catch (Exception e) {
                                errors.incrementAndGet();
                            }
                        }
                    });
                }
            }
            double seconds = (System.nanoTime() - start) / 1e9;
            double[] s = sorted(new ArrayList<>(millis));
            Map<String, Object> load = monitor.stats(from, monitor.elapsed());
            Object cpu = load.get("jvm_cpu_cores") instanceof Map<?, ?> m ? m.get("avg") : null;
            Map<String, Object> level = new LinkedHashMap<>();
            level.put("clients", clients);
            level.put("requests", s.length);
            level.put("errors", errors.get());
            level.put("perSecond", ServerMonitor.round(s.length / seconds));
            if (s.length > 0) {
                level.put("p50Ms", ServerMonitor.round(percentile(s, 50)));
                level.put("p95Ms", ServerMonitor.round(percentile(s, 95)));
                level.put("p99Ms", ServerMonitor.round(percentile(s, 99)));
            }
            level.put("load", load);
            levels.add(level);
            out.printf(Locale.ROOT, "  %-8d %10d %8d %8.1f %8.1f %8.1f %8.1f %8s%n", clients, s.length, errors.get(), s.length / seconds,
                       percentile(s, 50), percentile(s, 95), percentile(s, 99), cpu == null ? "-" : cpu);
            lines.add(String.join(",", String.valueOf(clients), String.valueOf(s.length), String.valueOf(errors.get()),
                                  fmt(s.length / seconds), fmt(percentile(s, 50)), fmt(percentile(s, 95)), fmt(percentile(s, 99)),
                                  cpu == null ? "" : cpu.toString()));
        }
        Files.write(runDir.resolve("throughput.csv"), lines);
        return levels;
    }

    // ------------------------------------------------------------------ helpers

    private long search(String qs) {
        Map<String, Object> body = new HashMap<>();
        body.put("qs", qs);
        body.put("fields", List.of("summary"));
        body.put("limit", cfg.pageSize);
        body.put("page", 1);
        return client.postJson("/rest/issues/search", body).path("total").asLong();
    }

    private long count(String qs) {
        return client.postJson("/rest/issues/count", Map.of("qs", qs)).asLong();
    }

    /** Count or the error text. */
    private Object countOrError(String qs) {
        Count c = countResult(qs);
        return c.ok() ? c.value() : c.error();
    }

    private Count countResult(String qs) {
        try {
            return new Count(count(qs), null, false);
        } catch (HttpStatusCodeException e) {
            String body = e.getResponseBodyAsString();
            return new Count(null, "HTTP " + e.getStatusCode().value() + " " + shorten(body), isUnsupported(e));
        }
    }

    /** Validation error saying the field does not support an operator or sorting. */
    private static boolean isUnsupported(HttpStatusCodeException e) {
        String body = e.getResponseBodyAsString();
        return e.getStatusCode().value() == 400 && (body.contains("не поддерживает") || body.contains("not support"));
    }

    private static double[] sorted(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
    }

    private static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%.2f", v);
    }

    private static String shorten(String s) {
        s = s == null ? "" : s.replaceAll("\\s+", " ");
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    static String csv(String s) {
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
