package ru.slie.luna.rest.client.generator;

import picocli.CommandLine;
import ru.slie.luna.rest.client.LunaRestClient;
import ru.slie.luna.rest.client.generator.commands.BenchCommand;
import ru.slie.luna.rest.client.generator.commands.DownCommand;
import ru.slie.luna.rest.client.generator.commands.GenerateCommand;
import ru.slie.luna.rest.client.generator.commands.IssueCommand;
import ru.slie.luna.rest.client.generator.commands.ProjectCommand;
import ru.slie.luna.rest.client.generator.commands.UpCommand;
import ru.slie.luna.rest.client.generator.commands.UserCommand;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "luna-issue-generator",
        mixinStandardHelpOptions = true,
        subcommands = {
                ProjectCommand.class,
                UserCommand.class,
                IssueCommand.class,
                GenerateCommand.class,
                UpCommand.class,
                DownCommand.class,
                BenchCommand.class,
        })
public class MainCommand implements Callable<Integer> {
    @CommandLine.Option(names = {"-b", "--base-url"}, description = "Адрес сервера")
    private String baseUrl;

    @CommandLine.Option(names = {"-u", "--user"}, description = "Имя пользователя")
    private String user;

    @CommandLine.Option(names = {"-p", "--password"}, description = "Пароль")
    private String password;

    public LunaRestClient getLunaClient() {
        if (baseUrl == null || user == null || password == null) {
            throw new CommandLine.ParameterException(new CommandLine(this), "Укажите -b, -u и -p");
        }
        return new LunaRestClient(baseUrl, user, password);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getUser() {
        return user;
    }

    public String getPassword() {
        return password;
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
