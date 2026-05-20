package ru.slie.luna.rest.client.generator.commands;

import net.datafaker.Faker;
import org.springframework.web.client.HttpClientErrorException;
import picocli.CommandLine;
import ru.slie.luna.rest.client.LunaRestClient;
import ru.slie.luna.rest.client.generator.MainCommand;
import ru.slie.luna.rest.client.generator.ProgressBar;
import ru.slie.luna.rest.client.generator.commands.utils.IssueGenerator;
import ru.slie.luna.rest.client.generator.commands.utils.ProjectGenParams;
import ru.slie.luna.rest.client.model.*;

import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@CommandLine.Command(name = "issue",
        description = "Управление задачами",
        subcommands = { IssueCommand.Count.class, IssueCommand.Gen.class })
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

    @CommandLine.Command(name = "gen", description = "Сгенерировать")
    static class Gen implements Runnable {
        @CommandLine.ParentCommand
        private IssueCommand parent;

        @CommandLine.Spec
        private CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = {"-p", "--project"}, split = ",", description = "Проекты, по умолчанию все доступные")
        private List<String> projects;

        @CommandLine.Option(names = {"-f", "--field"}, description = "Заполнить поле доп поле")
        private List<String> fields;

        @CommandLine.Option(names = {"-c", "--count"}, description = "Кол-во задач", defaultValue = "1")
        private int count = 1;

        @CommandLine.Option(names = {"-u", "--users"}, description = "Максимальное кол-во пользователей", defaultValue = "10")
        private int userLimit = 100;

        @CommandLine.Option(names = {"-t", "--threads"}, description = "Количество потоков", defaultValue = "1")
        private int threads = 1;

        @Override
        public void run() {
            MainCommand global = parent.mainCommand;
            LunaRestClient client = global.getLunaClient();
            PrintWriter out = spec.commandLine().getOut();
            PrintWriter err = spec.commandLine().getErr();
            ProgressBar progressBar = new ProgressBar(out, 4);
            progressBar.print(0, "Подготовка");

            int page = 1;
            int limit = Math.min(userLimit, 100);
            Map<String, ProjectGenParams> projectsMap = new HashMap<>();

            if (projects == null || projects.isEmpty()) {
                progressBar.print(0, "Получаю список проектов");
                while (true) {
                    RemoteSearchResult<RemoteProject> result = client.findProjects(page++, limit);
                    for (RemoteProject project : result.getResults()) {
                        projectsMap.put(project.getKey(), new ProjectGenParams(project.getKey()));
                    }

                    if (result.getResults().size() < limit) {
                        break;
                    }
                }
            } else {
                for (String project: projects) {
                    projectsMap.put(project, new ProjectGenParams(project));
                }
            }
            progressBar.print(1, "Получаю информацию о приоритетах");
            Set<Long> allPriorities = client.getPriorities().stream().map(RemotePriority::getId).collect(Collectors.toSet());

            progressBar.print(2, "Получаю информацию о проектах");

            for (Map.Entry<String, ProjectGenParams> entry: projectsMap.entrySet()) {
                progressBar.print(2, entry.getKey());
                RemoteProjectWithSchemas projectWithSchemas = client.getProject(entry.getKey());
                if (projectWithSchemas.getIssueTypeSchema() != null) {
                    entry.getValue().addIssueTypes(projectWithSchemas.getIssueTypeSchema().getIssueTypeIds());
                }

                if (projectWithSchemas.getPrioritySchema() != null) {
                    entry.getValue().addPriorities(projectWithSchemas.getPrioritySchema().getPriorities().stream().map(RemotePriority::getId).collect(Collectors.toList()));
                } else {
                    entry.getValue().addPriorities(allPriorities);
                }
            }

            progressBar.print(3, "Загружаю пользователей");

            projectsMap.values().removeIf(p -> p.getIssueTypes().isEmpty());

            if (projectsMap.isEmpty()) {
                err.println("Нет доступных проектов для генерации задач.");
            }

            for (Map.Entry<String, ProjectGenParams> entry: projectsMap.entrySet()) {
                progressBar.print(3, "Загружаю пользователей: " + entry.getKey());
                RemoteSearchResult<RemoteUser> result = client.findUsersForProject(entry.getKey(), 1, limit);
                for (RemoteUser user: result.getResults()) {
                    entry.getValue().addUser(user.getLogin());
                }
            }
            progressBar.print(4, "Готово");

            projectsMap.values().removeIf(p -> p.getIssueTypes().isEmpty());
            if (projectsMap.isEmpty()) {
                out.println();
                err.println("Нет доступных проектов для генерации задач.");
            }


            final ProgressBar progress = new ProgressBar(out, count);
            AtomicLong created = new AtomicLong(0);
            AtomicLong errorCount = new AtomicLong(0);
            Semaphore semaphore = new Semaphore(threads);

            IssueGenerator generator = new IssueGenerator(new ArrayList<>(projectsMap.values()));

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (long i = 0; i < count; i++) {
                    if (errorCount.get() > 10) {
                        break;
                    }
                    semaphore.acquire();
                    executor.submit(() -> {
                        if (errorCount.get() >= 10) {
                            semaphore.release();
                            return;
                        }

                        try {
                            Map<String, Object> issue = generator.genIssue();
                            RemoteIssue remoteIssue = client.createIssue(issue);
                            progress.print(created.incrementAndGet(), remoteIssue.getKey());
                        } catch (Exception e) {
                            synchronized (progress) {
                                err.printf("\n[Ошибка] %s%n", e.getMessage());
                                err.flush();
                            }

                            long currentErrors = errorCount.incrementAndGet();
                            if (currentErrors >= 10) {
                                synchronized (progress) {
                                    err.println("\n[Ошибка] Много ошибок. Остановка генерации...");
                                    err.flush();
                                }
                                executor.shutdownNow();
                            }

                        } finally {
                            semaphore.release();
                        }
                    });
                }
            } catch (InterruptedException e) {
                err.println("Процесс генерации был прерван");
                Thread.currentThread().interrupt();
            }

            out.println();

            if (errorCount.get() >= 10) {
                out.println("Процесс остановлен из-за большого количества ошибок.");
            } else {
                out.println("Генерация успешно завершена!");
            }
        }
    }
}
