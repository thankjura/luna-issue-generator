package ru.slie.luna.rest.client.generator.commands;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import picocli.CommandLine;
import ru.slie.luna.rest.client.LunaRestClient;
import ru.slie.luna.rest.client.generator.MainCommand;
import ru.slie.luna.rest.client.model.RemoteProject;
import ru.slie.luna.rest.client.model.RemoteProjectWithSchemas;
import ru.slie.luna.rest.client.model.RemoteSearchResult;
import tools.jackson.databind.ObjectMapper;

import java.io.PrintWriter;

@CommandLine.Command(name = "project",
        description = "Управление проектами",
        subcommands = { ProjectCommand.List.class })
public class ProjectCommand implements Runnable {
    @CommandLine.ParentCommand
    private MainCommand mainCommand;

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    @CommandLine.Command(name = "show", description = "Информация о проекте")
    static class ProjectList implements Runnable {
        @CommandLine.ParentCommand
        private ProjectCommand parent; // Ссылка на команду project

        @CommandLine.Parameters(index = "0", description = "Код проекта")
        private String projectKey;

        @CommandLine.Spec
        private CommandLine.Model.CommandSpec spec;

        @Override
        public void run() {
            MainCommand global = parent.mainCommand;
            LunaRestClient client = global.getLunaClient();

            PrintWriter out = spec.commandLine().getOut();
            try {
                RemoteProjectWithSchemas project = client.getProject(projectKey);
                ObjectMapper mapper = new ObjectMapper();
                String prettyJson = mapper.writerWithDefaultPrettyPrinter()
                                            .writeValueAsString(project);

                out.println(prettyJson);
                out.flush();
            } catch (HttpClientErrorException.NotFound e) {
                out.println("Проект не найден");
            }
        }
    }

    @CommandLine.Command(name = "show", description = "Вывести список всех проектов")
    static class List implements Runnable {
        @CommandLine.ParentCommand
        private ProjectCommand parent; // Ссылка на команду project

        @CommandLine.Option(names = {"-l", "--limit"}, description = "Лимит проектов", defaultValue = "10")
        private int limit = 10;

        @CommandLine.Spec
        private CommandLine.Model.CommandSpec spec;

        @Override
        public void run() {
            MainCommand global = parent.mainCommand;
            RestClient client = global.getLunaClient().get();
            RemoteSearchResult<RemoteProject> data = client.get().uri("/rest/projects?limit={limit}", limit).retrieve().body(new ParameterizedTypeReference<>() {});
            PrintWriter out = spec.commandLine().getOut();
            String rowTemplate = "%-6s %-10s %s%n";
            if (data != null && data.getResults() != null) {
                out.printf(rowTemplate, "ID", "Ключ", "Название");
                for (RemoteProject project : data.getResults()) {
                    out.printf(rowTemplate, project.getId(), project.getKey(), project.getName());
                }
                out.flush();
            }
        }
    }
}
