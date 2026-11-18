package live.jmusic.dashboard;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Finds, starts and stops service processes without shell strings or pgrep substring games. */
@Service
public class ProcessService {

    private final Registry registry;
    private final Map<Long, long[]> cpuSamples = new ConcurrentHashMap<>(); // pid -> {cpuNanos, wallNanos}

    public ProcessService(Registry registry) {
        this.registry = registry;
    }

    private static String cmd(ProcessHandle p) {
        // /proc/<pid>/cmdline is readable for every process; ProcessHandle.Info is not (other users)
        try {
            byte[] b = Files.readAllBytes(Path.of("/proc/" + p.pid() + "/cmdline"));
            if (b.length > 0) {
                return new String(b, java.nio.charset.StandardCharsets.UTF_8).replace('\0', ' ').trim();
            }
        } catch (Exception ignored) {
        }
        ProcessHandle.Info i = p.info();
        String c = i.command().orElse("");
        String[] a = i.arguments().orElse(new String[0]);
        return c + " " + String.join(" ", a);
    }

    /** Processes of a service, in pattern order (the first pattern is the "main" process). */
    public List<ProcessHandle> find(ServiceDef d) {
        long self = ProcessHandle.current().pid();
        List<ProcessHandle> all = ProcessHandle.allProcesses().filter(p -> p.pid() != self || d.self).collect(Collectors.toList());
        List<ProcessHandle> out = new ArrayList<>();
        for (String pat : d.patterns) {
            for (ProcessHandle p : all) {
                String c = cmd(p);
                if (c.contains(pat) && !c.contains("pgrep") && out.stream().noneMatch(o -> o.pid() == p.pid())) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    public Map<String, Object> describe(ProcessHandle p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pid", p.pid());
        m.put("command", cmd(p).trim());
        p.info().startInstant().ifPresent(s -> m.put("uptimeSec", Duration.between(s, Instant.now()).getSeconds()));
        m.put("rssMb", rssMb(p.pid()));
        m.put("cpu", cpuPercent(p));
        return m;
    }

    private long rssMb(long pid) {
        try {
            for (String l : Files.readAllLines(Path.of("/proc/" + pid + "/status"))) {
                if (l.startsWith("VmRSS:")) {
                    return Long.parseLong(l.replaceAll("[^0-9]", "")) / 1024;
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** CPU % (of one core) since the previous sample of this pid. */
    private double cpuPercent(ProcessHandle p) {
        long cpu = p.info().totalCpuDuration().map(Duration::toNanos).orElse(0L);
        long now = System.nanoTime();
        long[] prev = cpuSamples.put(p.pid(), new long[]{cpu, now});
        if (prev == null || now <= prev[1]) {
            return 0;
        }
        return Math.round(1000.0 * (cpu - prev[0]) / (now - prev[1])) / 10.0;
    }

    public void start(ServiceDef d) throws IOException {
        File log = new File(registry.logsDir(), d.logs.get(0));
        List<String> c = new ArrayList<>(Arrays.asList("setsid", "nohup"));
        c.addAll(d.start);
        new ProcessBuilder(c)
                .directory(new File(registry.appDir))
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                .start();
    }

    /** SIGTERM the service and all its descendants, wait, then SIGKILL what is left. */
    public int stop(ServiceDef d, Duration grace) throws InterruptedException {
        List<ProcessHandle> targets = new ArrayList<>();
        for (ProcessHandle p : find(d)) {
            targets.add(p);
            p.descendants().forEach(targets::add); // e.g. ffmpeg children that would otherwise be orphaned
        }
        targets.forEach(ProcessHandle::destroy);
        long deadline = System.currentTimeMillis() + grace.toMillis();
        while (System.currentTimeMillis() < deadline && targets.stream().anyMatch(ProcessHandle::isAlive)) {
            Thread.sleep(200);
        }
        targets.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        return targets.size();
    }
}
