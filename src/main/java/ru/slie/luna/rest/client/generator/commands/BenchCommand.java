package ru.slie.luna.rest.client.generator.commands;

import org.springframework.web.client.HttpStatusCodeException;
import picocli.CommandLine;
import ru.slie.luna.rest.client.generator.MainCommand;
import ru.slie.luna.rest.client.generator.bench.LunaServer;
import ru.slie.luna.rest.client.generator.bench.ReindexBench;
import ru.slie.luna.rest.client.generator.bench.SearchBench;
import ru.slie.luna.rest.client.generator.bench.ServerMonitor;
import ru.slie.luna.rest.client.generator.bench.Workdir;
import ru.slie.luna.rest.client.generator.data.ConfigLoader;
import ru.slie.luna.rest.client.generator.data.GeneratorConfig;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "bench",
        mixinStandardHelpOptions = true,
        description = {
                "Прогоны на стенде, подготовленном командой up. Каждый прогон сохраняется в runs/<дата>-<вид>[-метка]:",
                "report.json, CSV с результатами и samples.csv с нагрузкой на сервер; краткая строка добавляется в runs/index.csv."
        },
        subcommands = {BenchCommand.Search.class, BenchCommand.Reindex.class})
public class BenchCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    MainCommand main;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return 0;
    }

    abstract static class BenchRun implements Callable<Integer> {
        private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

        @CommandLine.ParentCommand
        BenchCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Mixin
        WorkdirOptions options;

        @CommandLine.Option(names = {"-l", "--label"}, paramLabel = "TEXT",
                description = "Метка прогона: добавляется к имени каталога и в runs/index.csv (например, ram-buffer-256)")
        String label;

        @CommandLine.Option(names = "--stop", description = "Остановить Луну после прогона, если она запущена этой командой")
        boolean stop;

        abstract String kind();

        abstract Map<String, Object> bench(GeneratorConfig cfg, Workdir workdir, Path runDir, ServerMonitor monitor, PrintWriter out) throws IOException;

        /** Short result columns for runs/index.csv. */
        abstract String brief(Map<String, Object> report);

        @Override
        public Integer call() throws IOException {
            PrintWriter out = spec.commandLine().getOut();
            Workdir workdir = options.workdir();
            if (!Files.isDirectory(workdir.root())) {
                spec.commandLine().getErr().printf("Нет рабочего каталога %s, подготовьте его командой up%n", workdir.root());
                return 1;
            }
            GeneratorConfig cfg = options.load(parent.main);
            LunaServer server = new LunaServer(cfg, workdir, out);
            String name = kind() + (label == null || label.isBlank() ? "" : "-" + label.replaceAll("[^\\p{L}\\p{N}._-]+", "_"));
            Path runDir = null;
            try {
                Workdir.ServerInfo info = server.ensureRunning(options.attach);
                runDir = workdir.newRun(name);
                Files.writeString(runDir.resolve("config.yaml"), new ConfigLoader().toYaml(cfg));
                out.printf("Прогон: %s%n", runDir);

                Map<String, Object> report = new LinkedHashMap<>();
                report.put("kind", kind());
                report.put("label", label);
                report.put("data", dataFacts(workdir));
                try (ServerMonitor monitor = new ServerMonitor(workdir, info, cfg.bench.sampleMillis, runDir.resolve("samples.csv"), out)) {
                    monitor.start();
                    monitor.printWarnings();
                    report.put("environment", monitor.environment());
                    report.putAll(bench(cfg, workdir, runDir, monitor, out));
                    report.put("warnings", monitor.warnings());
                }
                JSON.writeValue(runDir.resolve("report.json").toFile(), report);
                appendIndex(workdir, runDir, report);
                out.printf("%nОтчёт: %s%n", runDir.resolve("report.json"));
                return 0;
            } catch (IllegalStateException | IllegalArgumentException e) {
                spec.commandLine().getErr().println(e.getMessage());
                return 1;
            } catch (HttpStatusCodeException e) {
                spec.commandLine().getErr().printf("Ошибка сервера: %s %s%n", e.getStatusCode(), e.getResponseBodyAsString());
                return 1;
            } finally {
                if (stop && server.isStartedNow()) {
                    LunaServer.stop(workdir, out);
                }
                out.flush();
            }
        }

        private static Map<String, Object> dataFacts(Workdir workdir) {
            Map<String, Object> facts = new LinkedHashMap<>();
            if (!Files.isRegularFile(workdir.summary())) {
                return facts;
            }
            JsonNode summary = JSON.readTree(workdir.summary().toFile());
            facts.put("issues", summary.path("issues").path("onServer").asLong());
            facts.put("projects", summary.path("projects").size());
            facts.put("users", summary.path("users").path("count").asLong());
            facts.put("fields", summary.path("fields").path("count").asLong());
            facts.put("searchableFields", summary.path("fields").path("searchable").asLong());
            return facts;
        }

        private void appendIndex(Workdir workdir, Path runDir, Map<String, Object> report) throws IOException {
            Path index = workdir.runs().resolve("index.csv");
            if (!Files.exists(index)) {
                Files.writeString(index, "run,kind,label,issues,result\n");
            }
            Object issues = report.get("data") instanceof Map<?, ?> m ? m.get("issues") : "";
            String line = String.join(",", runDir.getFileName().toString(), kind(), label == null ? "" : label,
                                      String.valueOf(issues), "\"" + brief(report).replace("\"", "'") + "\"") + "\n";
            Files.writeString(index, line, StandardOpenOption.APPEND);
        }
    }

    @CommandLine.Command(name = "search", mixinStandardHelpOptions = true,
            description = {"Проверить поиск по контрольным значениям и согласованность по всем полям с поиском,",
                    "замерить время запросов по типам полей и пропускную способность (bench.search)."})
    static class Search extends BenchRun {
        @Override
        String kind() {
            return "search";
        }

        @Override
        Map<String, Object> bench(GeneratorConfig cfg, Workdir workdir, Path runDir, ServerMonitor monitor, PrintWriter out) throws IOException {
            if (!Files.isRegularFile(workdir.summary())) {
                throw new IllegalStateException("Нет " + workdir.summary() + ": сначала сгенерируйте данные командой up");
            }
            return new SearchBench(cfg, workdir, runDir, monitor, out).run();
        }

        @Override
        String brief(Map<String, Object> report) {
            Map<?, ?> correctness = (Map<?, ?>) report.get("correctness");
            Map<?, ?> consistency = (Map<?, ?>) report.get("consistency");
            List<?> throughput = (List<?>) report.get("throughput");
            String best = throughput.stream().map(l -> String.valueOf(((Map<?, ?>) l).get("perSecond")))
                                    .reduce((a, b) -> b).orElse("-");
            return String.format(Locale.ROOT, "controls failed %s/%s; consistency failed %s/%s; max qps %s",
                                 correctness.get("failed"), correctness.get("total"), consistency.get("failed"),
                                 consistency.get("total"), best);
        }
    }

    @CommandLine.Command(name = "reindex", mixinStandardHelpOptions = true,
            description = {"Полная переиндексация с блокировкой: время, задач в секунду, размер индекса",
                    "и нагрузка на сервер (bench.reindex)."})
    static class Reindex extends BenchRun {
        @Override
        String kind() {
            return "reindex";
        }

        @Override
        Map<String, Object> bench(GeneratorConfig cfg, Workdir workdir, Path runDir, ServerMonitor monitor, PrintWriter out) throws IOException {
            return new ReindexBench(cfg, workdir, runDir, monitor, out).run();
        }

        @Override
        String brief(Map<String, Object> report) {
            StringBuilder sb = new StringBuilder();
            for (Object r : (List<?>) report.get("runs")) {
                Map<?, ?> run = (Map<?, ?>) r;
                if (!sb.isEmpty()) {
                    sb.append("; ");
                }
                sb.append(run.get("result")).append(' ').append(run.get("seconds")).append(" s, ")
                  .append(run.get("issuesPerSecond")).append(" issues/s");
            }
            return sb.toString();
        }
    }
}
