package ru.slie.luna.rest.client.generator.commands;

import picocli.CommandLine;
import ru.slie.luna.rest.client.generator.bench.LunaServer;

import java.io.PrintWriter;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "down",
        mixinStandardHelpOptions = true,
        description = "Остановить Луну, запущенную из рабочего каталога (данные сохраняются).")
public class DownCommand implements Callable<Integer> {
    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @CommandLine.Option(names = {"-w", "--workdir"}, required = true, paramLabel = "DIR", description = "Рабочий каталог")
    private java.nio.file.Path workdir;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        try {
            if (!LunaServer.stop(new ru.slie.luna.rest.client.generator.bench.Workdir(workdir), out)) {
                out.println("Луна из этого каталога не запущена");
            }
        } catch (IllegalStateException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 1;
        }
        return 0;
    }
}
