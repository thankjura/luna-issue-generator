package ru.slie.luna.rest.client.generator.commands;

import net.datafaker.Faker;
import org.springframework.web.client.HttpClientErrorException;
import picocli.CommandLine;
import ru.slie.luna.rest.client.LunaRestClient;
import ru.slie.luna.rest.client.generator.MainCommand;
import ru.slie.luna.rest.client.generator.ProgressBar;
import ru.slie.luna.rest.client.generator.TextUtils;
import ru.slie.luna.rest.client.model.RemoteSearchResult;
import ru.slie.luna.rest.client.model.RemoteUser;
import ru.slie.luna.rest.client.model.request.RequestUser;

import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;
import java.util.Random;

@CommandLine.Command(name = "user",
        description = "Управление пользователями",
        subcommands = { UserCommand.Count.class, UserCommand.Gen.class })
public class UserCommand implements Runnable {
    private static final Faker fakerRu = new Faker(Locale.of("ru"));

    @CommandLine.ParentCommand
    private MainCommand mainCommand;

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static RequestUser generateUser(int directoryId, String emailDomain) {
        boolean isMale = new Random().nextBoolean();
        String name = isMale? fakerRu.resolve("name.male_first_name") :  fakerRu.resolve("name.female_first_name");
        String lastName = isMale? fakerRu.resolve("name.male_last_name") :  fakerRu.resolve("name.female_last_name");
        String login = TextUtils.translit(lastName + "." + name);
        if (!emailDomain.trim().startsWith("@")) {
            emailDomain = "@" + emailDomain;
        }

        return new RequestUser(directoryId, login, login + emailDomain, name, lastName);
    }

    @CommandLine.Command(name = "count", description = "Вывести кол-во пользователей")
    static class Count implements Runnable {
        @CommandLine.ParentCommand
        private UserCommand parent;

        @CommandLine.Spec
        private CommandLine.Model.CommandSpec spec;

        @Override
        public void run() {
            MainCommand global = parent.mainCommand;
            LunaRestClient client = global.getLunaClient();
            RemoteSearchResult<RemoteUser> data = client.findUsers();
            PrintWriter out = spec.commandLine().getOut();
            out.printf("Пользователей в системе: %s", data.getTotal());
        }
    }

    @CommandLine.Command(name = "gen", description = "Сгенерировать")
    static class Gen implements Runnable {
        @CommandLine.ParentCommand
        private UserCommand parent;

        @CommandLine.Spec
        private CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = {"-d", "--directory"}, description = "Директория пользователей", defaultValue = "1")
        private int directory = 1;

        @CommandLine.Option(names = {"-e", "--email-domain"}, description = "Домен почты", defaultValue = "example.com")
        private String emailDomain = "example.com";

        @CommandLine.Option(names = {"-c", "--count"}, description = "Кол-во пользователей", defaultValue = "1")
        private int count = 1;

        @CommandLine.Option(names = {"-g", "--group"}, split = ",", description = "Добавить в группу")
        private List<String> groups;

        @Override
        public void run() {
            MainCommand global = parent.mainCommand;
            LunaRestClient client = global.getLunaClient();
            int created = 0;
            PrintWriter out = spec.commandLine().getOut();
            PrintWriter err = spec.commandLine().getErr();
            ProgressBar progressBar = new ProgressBar(out, count);
            progressBar.print(0, "");

            while (created < count) {
                try {
                    created++;
                    RemoteUser user = client.createUser(generateUser(directory, emailDomain));
                    progressBar.print(++created, String.format("%-20s, %s.", user.getLogin(), user.getDisplayName()));
                    if (groups != null && !groups.isEmpty()) {
                        client.addUserToGroups(user.getLogin(), groups);
                        progressBar.append(String.format(" Группы: %s", groups));
                    }
                } catch (HttpClientErrorException.BadRequest e) {
                    err.println(e.getMessage());
                }
            }
        }
    }
}
