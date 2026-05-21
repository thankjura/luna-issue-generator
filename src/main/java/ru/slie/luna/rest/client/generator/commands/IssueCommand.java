package ru.slie.luna.rest.client.generator.commands;

import net.datafaker.Faker;
import picocli.CommandLine;
import ru.slie.luna.rest.client.JiraRestClient;
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
        subcommands = { IssueCommand.Gen.class })
public class IssueCommand implements Runnable {
    private static final Faker fakerRu = new Faker(Locale.of("ru"));

    @CommandLine.ParentCommand
    private MainCommand mainCommand;

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
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
            JiraRestClient client = global.getJiraClient();
            PrintWriter out = spec.commandLine().getOut();
            PrintWriter err = spec.commandLine().getErr();
            ProgressBar progressBar = new ProgressBar(out, 4);
            progressBar.print(0, "Подготовка");

            int page = 1;
            int limit = Math.min(userLimit, 100);
            Map<String, ProjectGenParams> projectsMap = new HashMap<>();

            if (projects == null || projects.isEmpty()) {
                progressBar.print(0, "Получаю список проектов");
                List<RemoteProject> result = client.getProjects();
                for (RemoteProject project : result) {
                    projectsMap.put(project.getKey(), new ProjectGenParams(project.getKey()));
                }
            } else {
                for (String project: projects) {
                    projectsMap.put(project, new ProjectGenParams(project));
                }
            }
            progressBar.print(1, "Получаю информацию о приоритетах");
            Set<String> allPriorities = client.getPriorities().stream().map(RemotePriority::getId).collect(Collectors.toSet());

            progressBar.print(2, "Получаю информацию о проектах");

            for (Map.Entry<String, ProjectGenParams> entry: projectsMap.entrySet()) {
                progressBar.print(2, entry.getKey());
                RemoteProject projectWithSchemas = client.getProject(entry.getKey());
                if (projectWithSchemas.getIssueTypes() != null) {
                    entry.getValue().addIssueTypes(projectWithSchemas.getIssueTypes().stream().filter(i -> !i.getSubtask()).map(RemoteIssueType::getId).toList());
                }

                RemotePrioritySchema schema = client.getProjectPrioritySchema(entry.getKey());
                if (schema != null) {
                    entry.getValue().addPriorities(schema.getOptionIds());
                } else {
                    entry.getValue().addPriorities(allPriorities);
                }
            }

            projectsMap.values().removeIf(p -> p.getIssueTypes().isEmpty());

            if (projectsMap.isEmpty()) {
                err.println("Нет доступных проектов для генерации задач.");
            }

            progressBar.print(3, "Загружаю пользователей");

            for (Map.Entry<String, ProjectGenParams> entry: projectsMap.entrySet()) {
                progressBar.print(3, "Загружаю пользователей: " + entry.getKey());
                List<RemoteUser> result = client.findUsersForProject(entry.getKey(), 1, limit);
                for (RemoteUser user: result) {
                    entry.getValue().addUser(user.getName());
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
            long startTime = System.currentTimeMillis();

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

            long totalTimeMs = System.currentTimeMillis() - startTime;
            double totalTimeSec = totalTimeMs / 1000.0;

            double avgSpeed = 0.0;
            if (totalTimeSec > 0) {
                avgSpeed = created.get() / totalTimeSec;
            }

            out.printf("Создано задач: %d, за %.0f сек, средняя скорость: %.2f задач/сек%n",
                    created.get(),
                    totalTimeSec,
                    avgSpeed);
            out.flush();
        }
    }
}
