package ru.slie.luna.rest.client.generator.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data generator configuration. Defaults live in generator-default.yaml, see {@link ConfigLoader}.
 */
public class GeneratorConfig {
    public Server server = new Server();
    /** Seed for reproducible structure: control values, which issues get them. */
    public long seed = 42;
    /** Parallel HTTP requests while creating issues, more than the server DB pool only queues requests. */
    public int threads = 16;
    /** Stop issue generation after this many failed issues (after retries). */
    public int maxErrors = 100;
    public Users users = new Users();
    public Projects projects = new Projects();
    public Fields fields = new Fields();
    public Issues issues = new Issues();
    public Controls controls = new Controls();
    public Sdk sdk = new Sdk();
    public Bench bench = new Bench();

    public static class Server {
        public String url = "http://localhost:7080";
        public String user = "admin";
        public String password = "admin";
    }

    public static class Users {
        public int count = 50;
        public int directoryId = 1;
        public String emailDomain = "example.com";
        public int passwordLength = 12;
        /** Groups granting permissions in the default permission schema. */
        public List<String> groups = new ArrayList<>(List.of("users"));
    }

    public static class Projects {
        public int count = 5;
        public String keyPrefix = "LT";
        /**
         * Schema ids assigned to every project: permission, issueType, workflow, priority, issueTypeScreen,
         * fieldLayout, notification. A missing one takes the first schema of that kind found on the server.
         */
        public Map<String, Long> schemas = new LinkedHashMap<>();
    }

    public static class Fields {
        /** Number of custom fields of each type, keys are {@link FieldKind} config names. */
        public Map<String, Integer> perType = new LinkedHashMap<>();
        /** Options of select fields (random in range). */
        public int minOptions = 3;
        public int maxOptions = 30;
        /** Probability that a custom field gets a value in an issue. */
        public double fillRate = 0.3;
    }

    public static class Issues {
        public int count = 1000;
        /** Share of issues with a markdown description, others get plain paragraphs. */
        public double markdownDescriptionRate = 0.1;
        /** Issues are created in batches; progress is saved after each batch so the run can be resumed. */
        public int batchSize = 2000;
    }

    public static class Controls {
        /**
         * Control values per searchable field: unique values out of the random range, each given to
         * {@link #issuesPerValue} known issues. Used to check search results for every field type.
         */
        public int valuesPerField = 3;
        public int issuesPerValue = 5;
    }

    /** How the up/bench commands start Luna through luna-sdk when server.url does not respond. */
    public static class Sdk {
        /** luna-sdk launcher: a path or a command on PATH. */
        public String command = "luna-sdk";
        /** JVM options of Luna (Tomcat). */
        public String jvmArgs = "-Xmx4g";
        /** Local JMX port for monitoring the Luna JVM, 0 disables JMX. */
        public int jmxPort = 7091;
        /** Luna version (war from the local or remote Maven repository), empty: the luna-sdk default. */
        public String lunaVersion = "";
        /** External database (jdbc:postgresql://host:port/db?user=..&password=..), empty: PostgreSQL container. */
        public String dbUri = "";
        public boolean offline = false;
        /** Seconds to wait for Luna to start. */
        public int startTimeout = 600;
        /** Extra luna-sdk run arguments. */
        public List<String> extraArgs = new ArrayList<>();
    }

    public static class Bench {
        /** Server metrics sampling interval, milliseconds. */
        public int sampleMillis = 1000;
        public Search search = new Search();
        public Reindex reindex = new Reindex();
    }

    public static class Search {
        /** Runs of every query not counted in the timings (JIT, caches). */
        public int warmup = 2;
        /** Measured runs of every query. */
        public int repeat = 10;
        /** Page size of search requests. */
        public int pageSize = 50;
        /** Parallel clients of the throughput test; each level runs for durationSeconds. Empty skips the test. */
        public List<Integer> concurrency = new ArrayList<>(List.of(1, 8, 32));
        public int durationSeconds = 20;
        /** Additional named queries: [{name: ..., qs: ...}]. */
        public List<NamedQuery> queries = new ArrayList<>();
    }

    public static class NamedQuery {
        public String name;
        public String qs;
    }

    public static class Reindex {
        /** Full blocking reindex runs in one bench. */
        public int repeat = 1;
        /** Status polling interval, milliseconds. */
        public int pollMillis = 1000;
    }
}
