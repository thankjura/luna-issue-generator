package ru.slie.luna.rest.client.generator.bench;

import com.sun.management.OperatingSystemMXBean;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Samples server load while a bench runs and writes samples.csv:
 * Luna JVM through JMX (CPU, heap, GC pauses, threads), the Tomcat process RSS, the host (/proc: CPU, load,
 * memory, disk IO) and the PostgreSQL container of the workdir (docker stats).
 * Sources that are not available (no JMX, not Linux, external database) are skipped, columns stay empty.
 */
public class ServerMonitor implements AutoCloseable {
    public static final List<String> COLUMNS = List.of(
            "time", "elapsed_s",
            "jvm_cpu_cores", "host_cpu_pct", "load1",
            "heap_used_mb", "heap_committed_mb", "heap_max_mb", "nonheap_mb",
            "gc_pauses", "gc_pause_ms", "gc_pause_ms_total", "threads",
            "luna_rss_mb", "host_mem_avail_mb", "disk_read_mb_s", "disk_write_mb_s",
            "db_cpu_pct", "db_mem_mb", "db_block_read_mb", "db_block_write_mb");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Pattern SIZE = Pattern.compile("([0-9.]+)\\s*([kKMGT]?i?B)");

    private final Workdir workdir;
    private final Workdir.ServerInfo server;
    private final int intervalMillis;
    private final PrintWriter out;
    private final BufferedWriter csv;
    private final List<double[]> samples = Collections.synchronizedList(new ArrayList<>());
    private final List<String> warnings = new ArrayList<>();
    private final Map<String, Object> environment = new LinkedHashMap<>();

    private JMXConnector jmxConnector;
    private OperatingSystemMXBean jvmOs;
    private MemoryMXBean jvmMemory;
    private ThreadMXBean jvmThreads;
    private List<GarbageCollectorMXBean> jvmGcs = List.of();
    private long tomcatPid = -1;
    private Process dockerStats;
    private volatile String[] dockerLast;

    private Thread thread;
    private volatile boolean running;
    private long startNanos;
    private long prevNanos;
    private long prevCpuNanos = -1;
    private long prevGcCount = -1;
    private long prevGcMillis = -1;
    private long[] prevHostCpu;
    private long[] prevDisk;

    public ServerMonitor(Workdir workdir, Workdir.ServerInfo server, int intervalMillis, Path csvFile, PrintWriter out) throws IOException {
        this.workdir = workdir;
        this.server = server;
        this.intervalMillis = Math.max(200, intervalMillis);
        this.out = out;
        this.csv = Files.newBufferedWriter(csvFile, StandardCharsets.UTF_8);
        csv.write(String.join(",", COLUMNS));
        csv.newLine();
    }

    public void start() {
        connectJmx();
        findTomcat();
        startDockerStats();
        environment.put("hostCpus", Runtime.getRuntime().availableProcessors());
        environment.put("hostMemoryMb", readMemInfo("MemTotal") / 1024);
        environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        startNanos = prevNanos = System.nanoTime();
        sample(false);
        running = true;
        thread = Thread.ofPlatform().daemon().name("server-monitor").start(() -> {
            while (running) {
                try {
                    Thread.sleep(intervalMillis);
                } catch (InterruptedException e) {
                    return;
                }
                sample(true);
            }
        });
    }

    /** Seconds since start, the time axis of samples. */
    public double elapsed() {
        return (System.nanoTime() - startNanos) / 1e9;
    }

    public List<String> warnings() {
        return warnings;
    }

    /** Server JVM, host and database facts for the report. */
    public Map<String, Object> environment() {
        return environment;
    }

