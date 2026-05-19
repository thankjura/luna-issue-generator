package ru.slie.luna.rest.client.generator;

import picocli.CommandLine;
import ru.slie.luna.rest.client.LunaRestClient;
import ru.slie.luna.rest.client.generator.commands.ProjectCommand;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "luna-issue-generator",
        mixinStandardHelpOptions = true,
        subcommands = {
                ProjectCommand.class,
        })
public class MainCommand implements Callable<Integer> {
    @CommandLine.Option(names = {"-b", "--base-url"}, description = "Адрес сервера", required = true, scope = CommandLine.ScopeType.INHERIT)
    private String baseUrl;

    @CommandLine.Option(names = {"-u", "--user"}, description = "Имя пользователя", required = true, scope = CommandLine.ScopeType.INHERIT)
    private String user;

    @CommandLine.Option(names = {"-p", "--password"}, description = "Пароль", required = true, scope = CommandLine.ScopeType.INHERIT)
    private String password;

    public LunaRestClient getLunaClient() {
        return new LunaRestClient(baseUrl, user, password);
    }

    public static void main(String[] args) {
        CommandLine cmd = new CommandLine(new MainCommand());
        int exitCode = cmd.execute(args);
        System.exit(exitCode);
    }


    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }
}
