package live.jmusic.dashboard;

import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Host metrics from /proc: load, memory, disks (space + how busy each drive is). */
@Service
public class SystemService {

    private static final List<String> MOUNTS = Arrays.asList("/", "/home", "/mnt/content/1TB_1", "/mnt/content/1TB_2",
            "/mnt/content/500GB", "/mnt/old_tb", "/mnt/1tb_m2");
    private Map<String, long[]> lastIo = new HashMap<>(); // device -> {io_ticks ms, wall ms}

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            String[] la = Files.readString(Path.of("/proc/loadavg")).trim().split(" ");
            m.put("load", Arrays.asList(Double.valueOf(la[0]), Double.valueOf(la[1]), Double.valueOf(la[2])));
        } catch (Exception ignored) {
        }
        m.put("cpus", Runtime.getRuntime().availableProcessors());
        try {
            Map<String, Long> mem = new HashMap<>();
            for (String l : Files.readAllLines(Path.of("/proc/meminfo"))) {
                String[] p = l.split(":");
                mem.put(p[0], Long.parseLong(p[1].replaceAll("[^0-9]", "")) / 1024);
            }
            m.put("memTotalMb", mem.get("MemTotal"));
            m.put("memAvailableMb", mem.get("MemAvailable"));
            double up = Double.parseDouble(Files.readString(Path.of("/proc/uptime")).split(" ")[0]);
            m.put("uptimeSec", (long) up);
        } catch (Exception ignored) {
        }
        Map<String, String> devOf = mountDevices();
        Map<String, Double> busy = ioBusy();
        List<Map<String, Object>> disks = new ArrayList<>();
        for (String mp : MOUNTS) {
            File f = new File(mp);
            if (!f.exists()) continue;
            try {
                FileStore fs = Files.getFileStore(Path.of(mp));
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("mount", mp);
                d.put("totalGb", fs.getTotalSpace() / 1_000_000_000L);
                d.put("freeGb", fs.getUsableSpace() / 1_000_000_000L);
                d.put("readOnly", fs.isReadOnly());
                String dev = devOf.get(mp);
                d.put("device", dev);
                if (dev != null) {
                    String base = dev.replaceAll("^/dev/", "").replaceAll("[0-9]+$", "");
                    d.put("busy", busy.getOrDefault(base, busy.get(dev.replaceAll("^/dev/", ""))));
                }
                disks.add(d);
            } catch (Exception ignored) {
            }
        }
        m.put("disks", disks);
        return m;
    }

    private Map<String, String> mountDevices() {
        Map<String, String> r = new HashMap<>();
        try {
            for (String l : Files.readAllLines(Path.of("/proc/mounts"))) {
                String[] p = l.split(" ");
                r.put(p[1], p[0]);
            }
        } catch (Exception ignored) {
        }
        return r;
    }

    /** % of time each block device was busy since the previous call (from /proc/diskstats io_ticks). */
    private synchronized Map<String, Double> ioBusy() {
        Map<String, Double> r = new HashMap<>();
        Map<String, long[]> now = new HashMap<>();
        long wall = System.currentTimeMillis();
        try {
            for (String l : Files.readAllLines(Path.of("/proc/diskstats"))) {
                String[] p = l.trim().split("\\s+");
                if (p.length < 13) continue;
                long ticks = Long.parseLong(p[12]);
                now.put(p[2], new long[]{ticks, wall});
                long[] prev = lastIo.get(p[2]);
                if (prev != null && wall > prev[1]) {
                    r.put(p[2], Math.min(100.0, Math.round(1000.0 * (ticks - prev[0]) / (wall - prev[1])) / 10.0));
                }
            }
        } catch (Exception ignored) {
        }
        lastIo = now;
        return r;
    }
}
