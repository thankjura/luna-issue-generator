package ru.slie.luna.rest.client.generator.commands;

import org.springframework.web.client.HttpStatusCodeException;
import picocli.CommandLine;
import ru.slie.luna.rest.client.generator.MainCommand;
import ru.slie.luna.rest.client.generator.bench.LunaServer;
import ru.slie.luna.rest.client.generator.bench.Workdir;
import ru.slie.luna.rest.client.generator.data.ConfigLoader;
import ru.slie.luna.rest.client.generator.data.DataGenerator;
import ru.slie.luna.rest.client.generator.data.GeneratorConfig;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "up",
        mixinStandardHelpOptions = true,
        description = {
                "Подготовить стенд: создать рабочий каталог, запустить Луну через luna-sdk и сгенерировать данные.",
                "Повторный запуск продолжает прерванную генерацию или догенерирует задачи до issues.count.",
                "Луна остаётся работать после выхода; остановить её: down -w DIR."
        })
public class UpCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private MainCommand main;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @CommandLine.Mixin
    private WorkdirOptions options;

    @CommandLine.Option(names = "--no-generate", description = "Только запустить Луну")
    private boolean noGenerate;

    @CommandLine.Option(names = "--stop", description = "Остановить Луну после генерации")
    private boolean stop;

    @Override
    public Integer call() throws IOException {
        PrintWriter out = spec.commandLine().getOut();
        Workdir workdir = options.workdir();
        workdir.create();
        GeneratorConfig cfg = options.load(main);
        Files.writeString(workdir.config(), new ConfigLoader().toYaml(cfg));
        out.printf("Рабочий каталог: %s%n", workdir.root());

        try {
            new LunaServer(cfg, workdir, out).ensureRunning(options.attach);
            if (!noGenerate) {
                DataGenerator generator = new DataGenerator(cfg, workdir.data(), out);
                try {
                    generator.run();
                } finally {
                    out.printf("Сводка: %s%n", generator.getSummaryPath());
                }
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 1;
        } catch (HttpStatusCodeException e) {
            spec.commandLine().getErr().printf("Ошибка сервера: %s %s%n", e.getStatusCode(), e.getResponseBodyAsString());
            return 1;
        } finally {
            if (stop) {
                LunaServer.stop(workdir, out);
            }
            out.flush();
        }
        if (!stop) {
            out.printf("Луна работает: %s (admin/%s). Остановить: down -w %s%n", cfg.server.url, cfg.server.password, workdir.root());
        }
        return 0;
    }
}