    private void connectJmx() {
        if (server.jmxPort <= 0) {
            warnings.add("JMX выключен (sdk.jmxPort = 0): нет метрик JVM Луны");
            return;
        }
        try {
            JMXServiceURL url = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://127.0.0.1:" + server.jmxPort + "/jmxrmi");
            jmxConnector = JMXConnectorFactory.connect(url, Map.of("jmx.remote.x.request.waiting.timeout", 5000L));
            MBeanServerConnection connection = jmxConnector.getMBeanServerConnection();
            jvmOs = ManagementFactory.newPlatformMXBeanProxy(connection, ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME, OperatingSystemMXBean.class);
            jvmMemory = ManagementFactory.newPlatformMXBeanProxy(connection, ManagementFactory.MEMORY_MXBEAN_NAME, MemoryMXBean.class);
            jvmThreads = ManagementFactory.newPlatformMXBeanProxy(connection, ManagementFactory.THREAD_MXBEAN_NAME, ThreadMXBean.class);
            List<GarbageCollectorMXBean> gcs = new ArrayList<>();
            for (ObjectName name : connection.queryNames(new ObjectName(ManagementFactory.GARBAGE_COLLECTOR_MXBEAN_DOMAIN_TYPE + ",*"), null)) {
                GarbageCollectorMXBean gc = ManagementFactory.newPlatformMXBeanProxy(connection, name.toString(), GarbageCollectorMXBean.class);
                // concurrent cycles run beside the application, only pauses stop it
                if (!gc.getName().contains("Concurrent")) {
                    gcs.add(gc);
                }
            }
            jvmGcs = gcs;
            RuntimeMXBean runtime = ManagementFactory.newPlatformMXBeanProxy(connection, ManagementFactory.RUNTIME_MXBEAN_NAME, RuntimeMXBean.class);
            environment.put("lunaJava", runtime.getVmName() + " " + runtime.getSpecVersion() + " (" + runtime.getVmVersion() + ")");
            environment.put("lunaJvmArgs", runtime.getInputArguments().stream()
                                                  .filter(a -> !a.startsWith("-Dcom.sun.management") && !a.startsWith("-Djava.rmi"))
                                                  .toList());
            environment.put("lunaCpus", jvmOs.getAvailableProcessors());
            environment.put("lunaHeapMaxMb", jvmMemory.getHeapMemoryUsage().getMax() >> 20);
            environment.put("lunaGc", jvmGcs.stream().map(GarbageCollectorMXBean::getName).toList());
        } catch (Exception e) {
            jvmOs = null;
            warnings.add("Нет подключения к JMX на порту " + server.jmxPort + " (" + e.getMessage()
                    + "): нет метрик JVM Луны. Луна должна быть запущена командой up с sdk.jmxPort");
        }
    }

    private void findTomcat() {
        if (server.pid <= 0) {
            return;
        }
        ProcessHandle.of(server.pid).ifPresent(sdk -> sdk.descendants()
                .filter(p -> p.info().commandLine().map(c -> c.contains("org.apache.catalina.startup.Bootstrap")).orElse(false))
                .findFirst()
                .ifPresent(p -> tomcatPid = p.pid()));
    }

