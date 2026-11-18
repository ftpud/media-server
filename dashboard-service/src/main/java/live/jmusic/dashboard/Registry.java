package live.jmusic.dashboard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class Registry {

    @Value("${dashboard.app-dir:/home/ftpud/media-server/app}")
    public String appDir;

    @Value("${dashboard.deploy-dir:/home/ftpud/media-server/deploy}")
    public String deployDir;

    public String logsDir() {
        return appDir + "/logs";
    }

    private final Map<String, ServiceDef> services = new LinkedHashMap<>();

    private static List<String> java(String jar) {
        return Arrays.asList("java", "-jar", jar, "--spring.config.location=classpath:/application.properties,config.properties");
    }

    public Registry() {
        add(new ServiceDef("mediad", "mediad", "stream", "Player + encoder: one persistent ffmpeg pipeline to RTMP",
                Arrays.asList("./mediad.sh"), Arrays.asList("mediad.sh", "mediad --out"),
                Arrays.asList("mediad.log"), "http://localhost:8095/health", null, "publisher-service", false));
        add(new ServiceDef("stream-service", "Stream service", "stream", "Feeds mediad with the rotation (media.mediad.enabled)",
                java("stream-service.jar"), Arrays.asList("-jar stream-service.jar", "ffmpeg_stream -threads 1 -init_hw_device"),
                Arrays.asList("stream-service.log"), "http://localhost:8088/health", "stream-service.jar", null, false));
        add(new ServiceDef("media-service", "Media library", "core", "Library, rotation, queue, search, tags",
                java("media-service.jar"), Arrays.asList("-jar media-service.jar"),
                Arrays.asList("media-service.log"), "http://localhost:8084/health", "media-service.jar", null, false));
        add(new ServiceDef("chat-service", "Chat bot", "core", "Twitch / GoodGame / sc2tv chat commands",
                java("chat-service.jar"), Arrays.asList("-jar chat-service.jar"),
                Arrays.asList("chat-service.log"), "http://localhost:8090/health", "chat-service.jar", null, false));
        add(new ServiceDef("live-service", "Live messages", "core", "On-screen live text (live.txt)",
                java("live-service.jar"), Arrays.asList("-jar live-service.jar"),
                Arrays.asList("live-service.log"), "http://localhost:8089/health", "live-service.jar", null, false));
        add(new ServiceDef("dashboard-service", "Dashboard", "core", "This control panel",
                java("dashboard-service.jar"), Arrays.asList("-jar dashboard-service.jar"),
                Arrays.asList("dashboard-service.log"), null, "dashboard-service.jar", null, true));
        add(new ServiceDef("publisher-service", "Publisher (legacy)", "legacy", "Old pipeline: concat ffmpeg -> RTMP. Replaced by mediad",
                java("publisher-service.jar"), Arrays.asList("-jar publisher-service.jar", "-jar balance-service.jar", "ffmpeg_stream -threads 1 -f concat"),
                Arrays.asList("publisher-service.log", "log-publisher.log", "log-balancer.log"), null, "publisher-service.jar", "mediad", false));
    }

    private void add(ServiceDef d) {
        services.put(d.name, d);
    }

    public List<ServiceDef> all() {
        return List.copyOf(services.values());
    }

    public Optional<ServiceDef> get(String name) {
        return Optional.ofNullable(services.get(name));
    }
}
