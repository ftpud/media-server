package live.jmusic.dashboard;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Versioned deploys: a new jar dropped into the deploy dir can be installed; the replaced jar is
 * kept in app/versions/ so any previous version can be restored with one click.
 */
@Service
public class DeployService {

    private final Registry registry;

    public DeployService(Registry registry) {
        this.registry = registry;
    }

    private File versionsDir() {
        File d = new File(registry.appDir, "versions");
        d.mkdirs();
        return d;
    }

    public Optional<File> pending(ServiceDef d) {
        if (d.jar == null) return Optional.empty();
        File f = new File(registry.deployDir, d.jar);
        return f.isFile() ? Optional.of(f) : Optional.empty();
    }

    public List<Map<String, Object>> versions(ServiceDef d) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (d.jar == null) return out;
        String prefix = d.jar.replace(".jar", "") + "@";
        File[] files = versionsDir().listFiles((dir, n) -> n.startsWith(prefix) && n.endsWith(".jar"));
        if (files == null) return out;
        Arrays.sort(files, Comparator.comparing(File::getName).reversed());
        for (File f : files) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("file", f.getName());
            m.put("version", f.getName().substring(prefix.length(), f.getName().length() - 4));
            m.put("sizeMb", f.length() / 1_000_000);
            out.add(m);
        }
        return out;
    }

    private void archiveCurrent(ServiceDef d) throws IOException {
        File cur = new File(registry.appDir, d.jar);
        if (cur.isFile()) {
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date(cur.lastModified()));
            Files.copy(cur.toPath(), new File(versionsDir(), d.jar.replace(".jar", "") + "@" + stamp + ".jar").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Installs the pending jar (keeping the current one as a version). */
    public void install(ServiceDef d) throws IOException {
        File src = pending(d).orElseThrow(() -> new IllegalStateException("nothing to deploy for " + d.name));
        archiveCurrent(d);
        Files.move(src.toPath(), new File(registry.appDir, d.jar).toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /** Restores an archived version (the current jar is archived first, so this is reversible too). */
    public void restore(ServiceDef d, String file) throws IOException {
        File v = new File(versionsDir(), file);
        if (!v.isFile() || !file.startsWith(d.jar.replace(".jar", "") + "@") || file.contains("/")) {
            throw new IllegalArgumentException("unknown version " + file);
        }
        archiveCurrent(d);
        Files.copy(v.toPath(), new File(registry.appDir, d.jar).toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
