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
        File installed = new File(registry.appDir, d.target);
        // only offer builds that are newer than what is installed (old leftovers must not downgrade)
        return f.isFile() && (!installed.isFile() || f.lastModified() > installed.lastModified()) ? Optional.of(f) : Optional.empty();
    }

    public List<Map<String, Object>> versions(ServiceDef d) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (d.jar == null) return out;
        String prefix = base(d) + "@";
        File[] files = versionsDir().listFiles((dir, n) -> n.startsWith(prefix));
        if (files == null) return out;
        Arrays.sort(files, Comparator.comparing(File::getName).reversed());
        for (File f : files) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("file", f.getName());
            m.put("version", f.getName().substring(prefix.length()).replace(".jar", ""));
            m.put("sizeMb", f.length() / 1_000_000);
            out.add(m);
        }
        return out;
    }

    private static String base(ServiceDef d) {
        return d.jar.replace(".jar", "");
    }

    private static String suffix(ServiceDef d) {
        return d.jar.endsWith(".jar") ? ".jar" : "";
    }

    private void archiveCurrent(ServiceDef d) throws IOException {
        File cur = new File(registry.appDir, d.target);
        if (cur.isFile()) {
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date(cur.lastModified()));
            Files.copy(cur.toPath(), new File(versionsDir(), base(d) + "@" + stamp + suffix(d)).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void makeExecutable(ServiceDef d) {
        if (!d.jar.endsWith(".jar")) new File(registry.appDir, d.target).setExecutable(true, false);
    }

    /** Installs the pending jar (keeping the current one as a version). */
    public void install(ServiceDef d) throws IOException {
        File src = pending(d).orElseThrow(() -> new IllegalStateException("nothing to deploy for " + d.name));
        archiveCurrent(d);
        // copy then delete: the target may be on another filesystem; a running binary is replaced atomically
        File tmp = new File(registry.appDir, d.target + ".new");
        Files.copy(src.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp.toPath(), new File(registry.appDir, d.target).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.delete(src.toPath());
        makeExecutable(d);
    }

    /** Restores an archived version (the current jar is archived first, so this is reversible too). */
    public void restore(ServiceDef d, String file) throws IOException {
        File v = new File(versionsDir(), file);
        if (!v.isFile() || !file.startsWith(base(d) + "@") || file.contains("/")) {
            throw new IllegalArgumentException("unknown version " + file);
        }
        archiveCurrent(d);
        File tmp = new File(registry.appDir, d.target + ".new");
        Files.copy(v.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp.toPath(), new File(registry.appDir, d.target).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        makeExecutable(d);
    }
}