    private void startDockerStats() {
        Path database = workdir.sdkState().resolve("database").toAbsolutePath();
        try {
            Process ps = new ProcessBuilder("docker", "ps", "-q").redirectErrorStream(true).start();
            List<String> ids = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines()
                                                                                                  .filter(l -> !l.isBlank()).toList();
            ps.waitFor();
            if (ids.isEmpty()) {
                warnings.add("Контейнер БД не найден: нет метрик PostgreSQL");
                return;
            }
            List<String> inspect = new ArrayList<>(List.of("docker", "inspect", "-f", "{{.Id}} {{.Name}} {{range .Mounts}}{{.Source}}|{{end}}"));
            inspect.addAll(ids);
            Process p = new ProcessBuilder(inspect).redirectErrorStream(true).start();
            String container = null;
            for (String line : new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                String[] parts = line.split(" ", 3);
                if (parts.length == 3 && Arrays.asList(parts[2].split("\\|")).contains(database.toString())) {
                    container = parts[0];
                    environment.put("dbContainer", parts[1].replaceFirst("^/", ""));
                }
            }
            p.waitFor();
            if (container == null) {
                warnings.add("Контейнер БД с каталогом " + database + " не найден: нет метрик PostgreSQL");
                return;
            }
            dockerStats = new ProcessBuilder("docker", "stats", "--format",
                    "{{.CPUPerc}};{{.MemUsage}};{{.BlockIO}}", container).redirectErrorStream(true).start();
            Thread.ofPlatform().daemon().name("docker-stats").start(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(dockerStats.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // docker stats redraws the screen with escape sequences even without a terminal
                        String clean = line.replaceAll("\u001B\\[[0-9;?]*[A-Za-z]", "").trim();
                        String[] parts = clean.split(";");
                        if (parts.length == 3 && parts[0].endsWith("%")) {
                            dockerLast = parts;
                        }
                    }
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            warnings.add("docker недоступен (" + e.getMessage() + "): нет метрик PostgreSQL");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void sample(boolean record) {
        long now = System.nanoTime();
        double dt = Math.max(1e-3, (now - prevNanos) / 1e9);
        double[] v = new double[COLUMNS.size()];
        Arrays.fill(v, Double.NaN);
        v[1] = (now - startNanos) / 1e9;

        if (jvmOs != null) {
            try {
                long cpu = jvmOs.getProcessCpuTime();
                if (prevCpuNanos >= 0) {
                    v[2] = (cpu - prevCpuNanos) / 1e9 / dt;
                }
                prevCpuNanos = cpu;
                MemoryUsage heap = jvmMemory.getHeapMemoryUsage();
                v[5] = heap.getUsed() / 1048576.0;
                v[6] = heap.getCommitted() / 1048576.0;
                v[7] = heap.getMax() / 1048576.0;
                v[8] = jvmMemory.getNonHeapMemoryUsage().getUsed() / 1048576.0;
                long gcCount = 0;
                long gcMillis = 0;
                for (GarbageCollectorMXBean gc : jvmGcs) {
                    gcCount += Math.max(0, gc.getCollectionCount());
                    gcMillis += Math.max(0, gc.getCollectionTime());
                }
                if (prevGcCount >= 0) {
                    v[9] = gcCount - prevGcCount;
                    v[10] = gcMillis - prevGcMillis;
                }
                v[11] = gcMillis;
                prevGcCount = gcCount;
                prevGcMillis = gcMillis;
                v[12] = jvmThreads.getThreadCount();
            } catch (Exception e) {
                // server restarting or gone: keep sampling the rest
            }
        }

        long[] hostCpu = readHostCpu();
        if (hostCpu != null) {
            if (prevHostCpu != null && hostCpu[0] > prevHostCpu[0]) {
                v[3] = 100.0 * (1 - (double) (hostCpu[1] - prevHostCpu[1]) / (hostCpu[0] - prevHostCpu[0]));
            }
            prevHostCpu = hostCpu;
        }
        v[4] = readLoad();
        if (tomcatPid > 0) {
            v[13] = readProcStatus(tomcatPid, "VmRSS") / 1024.0;
        }
        long avail = readMemInfo("MemAvailable");
        v[14] = avail >= 0 ? avail / 1024.0 : Double.NaN;
        long[] disk = readDisk();
        if (disk != null) {
            if (prevDisk != null) {
                v[15] = (disk[0] - prevDisk[0]) * 512 / 1048576.0 / dt;
                v[16] = (disk[1] - prevDisk[1]) * 512 / 1048576.0 / dt;
            }
            prevDisk = disk;
        }
        String[] docker = dockerLast;
        if (docker != null) {
            v[17] = parseDouble(docker[0].replace("%", ""));
            v[18] = parseSize(docker[1].split("/")[0]);
            String[] io = docker[2].split("/");
            v[19] = parseSize(io[0]);
            v[20] = io.length > 1 ? parseSize(io[1]) : Double.NaN;
        }
        prevNanos = now;

        if (record) {
            samples.add(v);
            try {
                StringJoiner line = new StringJoiner(",");
                line.add(LocalTime.now().format(TIME));
                for (int i = 1; i < v.length; i++) {
                    line.add(Double.isNaN(v[i]) ? "" : String.format(Locale.ROOT, i == 1 ? "%.1f" : "%.2f", v[i]));
                }
                csv.write(line.toString());
                csv.newLine();
                csv.flush();
            } catch (IOException ignored) {
            }
        }
    }

    /** Latest values: column -> value, for a progress line. */
    public Map<String, Double> latest() {
        Map<String, Double> m = new LinkedHashMap<>();
        synchronized (samples) {
            if (!samples.isEmpty()) {
                double[] v = samples.getLast();
                for (int i = 1; i < COLUMNS.size(); i++) {
                    m.put(COLUMNS.get(i), v[i]);
                }
            }
        }
        return m;
    }

    /**
     * Load between two moments ({@link #elapsed()} values): average and maximum of every metric,
     * GC pause time and count within the window, database block IO within the window.
     */
    public Map<String, Object> stats(double from, double to) {
        List<double[]> window;
        synchronized (samples) {
            window = samples.stream().filter(v -> v[1] >= from && v[1] <= to + intervalMillis / 1000.0).toList();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("samples", window.size());
        for (String column : List.of("jvm_cpu_cores", "host_cpu_pct", "load1", "heap_used_mb", "heap_committed_mb",
                                     "threads", "luna_rss_mb", "disk_read_mb_s", "disk_write_mb_s", "db_cpu_pct", "db_mem_mb")) {
            int i = COLUMNS.indexOf(column);
            DoubleSummaryStatistics s = window.stream().mapToDouble(v -> v[i]).filter(d -> !Double.isNaN(d)).summaryStatistics();
            if (s.getCount() > 0) {
                out.put(column, Map.of("avg", round(s.getAverage()), "max", round(s.getMax())));
            }
        }
        int pauses = COLUMNS.indexOf("gc_pauses");
        int pauseMs = COLUMNS.indexOf("gc_pause_ms");
        double gcCount = window.stream().mapToDouble(v -> v[pauses]).filter(d -> !Double.isNaN(d)).sum();
        double gcMs = window.stream().mapToDouble(v -> v[pauseMs]).filter(d -> !Double.isNaN(d)).sum();
        if (jvmOs != null) {
            out.put("gc_pauses", (long) gcCount);
            out.put("gc_pause_ms", (long) gcMs);
            out.put("gc_pause_share_pct", round(100 * gcMs / 1000.0 / Math.max(0.001, to - from)));
        }
        for (String column : List.of("db_block_read_mb", "db_block_write_mb")) {
            int i = COLUMNS.indexOf(column);
            double[] values = window.stream().mapToDouble(v -> v[i]).filter(d -> !Double.isNaN(d)).toArray();
            if (values.length > 1) {
                out.put(column, round(values[values.length - 1] - values[0]));
            }
        }
        return out;
    }

    @Override
    public void close() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (dockerStats != null) {
            dockerStats.destroy();
        }
        try {
            csv.close();
        } catch (IOException ignored) {
        }
        if (jmxConnector != null) {
            try {
                jmxConnector.close();
            } catch (IOException ignored) {
            }
        }
    }

    public void printWarnings() {
        warnings.forEach(w -> out.println("Внимание: " + w));
    }

    // ------------------------------------------------------------------ /proc

    private static long[] readHostCpu() {
        try {
            String line = Files.readAllLines(Path.of("/proc/stat")).getFirst();
            String[] f = line.trim().split("\\s+");
            long total = 0;
            for (int i = 1; i < f.length; i++) {
                total += Long.parseLong(f[i]);
            }
            long idle = Long.parseLong(f[4]) + (f.length > 5 ? Long.parseLong(f[5]) : 0);
            return new long[]{total, idle};
        } catch (Exception e) {
            return null;
        }
    }

    private static double readLoad() {
        try {
            return Double.parseDouble(Files.readString(Path.of("/proc/loadavg")).split(" ")[0]);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    /** kB, -1 if not available. */
    static long readMemInfo(String key) {
        return readKeyValue(Path.of("/proc/meminfo"), key);
    }

    private static long readProcStatus(long pid, String key) {
        return readKeyValue(Path.of("/proc", String.valueOf(pid), "status"), key);
    }

    private static long readKeyValue(Path file, String key) {
        try {
            for (String line : Files.readAllLines(file)) {
                if (line.startsWith(key + ":")) {
                    return Long.parseLong(line.substring(key.length() + 1).trim().split("\\s+")[0]);
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** Sectors read and written by whole disks (not partitions, loop or device-mapper devices). */
    private static long[] readDisk() {
        try {
            Set<String> disks = new HashSet<>();
            try (var stream = Files.list(Path.of("/sys/block"))) {
                stream.map(p -> p.getFileName().toString())
                      .filter(n -> !n.startsWith("loop") && !n.startsWith("ram") && !n.startsWith("zram") && !n.startsWith("dm-"))
                      .forEach(disks::add);
            }
            long read = 0;
            long written = 0;
            for (String line : Files.readAllLines(Path.of("/proc/diskstats"))) {
                String[] f = line.trim().split("\\s+");
                if (f.length > 9 && disks.contains(f[2])) {
                    read += Long.parseLong(f[5]);
                    written += Long.parseLong(f[9]);
                }
            }
            return new long[]{read, written};
        } catch (Exception e) {
            return null;
        }
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** docker sizes ("12.5MiB", "1.2GB", "0B") in megabytes. */
    static double parseSize(String s) {
        Matcher m = SIZE.matcher(s.trim());
        if (!m.find()) {
            return Double.NaN;
        }
        double value = Double.parseDouble(m.group(1));
        String unit = m.group(2);
        boolean binary = unit.contains("i");
        double base = binary ? 1024 : 1000;
        double bytes = switch (Character.toUpperCase(unit.charAt(0))) {
            case 'K' -> value * base;
            case 'M' -> value * base * base;
            case 'G' -> value * base * base * base;
            case 'T' -> value * base * base * base * base;
            default -> value;
        };
        return bytes / 1048576.0;
    }

    static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
