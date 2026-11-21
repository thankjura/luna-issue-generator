package ru.slie.luna.rest.client.generator.data;

import net.datafaker.Faker;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import ru.slie.luna.rest.client.LunaRestClient;
import ru.slie.luna.rest.client.generator.ProgressBar;
import ru.slie.luna.rest.client.generator.TextUtils;
import ru.slie.luna.rest.client.generator.data.GeneratorState.ControlValue;
import ru.slie.luna.rest.client.generator.data.GeneratorState.FieldRecord;
import ru.slie.luna.rest.client.generator.data.GeneratorState.ProjectRecord;
import ru.slie.luna.rest.client.generator.data.GeneratorState.UserRecord;
import ru.slie.luna.rest.client.model.RemoteIssue;
import ru.slie.luna.rest.client.model.RemoteUser;
import ru.slie.luna.rest.client.model.request.RequestUser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Generates test data through the REST API: users, projects with schemas, custom fields of all types and issues.
 * <p>
 * Output directory:
 * <ul>
 *     <li>state.json - everything created so far, used to resume an interrupted run</li>
 *     <li>controls.jsonl - issues created with control values (one line per issue)</li>
 *     <li>errors.log - failed requests</li>
 *     <li>summary.json - what was created, timings, user passwords and expected search results</li>
 * </ul>
 */
public class DataGenerator {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int RECENT_KEYS = 10_000;
    private static final int LABELS = 500;
    private static final String SUMMARY_FIELD = "summary";

    /** Schema kind -> REST collection and project PATCH key. */
    private static final Map<String, String[]> SCHEMAS = new LinkedHashMap<>();

    static {
        SCHEMAS.put("permission", new String[]{"permission_schemas", "permissionSchemaId"});
        SCHEMAS.put("issueType", new String[]{"issuetype_schemas", "issueTypeSchemaId"});
        SCHEMAS.put("workflow", new String[]{"workflow_schemas", "workflowSchemaId"});
        SCHEMAS.put("priority", new String[]{"priority_schemas", "prioritySchemaId"});
        SCHEMAS.put("issueTypeScreen", new String[]{"issuetype_screen_schemas", "issueTypeScreenSchemaId"});
        SCHEMAS.put("fieldLayout", new String[]{"field_layout_schemas", "fieldLayoutSchemaId"});
        SCHEMAS.put("notification", new String[]{"notification_schemas", "notificationSchemaId"});
    }

    private final GeneratorConfig config;
    private final LunaRestClient client;
    private final PrintWriter out;
    private final JsonMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private final JsonMapper jsonLine = JsonMapper.builder().build();
    private final Path statePath;
    private final Path controlsLog;
    private final Path errorsLog;
    private final Path summaryPath;
    private final TextPool text;
    private final Faker faker;
    private final SecureRandom secureRandom = new SecureRandom();
    private final AtomicReferenceArray<String> recentKeys = new AtomicReferenceArray<>(RECENT_KEYS);
    private final AtomicLong recentKeysCount = new AtomicLong();
    private final OffsetDateTime startedAt = OffsetDateTime.now();
    private GeneratorState state;
    private volatile boolean stopRequested;

