package ru.slie.luna.rest.client.generator.commands;

import picocli.CommandLine;
import ru.slie.luna.rest.client.generator.MainCommand;
import ru.slie.luna.rest.client.generator.bench.Workdir;
import ru.slie.luna.rest.client.generator.data.ConfigLoader;
import ru.slie.luna.rest.client.generator.data.GeneratorConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Options of commands working with a test workdir. */
public class WorkdirOptions {
    @CommandLine.Option(names = {"-w", "--workdir"}, required = true, paramLabel = "DIR",
            description = "Рабочий каталог: Луна, данные генератора и результаты прогонов")
    Path workdir;

    @CommandLine.Option(names = {"-c", "--config"}, paramLabel = "FILE",
            description = "Файл конфигурации (YAML); по умолчанию config.yaml рабочего каталога, если он есть")
    Path config;

    @CommandLine.Option(names = {"-s", "--set"}, paramLabel = "PATH=VALUE",
            description = "Переопределить параметр: --set bench.search.repeat=20")
    List<String> overrides;

    @CommandLine.Option(names = "--attach",
            description = "Работать с Луной по адресу server.url, даже если она запущена не из этого каталога")
    boolean attach;

    public Workdir workdir() {
        return new Workdir(workdir);
    }

    public GeneratorConfig load(MainCommand main) {
        Path file = config;
        Path saved = workdir().config();
        if (file == null && Files.isRegularFile(saved)) {
            file = saved;
        }
        GeneratorConfig cfg = new ConfigLoader().load(file, System.getenv(), overrides);
        if (main.getBaseUrl() != null) {
            cfg.server.url = main.getBaseUrl();
        }
        if (main.getUser() != null) {
            cfg.server.user = main.getUser();
        }
        if (main.getPassword() != null) {
            cfg.server.password = main.getPassword();
        }
        return cfg;
    }
}
