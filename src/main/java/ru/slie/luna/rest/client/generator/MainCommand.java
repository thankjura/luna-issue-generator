package ru.slie.luna.rest.client.generator;

import picocli.CommandLine;
import ru.slie.luna.rest.client.JiraRestClient;
import ru.slie.luna.rest.client.generator.commands.IssueCommand;
import ru.slie.luna.rest.client.generator.commands.UserCommand;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "luna-issue-generator",
        mixinStandardHelpOptions = true,
        subcommands = {
                UserCommand.class,
                IssueCommand.class,
        })
public class MainCommand implements Callable<Integer> {
    @CommandLine.Option(names = {"-b", "--base-url"}, description = "Адрес сервера", required = true)
    private String baseUrl;

    @CommandLine.Option(names = {"-u", "--user"}, description = "Имя пользователя", required = true)
    private String user;

    @CommandLine.Option(names = {"-p", "--password"}, description = "Пароль", required = true)
    private String password;

    public JiraRestClient getLunaClient() {
        return new JiraRestClient(baseUrl, user, password);
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
