package ru.slie.luna.rest.client.generator.commands;

import org.springframework.web.client.HttpStatusCodeException;
import picocli.CommandLine;
import ru.slie.luna.rest.client.generator.MainCommand;
import ru.slie.luna.rest.client.generator.data.ConfigLoader;
import ru.slie.luna.rest.client.generator.data.DataGenerator;
import ru.slie.luna.rest.client.generator.data.GeneratorConfig;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "generate",
        mixinStandardHelpOptions = true,
        description = {
                "Сгенерировать тестовые данные: пользователей, проекты, доп. поля всех типов и задачи.",
                "Повторный запуск с тем же каталогом продолжает прерванную генерацию.",
                "Настройки: generator-default.yaml < файл -c < переменные LUNA_GEN_* < --set."
        })
public class GenerateCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private MainCommand main;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @CommandLine.Option(names = {"-c", "--config"}, description = "Файл конфигурации (YAML)")
    private Path config;

    @CommandLine.Option(names = {"-o", "--out"}, description = "Каталог для состояния и сводки (по умолчанию: ${DEFAULT-VALUE})",
            defaultValue = "generator-data")
    private Path outDir;

    @CommandLine.Option(names = {"-s", "--set"}, description = "Переопределить параметр: --set issues.count=1000000")
    private List<String> overrides;

    @CommandLine.Option(names = "--print-config", description = "Вывести итоговую конфигурацию и выйти")
    private boolean printConfig;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        ConfigLoader loader = new ConfigLoader();
        GeneratorConfig cfg = loader.load(config, System.getenv(), overrides);
        if (main.getBaseUrl() != null) {
            cfg.server.url = main.getBaseUrl();
        }
        if (main.getUser() != null) {
            cfg.server.user = main.getUser();
        }
        if (main.getPassword() != null) {
            cfg.server.password = main.getPassword();
        }

        if (printConfig) {
            out.println(loader.toYaml(cfg));
            return 0;
        }

        DataGenerator generator = new DataGenerator(cfg, outDir.toAbsolutePath(), out);
        try {
            generator.run();
        } catch (IllegalStateException | IllegalArgumentException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 1;
        } catch (HttpStatusCodeException e) {
            spec.commandLine().getErr().printf("Ошибка сервера: %s %s%n", e.getStatusCode(), e.getResponseBodyAsString());
            return 1;
        } finally {
            out.printf("Сводка: %s%n", generator.getSummaryPath());
            out.flush();
        }
        return 0;
    }
}
