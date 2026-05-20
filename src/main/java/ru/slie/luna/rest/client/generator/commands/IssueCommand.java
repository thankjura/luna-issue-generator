package ru.slie.luna.rest.client.generator.commands;

import net.datafaker.Faker;
import org.springframework.web.client.HttpClientErrorException;
import picocli.CommandLine;
import ru.slie.luna.rest.client.LunaRestClient;
import ru.slie.luna.rest.client.generator.MainCommand;
import ru.slie.luna.rest.client.model.RemoteStatisticGroup;
import ru.slie.luna.rest.client.model.RemoteStatisticResult;

import java.io.PrintWriter;
import java.util.Locale;

@CommandLine.Command(name = "issue",
        description = "Управление задачами",
        subcommands = { IssueCommand.Count.class })
public class IssueCommand implements Runnable {
    private static final Faker fakerRu = new Faker(Locale.of("ru"));

    @CommandLine.ParentCommand
    private MainCommand mainCommand;

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }


    @CommandLine.Command(name = "count", description = "Вывести кол-во задач")
    public static class Count implements Runnable {
        @CommandLine.ParentCommand
        private IssueCommand parent;

        @CommandLine.Spec
        private CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = {"-g", "--group"}, description = "Сгруппировать по полю")
        private String group;

        @Override
        public void run() {
            MainCommand global = parent.mainCommand;
            LunaRestClient client = global.getLunaClient();
            PrintWriter out = spec.commandLine().getOut();
            PrintWriter err = spec.commandLine().getErr();

            if (group != null && !group.trim().isEmpty()) {
                try {
                    RemoteStatisticResult result = client.getStatisticReport(group);
                    String rowTemplate = "%-10s %-5s | %s%n";
                    for (RemoteStatisticGroup groupItem : result.getGroups()) {
                        out.printf(rowTemplate, groupItem.getId(), groupItem.getCount(), groupItem.getLabel());
                    }
                    out.printf("%-10s %-5s", "Всего:", result.getTotalCount());
                } catch (HttpClientErrorException e) {
                    err.println(e.getMessage());
                }
            } else {
                Long total = client.countIssues();
                out.printf("Задач в системе: %s", total);
            }
        }
    }
}