    public DataGenerator(GeneratorConfig config, Path outDir, PrintWriter out) {
        this.config = config;
        this.out = out;
        this.client = new LunaRestClient(config.server.url, config.server.user, config.server.password,
                                         Math.max(config.threads, 4) + 4);
        this.statePath = outDir.resolve("state.json");
        this.controlsLog = outDir.resolve("controls.jsonl");
        this.errorsLog = outDir.resolve("errors.log");
        this.summaryPath = outDir.resolve("summary.json");
        this.text = new TextPool(config.seed);
        this.faker = new Faker(Locale.of("ru"), new Random(config.seed));
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path getSummaryPath() {
        return summaryPath;
    }

    public void run() {
        loadState();
        CountDownLatch finished = new CountDownLatch(1);
        Thread hook = new Thread(() -> {
            // Ctrl+C / SIGTERM: stop sending new issues, let requests in flight finish and save the state
            stopRequested = true;
            try {
                finished.await(2, java.util.concurrent.TimeUnit.MINUTES);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "generator-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            timed("users", this::generateUsers);
            timed("projects", this::generateProjects);
            timed("fields", this::generateFields);
            if (!stopRequested) {
                timed("issues", this::generateIssues);
            }
        } finally {
            writeSummary();
            finished.countDown();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // shutdown in progress
            }
        }
    }

    private void timed(String step, Runnable action) {
        long start = System.nanoTime();
        try {
            action.run();
        } finally {
            double seconds = (System.nanoTime() - start) / 1e9;
            switch (step) {
                case "users" -> state.stepSeconds.users += seconds;
                case "projects" -> state.stepSeconds.projects += seconds;
                case "fields" -> state.stepSeconds.fields += seconds;
                case "issues" -> state.stepSeconds.issues += seconds;
                default -> {}
            }
            saveState();
        }
    }

    // ------------------------------------------------------------------ users

    private void generateUsers() {
        int need = config.users.count - state.users.size();
        // control users are only values of user fields: do not spend license seats without such fields
        boolean userFields = config.fields.perType.entrySet().stream()
                .anyMatch(e -> e.getValue() > 0 && FieldKind.byConfigName(e.getKey())
                        .map(k -> k == FieldKind.USER || k == FieldKind.MULTI_USER).orElse(false));
        int needControl = userFields ? config.controls.valuesPerField - state.controlUsers.size() : 0;
        if (need <= 0 && needControl <= 0) {
            out.printf("Пользователи: уже созданы (%d)%n", state.users.size());
            return;
        }
        checkLicenseUserLimit(Math.max(need, 0) + Math.max(needControl, 0));
        Set<String> logins = new HashSet<>();
        state.users.forEach(u -> logins.add(u.login));
        state.controlUsers.forEach(u -> logins.add(u.login));

        ProgressBar progress = new ProgressBar(out, Math.max(need, 0) + Math.max(needControl, 0));
        int done = 0;
        for (int i = 0; i < need; i++) {
            boolean male = faker.random().nextBoolean();
            String name = male ? faker.resolve("name.male_first_name") : faker.resolve("name.female_first_name");
            String lastName = male ? faker.resolve("name.male_last_name") : faker.resolve("name.female_last_name");
            UserRecord user = createUser(TextUtils.translit(lastName + "." + name), name, lastName, logins);
            state.users.add(user);
            saveState();
            progress.print(++done, user.login);
        }
        int controlTarget = state.controlUsers.size() + Math.max(needControl, 0);
        for (int k = state.controlUsers.size(); k < controlTarget; k++) {
            UserRecord user = createUser("ctl.user" + k, "Control", "User " + k, logins);
            state.controlUsers.add(user);
            saveState();
            progress.print(++done, user.login);
        }
        out.println();
    }

    /**
     * Luna refuses to create issues once active users exceed the license limit (the SDK developer license allows 10),
     * fail before creating them instead of getting thousands of failed issues.
     */
    private void checkLicenseUserLimit(int newUsers) {
        JsonNode license;
        try {
            license = client.getJson("/rest/licenses/self");
        } catch (HttpStatusCodeException e) {
            return;
        }
        long active = license.path("activeUserCount").asLong();
        for (JsonNode l : license.path("licenses")) {
            if (l.path("current").asBoolean(false) && "LUNA".equals(l.path("app").asString(""))) {
                long limit = l.path("userLimit").asLong(Long.MAX_VALUE);
                if (active + newUsers > limit) {
                    throw new IllegalStateException(String.format(
                            "Лицензия (%s) разрешает %d активных пользователей: сейчас %d, будет создано ещё %d "
                            + "(users.count + controls.valuesPerField). Уменьшите users.count или установите лицензию с большим лимитом.",
                            l.path("type").asString(), limit, active, newUsers));
                }
            }
        }
    }

    private UserRecord createUser(String baseLogin, String name, String lastName, Set<String> logins) {
        String login = baseLogin.replaceAll("[^a-z0-9._-]", "");
        for (int attempt = 0; ; attempt++) {
            String candidate = attempt == 0 && !logins.contains(login) ? login : login + (attempt + 1);
            if (logins.contains(candidate)) {
                continue;
            }
            String password = randomPassword();
            String email = candidate + "@" + config.users.emailDomain.replaceFirst("^@", "");
            try {
                RemoteUser user = client.createUser(new RequestUser(config.users.directoryId, candidate, email, name, lastName, password));
                if (config.users.groups != null && !config.users.groups.isEmpty()) {
                    client.addUserToGroups(user.getLogin(), config.users.groups);
                }
                logins.add(candidate);
                return new UserRecord(user.getLogin(), email, name + " " + lastName, password);
            } catch (HttpClientErrorException.BadRequest | HttpClientErrorException.Conflict e) {
                logins.add(candidate);
                if (attempt > 20) {
                    throw e;
                }
            }
        }
    }

    private String randomPassword() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < config.users.passwordLength; i++) {
            sb.append(PASSWORD_CHARS.charAt(secureRandom.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ projects

    private void generateProjects() {
        if (state.projects.size() >= config.projects.count) {
            out.printf("Проекты: уже созданы (%d)%n", state.projects.size());
            return;
        }
        Map<String, String> schemas = resolveSchemas();
        ProgressBar progress = new ProgressBar(out, config.projects.count);
        for (int i = state.projects.size(); i < config.projects.count; i++) {
            String key = (config.projects.keyPrefix + (i + 1)).toUpperCase(Locale.ROOT);
            String name = capitalize(text.words(2)) + " " + (i + 1);
            JsonNode project;
            try {
                project = client.postJson("/rest/projects/create", Map.of("key", key, "name", name));
            } catch (HttpClientErrorException.BadRequest e) {
                // created by an interrupted run: adopt it
                project = client.getJson("/rest/projects/{key}/schemas", key);
            }
            client.patchJson("/rest/projects/{key}", schemas, key);

            JsonNode full = client.getJson("/rest/projects/{key}/schemas", key);
            ProjectRecord record = new ProjectRecord();
            record.id = project.path("id").asLong();
            record.key = key;
            record.name = full.path("name").asString(name);
            for (JsonNode type : full.path("issueTypeSchema").path("issueTypes")) {
                if (!type.path("subtask").asBoolean(false)) {
                    record.issueTypeIds.add(type.path("id").asLong());
                }
            }
            for (JsonNode priority : full.path("prioritySchema").path("priorities")) {
                record.priorityIds.add(priority.path("id").asLong());
            }
            if (record.issueTypeIds.isEmpty()) {
                throw new IllegalStateException("Project " + key + " has no issue types, check the issue type schema");
            }
            state.projects.add(record);
            saveState();
            progress.print(i + 1, key);
        }
        out.println();
    }

    private Map<String, String> resolveSchemas() {
        Map<String, String> patch = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> schema : SCHEMAS.entrySet()) {
            Long id = config.projects.schemas.get(schema.getKey());
            if (id == null) {
                JsonNode list = client.getJson("/rest/" + schema.getValue()[0]);
                JsonNode items = list.isArray() ? list : list.path("results");
                if (items.isArray() && !items.isEmpty()) {
                    id = items.get(0).path("id").asLong();
                }
            }
            if (id != null) {
                patch.put(schema.getValue()[1], String.valueOf(id));
            }
        }
        return patch;
    }

    // ------------------------------------------------------------------ fields

    private void generateFields() {
        Map<FieldKind, Integer> plan = new LinkedHashMap<>();
        config.fields.perType.forEach((name, count) -> plan.put(
                FieldKind.byConfigName(name).orElseThrow(() -> new IllegalArgumentException("Unknown field type: " + name
                        + ", expected one of " + Arrays.stream(FieldKind.values()).map(FieldKind::configName).toList())),
                count));
        int total = plan.values().stream().mapToInt(Integer::intValue).sum();
        long existing = state.fields.size();
        if (existing >= total) {
            out.printf("Поля: уже созданы (%d)%n", existing);
            return;
        }

        ProgressBar progress = new ProgressBar(out, total);
        Random random = new Random(config.seed);
        int done = 0;
        for (Map.Entry<FieldKind, Integer> entry : plan.entrySet()) {
            FieldKind kind = entry.getKey();
            long have = state.fields.stream().filter(f -> f.kind == kind).count();
            done += (int) Math.min(have, entry.getValue());
            for (long idx = have; idx < entry.getValue(); idx++) {
                FieldRecord field = createField(kind, (int) idx + 1, random);
                state.fields.add(field);
                saveState();
                progress.print(++done, field.id + " " + field.name);
            }
        }
        out.println();
    }

    private FieldRecord createField(FieldKind kind, int number, Random random) {
        String baseName = String.format("%s %03d %s", kind.label(), number, capitalize(text.word(random.nextInt(10_000))));
        String name = baseName;
        JsonNode created = null;
        for (int attempt = 2; created == null; attempt++) {
            try {
                created = client.postJson("/rest/fields/custom/create", Map.of("name", name, "fieldType", kind.typeKey()));
            } catch (HttpClientErrorException.BadRequest e) {
                // field names are unique on the server: another run on this instance used the same name
                if (attempt > 50) {
                    throw e;
                }
                name = baseName + " " + attempt;
            }
        }
        FieldRecord field = new FieldRecord();
        field.id = created.path("id").asString();
        field.name = name;
        field.kind = kind;

        JsonNode searchers = client.getJson("/rest/fields/custom/{id}/searchers", field.id);
        if (searchers.isArray() && !searchers.isEmpty()) {
            field.searcher = searchers.get(0).path("key").asString();
            client.putJson("/rest/fields/custom/{id}", Map.of("name", name, "searcher", field.searcher), field.id);
        }

        if (kind.hasOptions()) {
            JsonNode full = client.getJson("/rest/fields/custom/{id}", field.id);
            long contextId = full.path("contexts").get(0).path("id").asLong();
            int options = config.fields.minOptions + random.nextInt(Math.max(1, config.fields.maxOptions - config.fields.minOptions + 1));
            for (int i = 0; i < options; i++) {
                String optionName = capitalize(text.word(random.nextInt(10_000))) + " " + (i + 1);
                field.optionIds.add(createOption(field.id, contextId, optionName));
            }
            if (field.searcher != null) {
                for (int k = 0; k < config.controls.valuesPerField; k++) {
                    String optionName = "Control " + k;
                    long optionId = createOption(field.id, contextId, optionName);
                    field.controls.add(control(k, optionId, String.format("%s = \"%s\"", field.id, optionName)));
                }
            }
        }
        return field;
    }

    private long createOption(String fieldId, long contextId, String name) {
        return client.postJson("/rest/fields/custom/{id}/contexts/{contextId}/options", Map.of("name", name), fieldId, contextId)
                     .path("id").asLong();
    }

    // ------------------------------------------------------------------ controls

    private static ControlValue control(int index, Object value, String query) {
        ControlValue control = new ControlValue();
        control.index = index;
        control.value = value;
        control.query = query;
        return control;
    }

    /** Control values of fields without options; options get them on creation. */
    private void buildControls() {
        int values = config.controls.valuesPerField;
        for (FieldRecord field : state.fields) {
            if (!field.kind.controlled() || field.searcher == null || !field.controls.isEmpty() || field.kind.hasOptions()) {
                continue;
            }
            for (int k = 0; k < values; k++) {
                String id = field.id;
                ControlValue control = switch (field.kind) {
                    case STRING, TEXT -> {
                        String token = "ctl" + id + "v" + k;
                        yield control(k, token, String.format("%s ~ \"%s\"", id, token));
                    }
                    case INTEGER -> control(k, 2_000_000_000L + k, String.format("%s = %d", id, 2_000_000_000L + k));
                    case DOUBLE -> control(k, 5_000_000.25 + k, String.format(Locale.ROOT, "%s = %.2f", id, 5_000_000.25 + k));
                    case DATE -> {
                        String day = LocalDate.of(1990, 1, 1).plusDays(k).format(DATE);
                        yield control(k, day, String.format("%s = %s", id, day));
                    }
                    case DATE_TIME -> {
                        String moment = LocalDateTime.of(1990, 1, 1, 12, 0).plusDays(k).format(DATE_TIME);
                        yield control(k, moment, String.format("%s = \"%s\"", id, moment));
                    }
                    case USER -> {
                        String login = state.controlUsers.get(k).login;
                        yield control(k, login, String.format("%s = %s", id, login));
                    }
                    case MULTI_USER -> {
                        String login = state.controlUsers.get(k).login;
                        yield control(k, List.of(login), String.format("%s = %s", id, login));
                    }
                    case LABEL -> {
                        String label = "ctl-" + id + "-" + k;
                        yield control(k, List.of(label), String.format("%s = \"%s\"", id, label));
                    }
                    case ISSUE_REF -> control(k, state.anchorIssues.get(k), String.format("%s = %s", id, state.anchorIssues.get(k)));
                    case MULTI_ISSUE_REF -> control(k, List.of(state.anchorIssues.get(k)), String.format("%s = %s", id, state.anchorIssues.get(k)));
                    default -> null;
                };
                if (control != null) {
                    field.controls.add(control);
                }
            }
        }
        if (state.systemControls.isEmpty()) {
            for (int k = 0; k < values; k++) {
                String token = "ctl" + state.runTag + "summaryv" + k;
                state.systemControls.add(control(k, token, String.format("summary ~ \"%s\"", token)));
            }
        }
    }

    /** Gives each control value to issuesPerValue distinct issues, reproducibly from the seed. */
    private void assignControlSeqs() {
        long count = config.issues.count;
        List<Map.Entry<String, List<ControlValue>>> groups = new ArrayList<>();
        for (FieldRecord field : state.fields) {
            groups.add(Map.entry(field.id, field.controls));
        }
        groups.add(Map.entry(SUMMARY_FIELD, state.systemControls));
        for (Map.Entry<String, List<ControlValue>> group : groups) {
            if (group.getValue().isEmpty() || !group.getValue().getFirst().seqs.isEmpty()) {
                continue;
            }
            Random random = new Random(config.seed ^ group.getKey().hashCode());
            long wanted = Math.min((long) group.getValue().size() * config.controls.issuesPerValue, count);
            LinkedHashSet<Long> seqs = new LinkedHashSet<>();
            while (seqs.size() < wanted) {
                seqs.add(random.nextLong(count));
            }
            Iterator<Long> it = seqs.iterator();
            for (ControlValue control : group.getValue()) {
                for (int i = 0; i < config.controls.issuesPerValue && it.hasNext(); i++) {
                    control.seqs.add(it.next());
                }
            }
        }
    }

    private record ControlRef(String fieldId, ControlValue control) {
    }

    private Map<Long, List<ControlRef>> controlsBySeq() {
        Map<Long, List<ControlRef>> bySeq = new HashMap<>();
        for (FieldRecord field : state.fields) {
            for (ControlValue control : field.controls) {
                control.seqs.forEach(seq -> bySeq.computeIfAbsent(seq, s -> new ArrayList<>()).add(new ControlRef(field.id, control)));
            }
        }
        for (ControlValue control : state.systemControls) {
            control.seqs.forEach(seq -> bySeq.computeIfAbsent(seq, s -> new ArrayList<>()).add(new ControlRef(SUMMARY_FIELD, control)));
        }
        return bySeq;
    }

    // ------------------------------------------------------------------ issues

    private void generateIssues() {
        createAnchorIssues();
        buildControls();
        assignControlSeqs();
        saveState();

        long count = config.issues.count;
        if (state.issuesDone >= count) {
            out.printf("Задачи: уже созданы (%d)%n", state.issuesDone);
            return;
        }

        Map<Long, List<ControlRef>> controls = controlsBySeq();
        Set<Long> controlSeqsDone = readControlsLog().keySet();
        // issues of an unfinished batch completed before a graceful stop
        Set<Long> doneAhead = new HashSet<>(state.doneAhead);
        List<UserRecord> users = state.users.isEmpty() ? List.of(new UserRecord(config.server.user, null, null, null)) : state.users;

        ProgressBar progress = new ProgressBar(out, (int) Math.min(count, Integer.MAX_VALUE));
        AtomicLong done = new AtomicLong(state.issuesDone + doneAhead.size());
        AtomicLong failedThisRun = new AtomicLong();
        Semaphore permits = new Semaphore(config.threads);
        long runStart = System.nanoTime();
        long createdBefore = state.issuesCreated;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            while (state.issuesDone < count && !stopRequested && failedThisRun.get() <= config.maxErrors) {
                long from = state.issuesDone;
                long to = Math.min(count, from + config.issues.batchSize);
                Set<Long> completed = ConcurrentHashMap.newKeySet();
                doneAhead.stream().filter(seq -> seq >= from && seq < to).forEach(completed::add);
                AtomicLong created = new AtomicLong();
                AtomicLong failed = new AtomicLong();
                Map<String, AtomicLong> filled = new ConcurrentHashMap<>();
                for (long seq = from; seq < to; seq++) {
                    if (stopRequested || failedThisRun.get() > config.maxErrors) {
                        break;
                    }
                    List<ControlRef> refs = controls.getOrDefault(seq, List.of());
                    if (doneAhead.contains(seq) || (!refs.isEmpty() && controlSeqsDone.contains(seq))) {
                        completed.add(seq);
                        continue;
                    }
                    permits.acquireUninterruptibly();
                    final long current = seq;
                    executor.submit(() -> {
                        try {
                            Optional<String> key = createIssue(current, refs, users, filled);
                            if (key.isPresent()) {
                                created.incrementAndGet();
                                if (!refs.isEmpty()) {
                                    logControls(current, key.get(), refs);
                                }
                                progress.print(done.incrementAndGet(), key.get());
                            } else {
                                failed.incrementAndGet();
                                failedThisRun.incrementAndGet();
                                done.incrementAndGet();
                            }
                            completed.add(current);
                        } finally {
                            permits.release();
                        }
                    });
                }
                // wait for the requests in flight
                permits.acquireUninterruptibly(config.threads);
                permits.release(config.threads);

                state.issuesCreated += created.get();
                state.issuesFailed += failed.get();
                filled.forEach((fieldId, n) -> state.fieldFilled.merge(fieldId, n.get(), Long::sum));
                if (completed.size() == to - from) {
                    state.issuesDone = to;
                    state.doneAhead.clear();
                    doneAhead.clear();
                } else {
                    // stopped inside the batch: remember finished issues so a resumed run does not create them again
                    state.doneAhead = new ArrayList<>(completed);
                }
                saveState();
            }
        }
        if (failedThisRun.get() > config.maxErrors) {
            out.printf("%nСлишком много ошибок (%d), генерация остановлена, подробности в %s%n", failedThisRun.get(), errorsLog);
        } else if (stopRequested) {
            out.printf("%nГенерация прервана на %d из %d, повторный запуск продолжит её%n", done.get(), count);
        }
        double seconds = (System.nanoTime() - runStart) / 1e9;
        state.issueSeconds += seconds;
        out.printf("%nСоздано задач: %d за %.0f с (%.1f задач/с)%n", state.issuesCreated - createdBefore, seconds,
                   (state.issuesCreated - createdBefore) / Math.max(seconds, 0.001));
    }

    private void createAnchorIssues() {
        for (int k = state.anchorIssues.size(); k < config.controls.valuesPerField; k++) {
            ProjectRecord project = state.projects.getFirst();
            Map<String, Object> request = new HashMap<>();
            request.put("project", project.key);
            request.put("issueType", project.issueTypeIds.getFirst());
            request.put("summary", "Control anchor " + k);
            RemoteIssue issue = client.createIssue(request);
            state.anchorIssues.add(issue.getKey());
            saveState();
        }
    }

    /** @param filled per custom field: created issues having a value of it */
    private Optional<String> createIssue(long seq, List<ControlRef> refs, List<UserRecord> users, Map<String, AtomicLong> filled) {
        Map<String, Object> request = buildIssue(refs, users);
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                RemoteIssue issue = client.createIssue(request);
                rememberKey(issue.getKey());
                for (FieldRecord field : state.fields) {
                    if (request.containsKey(field.id)) {
                        filled.computeIfAbsent(field.id, id -> new AtomicLong()).incrementAndGet();
                    }
                }
                return Optional.of(issue.getKey());
            } catch (HttpClientErrorException e) {
                // validation errors will not go away on retry
                logError(seq, e.getStatusCode() + " " + e.getResponseBodyAsString(), request);
                return Optional.empty();
            } catch (Exception e) {
                last = e;
                try {
                    Thread.sleep(500L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        String message = last instanceof HttpStatusCodeException http
                ? http.getStatusCode() + " " + http.getResponseBodyAsString()
                : String.valueOf(last);
        logError(seq, message, request);
        return Optional.empty();
    }

    private Map<String, Object> buildIssue(List<ControlRef> refs, List<UserRecord> users) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        ProjectRecord project = state.projects.get(random.nextInt(state.projects.size()));
        Map<String, ControlValue> controls = new HashMap<>();
        refs.forEach(ref -> controls.put(ref.fieldId(), ref.control()));

        Map<String, Object> request = new HashMap<>();
        request.put("project", project.key);
        request.put("issueType", project.issueTypeIds.get(random.nextInt(project.issueTypeIds.size())));
        String summary = text.sentence();
        ControlValue summaryControl = controls.get(SUMMARY_FIELD);
        if (summaryControl != null) {
            summary = summary + " " + summaryControl.value;
        }
        request.put("summary", summary);
        request.put("description", random.nextDouble() < config.issues.markdownDescriptionRate
                ? text.markdown() : text.paragraphs(random.nextInt(1, 4)));
        if (!project.priorityIds.isEmpty()) {
            request.put("priority", project.priorityIds.get(random.nextInt(project.priorityIds.size())));
        }
        request.put("author", users.get(random.nextInt(users.size())).login);
        if (random.nextInt(100) < 80) {
            request.put("assignee", users.get(random.nextInt(users.size())).login);
        }

        for (FieldRecord field : state.fields) {
            ControlValue control = controls.get(field.id);
            if (control != null) {
                request.put(field.id, field.kind == FieldKind.TEXT ? text.sentence() + " " + control.value : control.value);
            } else if (random.nextDouble() < config.fields.fillRate) {
                Object value = randomValue(field, users, random);
                if (value != null) {
                    request.put(field.id, value);
                }
            }
        }
        return request;
    }

    private Object randomValue(FieldRecord field, List<UserRecord> users, ThreadLocalRandom random) {
        return switch (field.kind) {
            case STRING -> text.words(random.nextInt(1, 5));
            case TEXT -> text.paragraphs(1);
            case INTEGER -> random.nextLong(1_000_000);
            case DOUBLE -> Math.round(random.nextDouble(0, 10_000) * 100) / 100.0;
            case DATE -> LocalDate.of(2020, 1, 1).plusDays(random.nextInt(365 * 7)).format(DATE);
            case DATE_TIME -> LocalDateTime.of(2020, 1, 1, 0, 0).plusMinutes(random.nextLong(60L * 24 * 365 * 7)).format(DATE_TIME);
            case BOOLEAN -> random.nextBoolean();
            case SINGLE_SELECT -> field.optionIds.get(random.nextInt(field.optionIds.size()));
            case MULTI_SELECT -> pick(field.optionIds, random.nextInt(1, 4), random);
            case USER -> users.get(random.nextInt(users.size())).login;
            case MULTI_USER -> pick(users, random.nextInt(1, 4), random).stream().map(u -> u.login).toList();
            case LABEL -> {
                List<String> labels = new ArrayList<>();
                for (int i = random.nextInt(1, 4); i > 0; i--) {
                    labels.add("lbl" + random.nextInt(LABELS));
                }
                yield labels.stream().distinct().toList();
            }
            case ISSUE_REF -> recentKey(random);
            case MULTI_ISSUE_REF -> {
                String key = recentKey(random);
                yield key == null ? null : List.of(key);
            }
            case PRIORITY -> {
                ProjectRecord project = state.projects.getFirst();
                yield project.priorityIds.isEmpty() ? null : project.priorityIds.get(random.nextInt(project.priorityIds.size()));
            }
            case COLOR -> String.format("#%06x", random.nextInt(0x1000000));
            case DURATION -> random.nextInt(1, 40) + "h";
        };
    }

    private static <T> List<T> pick(List<T> list, int count, ThreadLocalRandom random) {
        Set<T> picked = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            picked.add(list.get(random.nextInt(list.size())));
        }
        return new ArrayList<>(picked);
    }

    private void rememberKey(String key) {
        long index = recentKeysCount.getAndIncrement();
        recentKeys.set((int) (index % RECENT_KEYS), key);
    }

    private String recentKey(ThreadLocalRandom random) {
        long size = Math.min(recentKeysCount.get(), RECENT_KEYS);
        return size == 0 ? null : recentKeys.get(random.nextInt((int) size));
    }

    // ------------------------------------------------------------------ files

    private synchronized void logControls(long seq, String key, List<ControlRef> refs) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("seq", seq);
        line.put("key", key);
        line.put("controls", refs.stream().map(r -> r.fieldId() + "#" + r.control().index).toList());
        appendLine(controlsLog, jsonLine.writeValueAsString(line));
    }

    private synchronized void logError(long seq, String message, Map<String, Object> request) {
        appendLine(errorsLog, String.format("%s seq=%d %s request=%s", OffsetDateTime.now(), seq, message,
                                            jsonLine.writeValueAsString(request)));
    }

    private static void appendLine(Path file, String line) {
        try {
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** seq -> control refs ("fieldId#index") of issues created with control values. */
    private Map<Long, List<String>> readControlsLog() {
        Map<Long, List<String>> result = new HashMap<>();
        if (!Files.exists(controlsLog)) {
            return result;
        }
        try {
            for (String line : Files.readAllLines(controlsLog)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = jsonLine.readTree(line);
                List<String> refs = new ArrayList<>();
                node.path("controls").forEach(n -> refs.add(n.asString()));
                result.put(node.path("seq").asLong(), refs);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    private void loadState() {
        if (Files.exists(statePath)) {
            state = json.readValue(statePath.toFile(), GeneratorState.class);
            if (!Objects.equals(state.server, config.server.url)) {
                throw new IllegalStateException(String.format("%s was created for %s, not %s: use another output directory",
                                                              statePath, state.server, config.server.url));
            }
            out.printf("Продолжаю генерацию из %s%n", statePath);
        } else {
            state = new GeneratorState();
            state.fieldFilledComplete = true;
            state.server = config.server.url;
            state.seed = config.seed;
            state.runTag = Long.toString(secureRandom.nextLong(36L * 36 * 36 * 36 * 36, 36L * 36 * 36 * 36 * 36 * 36), 36);
        }
    }

    private synchronized void saveState() {
        try {
            Path tmp = statePath.resolveSibling(statePath.getFileName() + ".tmp");
            json.writeValue(tmp.toFile(), state);
            Files.move(tmp, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeSummary() {
        Map<String, Long> controlCounts = new HashMap<>();
        readControlsLog().values().forEach(refs -> refs.forEach(ref -> controlCounts.merge(ref, 1L, Long::sum)));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("generatedAt", OffsetDateTime.now().toString());
        summary.put("runStartedAt", startedAt.toString());
        summary.put("server", config.server.url);
        summary.put("seed", config.seed);
        summary.put("runTag", state.runTag);

        Map<String, Object> users = new LinkedHashMap<>();
        users.put("count", state.users.size());
        users.put("groups", config.users.groups);
        users.put("accounts", state.users);
        users.put("controlAccounts", state.controlUsers);
        summary.put("users", users);

        summary.put("projects", state.projects.stream().map(p -> Map.of("key", p.key, "name", p.name, "id", p.id)).toList());

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("count", state.fields.size());
        Map<String, Long> byType = new LinkedHashMap<>();
        state.fields.forEach(f -> byType.merge(f.kind.configName(), 1L, Long::sum));
        fields.put("byType", byType);
        fields.put("searchable", state.fields.stream().filter(f -> f.searcher != null).count());
        fields.put("list", state.fields.stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.id);
            m.put("name", f.name);
            m.put("type", f.kind.configName());
            m.put("searcher", f.searcher);
            if (f.kind.hasOptions()) {
                m.put("options", f.optionIds.size());
            }
            if (state.fieldFilledComplete) {
                m.put("filled", state.fieldFilled.getOrDefault(f.id, 0L));
            }
            return m;
        }).toList());
        summary.put("fields", fields);

        Map<String, Object> issues = new LinkedHashMap<>();
        issues.put("requested", config.issues.count);
        issues.put("processed", state.issuesDone);
        issues.put("created", state.issuesCreated);
        issues.put("failed", state.issuesFailed);
        issues.put("anchorIssues", state.anchorIssues);
        issues.put("seconds", Math.round(state.issueSeconds));
        issues.put("perSecond", state.issueSeconds > 0 ? Math.round(state.issuesCreated / state.issueSeconds * 10) / 10.0 : null);
        try {
            issues.put("onServer", client.countIssues());
        } catch (Exception e) {
            issues.put("onServer", null);
        }
        summary.put("issues", issues);
        summary.put("stepSeconds", state.stepSeconds);

        List<Map<String, Object>> controls = new ArrayList<>();
        for (FieldRecord field : state.fields) {
            for (ControlValue c : field.controls) {
                controls.add(controlSummary(field.id, field.name, field.kind.configName(), c, controlCounts));
            }
        }
        for (ControlValue c : state.systemControls) {
            controls.add(controlSummary(SUMMARY_FIELD, "Summary", "summary", c, controlCounts));
        }
        summary.put("controls", controls);
        summary.put("config", config);

        try {
            json.writeValue(summaryPath.toFile(), summary);
        } catch (Exception e) {
            out.println("Не удалось записать сводку: " + e.getMessage());
        }
    }

    private static Map<String, Object> controlSummary(String fieldId, String fieldName, String type, ControlValue c,
                                                      Map<String, Long> counts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", fieldId);
        m.put("fieldName", fieldName);
        m.put("type", type);
        m.put("value", c.value);
        m.put("query", c.query);
        m.put("expected", counts.getOrDefault(fieldId + "#" + c.index, 0L));
        return m;
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
