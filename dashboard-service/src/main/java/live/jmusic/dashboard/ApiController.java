package live.jmusic.dashboard;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.util.UriUtils;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@RestController
@RequestMapping("/api")
public class ApiController {

    private static final String MEDIAD = "http://localhost:8095";
    private static final String MEDIA = "http://localhost:8084";
    private static final String LIVE = "http://localhost:8089";

    private final Registry registry;
    private final ProcessService processes;
    private final LogService logs;
    private final SystemService system;
    private final DeployService deploys;
    private final AuthFilter auth;
    private final RestTemplate http;
    private final ExecutorService pool = Executors.newFixedThreadPool(8);

    public ApiController(Registry registry, ProcessService processes, LogService logs, SystemService system,
                         DeployService deploys, AuthFilter auth) {
        this.registry = registry;
        this.processes = processes;
        this.logs = logs;
        this.system = system;
        this.deploys = deploys;
        this.auth = auth;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(2000);
        f.setReadTimeout(30000);
        this.http = new RestTemplate(f);
    }

    // ---------- auth ----------
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        if (!auth.matches(body.get("token"))) {
            return ResponseEntity.status(401).body(Map.of("error", "wrong token"));
        }
        ResponseCookie c = ResponseCookie.from(AuthFilter.COOKIE, body.get("token").trim())
                .httpOnly(true).sameSite("Strict").path("/").maxAge(Duration.ofDays(30)).build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, c.toString()).body(Map.of("ok", true));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        ResponseCookie c = ResponseCookie.from(AuthFilter.COOKIE, "").path("/").maxAge(0).build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, c.toString()).body(Map.of("ok", true));
    }

    // ---------- services ----------
    private ServiceDef def(String name) {
        return registry.get(name).orElseThrow(() -> new NoSuchElementException("unknown service " + name));
    }

    private String health(ServiceDef d) {
        if (d.healthUrl == null) return null;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(d.healthUrl).openConnection();
            c.setConnectTimeout(1000);
            c.setReadTimeout(2000);
            return c.getResponseCode() == 200 ? "up" : "down";
        } catch (Exception e) {
            return "down";
        }
    }

    private Map<String, Object> status(ServiceDef d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", d.name);
        m.put("title", d.title);
        m.put("group", d.group);
        m.put("description", d.description);
        List<Map<String, Object>> procs = new ArrayList<>();
        for (ProcessHandle p : processes.find(d)) procs.add(processes.describe(p));
        m.put("processes", procs);
        m.put("running", !procs.isEmpty());
        m.put("health", procs.isEmpty() ? null : health(d));
        m.put("logs", d.logs);
        m.put("self", d.self);
        m.put("conflictsWith", d.conflictsWith);
        deploys.pending(d).ifPresent(f -> m.put("pendingDeploy", Map.of("file", f.getName(), "modified", f.lastModified())));
        m.put("versions", deploys.versions(d).size());
        return m;
    }

    @GetMapping("/services")
    public List<Map<String, Object>> services() throws Exception {
        List<Future<Map<String, Object>>> fs = new ArrayList<>();
        for (ServiceDef d : registry.all()) fs.add(pool.submit(() -> status(d)));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Future<Map<String, Object>> f : fs) out.add(f.get(10, TimeUnit.SECONDS));
        return out;
    }

    private void checkConflict(ServiceDef d) {
        if (d.conflictsWith != null) {
            ServiceDef other = def(d.conflictsWith);
            if (!processes.find(other).isEmpty()) {
                throw new IllegalStateException(d.title + " cannot run together with " + other.title + ": stop it first");
            }
        }
    }

    /** Restarting the dashboard itself: a detached shell kills this JVM and starts the jar again. */
    private void restartSelf() throws Exception {
        long pid = ProcessHandle.current().pid();
        String cmd = "sleep 1; kill " + pid + "; sleep 3; cd '" + registry.appDir + "' && nohup java -jar dashboard-service.jar"
                + " --spring.config.location=classpath:/application.properties,config.properties >> logs/dashboard-service.log 2>&1 &";
        new ProcessBuilder("setsid", "sh", "-c", cmd).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    @PostMapping("/services/{name}/{action}")
    public Map<String, Object> action(@PathVariable String name, @PathVariable String action) throws Exception {
        ServiceDef d = def(name);
        switch (action) {
            case "start":
                if (!processes.find(d).isEmpty()) throw new IllegalStateException(d.title + " is already running");
                checkConflict(d);
                processes.start(d);
                break;
            case "stop":
                if (d.self) throw new IllegalStateException("the dashboard cannot stop itself");
                processes.stop(d, Duration.ofSeconds(10));
                break;
            case "restart":
                if (d.self) {
                    restartSelf();
                    break;
                }
                processes.stop(d, Duration.ofSeconds(10));
                checkConflict(d);
                processes.start(d);
                break;
            case "deploy":
                deploys.install(d);
                if (d.self) {
                    restartSelf();
                } else {
                    processes.stop(d, Duration.ofSeconds(10));
                    checkConflict(d);
                    processes.start(d);
                }
                break;
            default:
                throw new IllegalArgumentException("unknown action " + action);
        }
        return Map.of("ok", true, "service", name, "action", action);
    }

    @GetMapping("/services/{name}/versions")
    public List<Map<String, Object>> versions(@PathVariable String name) {
        return deploys.versions(def(name));
    }

    @PostMapping("/services/{name}/rollback")
    public Map<String, Object> rollback(@PathVariable String name, @RequestBody Map<String, String> body) throws Exception {
        ServiceDef d = def(name);
        deploys.restore(d, body.get("file"));
        if (d.self) {
            restartSelf();
        } else {
            processes.stop(d, Duration.ofSeconds(10));
            checkConflict(d);
            processes.start(d);
        }
        return Map.of("ok", true);
    }

    // ---------- logs ----------
    private File logFile(ServiceDef d, String file) {
        String f = file == null ? d.logs.get(0) : file;
        if (!d.logs.contains(f)) throw new IllegalArgumentException("unknown log " + f);
        return new File(registry.logsDir(), f);
    }

    @GetMapping("/logs/{name}")
    public Map<String, Object> tail(@PathVariable String name, @RequestParam(required = false) String file,
                                    @RequestParam(defaultValue = "300") int lines) throws Exception {
        File f = logFile(def(name), file);
        return Map.of("file", f.getName(), "sizeMb", f.length() / 1_000_000, "lines", logs.tail(f, Math.min(lines, 5000)));
    }

    @GetMapping(value = "/logs/{name}/follow", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter follow(@PathVariable String name, @RequestParam(required = false) String file) {
        return logs.follow(logFile(def(name), file));
    }

    // ---------- host ----------
    @GetMapping("/system")
    public Map<String, Object> system() {
        return system.snapshot();
    }

    // ---------- stream (proxied to mediad / media-service / live-service) ----------
    private ResponseEntity<String> proxy(String url, Object body) {
        try {
            String r = body == null ? http.getForObject(url, String.class) : http.postForObject(url, body, String.class);
            String json = r == null ? "null" : r.trim();
            if (!(json.startsWith("{") || json.startsWith("[") || json.startsWith("\""))) json = "\"" + json.replace("\"", "\\\"") + "\"";
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}");
        }
    }

    @GetMapping("/stream/status")
    public ResponseEntity<String> streamStatus() {
        return proxy(MEDIAD + "/status", null);
    }

    @GetMapping("/stream/events")
    public ResponseEntity<String> streamEvents(@RequestParam(defaultValue = "0") long since, @RequestParam(defaultValue = "0") long wait) {
        return proxy(MEDIAD + "/events?since=" + since + "&wait=" + Math.min(wait, 25000), null);
    }

    @GetMapping(value = "/stream/snapshot", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> snapshot() {
        try {
            byte[] img = http.getForObject(MEDIAD + "/snapshot.jpg", byte[].class);
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").contentType(MediaType.IMAGE_JPEG).body(img);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @GetMapping("/stream/upcoming")
    public ResponseEntity<String> upcoming() {
        return proxy(MEDIA + "/media/list?internal=1", null);
    }

    @PostMapping("/stream/next")
    public ResponseEntity<String> next() {
        return proxy(MEDIA + "/media/next", null);
    }

    @PostMapping("/stream/seek")
    public ResponseEntity<String> seek(@RequestBody Map<String, String> body) {
        String t = body.getOrDefault("time", "").trim();
        if (!t.matches("[0-9]{1,3}(:[0-9]{1,2}){1,2}")) return ResponseEntity.badRequest().body("{\"error\":\"use M:SS or H:MM:SS\"}");
        return proxy(MEDIA + "/media/seek/" + t, null);
    }

    @PostMapping("/stream/search")
    public ResponseEntity<String> search(@RequestBody Map<String, String> body) {
        return proxy(MEDIA + "/media/search/?internal=1", body.getOrDefault("q", ""));
    }

    @PostMapping("/stream/enqueue")
    public ResponseEntity<String> enqueue(@RequestBody Map<String, Object> body) {
        boolean next = Boolean.TRUE.equals(body.get("next"));
        return proxy(MEDIA + (next ? "/media/enqueue/next/" : "/media/enqueue/"), String.valueOf(body.getOrDefault("q", "")));
    }

    @PostMapping("/stream/message")
    public ResponseEntity<String> message(@RequestBody Map<String, String> body) {
        String text = body.getOrDefault("text", "").trim();
        if (text.isEmpty()) return ResponseEntity.badRequest().body("{\"error\":\"empty message\"}");
        return proxy(LIVE + "/live/message/" + UriUtils.encodePathSegment("dash", StandardCharsets.UTF_8), text);
    }

    // ---------- errors ----------
    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class, NoSuchElementException.class})
    public ResponseEntity<Map<String, String>> bad(Exception e) {
        return ResponseEntity.status(e instanceof NoSuchElementException ? 404 : 409).body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
