package ru.slie.luna.rest.client.generator.commands;

import picocli.CommandLine;
import ru.slie.luna.rest.client.generator.MainCommand;

@CommandLine.Command(name = "gen",
        description = "Генерация задач",
        subcommands = { ProjectCommand.List.class })
public class GenCommand {
    @CommandLine.ParentCommand
    private MainCommand mainCommand;

    @CommandLine.Option(names = {"-p", "--project"}, description = "Список проектов", defaultValue = "10")
    private int projectKey = 10;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;
}
