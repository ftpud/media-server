package live.jmusic.streamservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import live.jmusic.shared.model.MediaItem;
import live.jmusic.shared.model.RotationItem;
import live.jmusic.shared.rest.RestRequestService;
import live.jmusic.streamservice.util.SubtitlesService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives the persistent mediad player instead of spawning one ffmpeg per track.
 * Active only when media.mediad.enabled=true. media-service stays the source of the rotation order;
 * mediad's track_started events re-anchor media-service's time pointer (no wall-clock drift).
 */
@Slf4j
@Service
public class MediadStreamService {

    @Autowired
    RestRequestService restRequestService;

    @Autowired
    SubtitlesService subtitlesService;

    @Value("${media.mediad.enabled:false}")
    private boolean enabled;

    @Value("${media.mediad.url:http://localhost:8095}")
    private String url;

    private final RestTemplate http;
    private volatile boolean nextQueued = false;

    public MediadStreamService() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3000);
        f.setReadTimeout(45000); // longer than the long-poll wait
        http = new RestTemplate(f);
    }

    public boolean isEnabled() {
        return enabled;
    }

    private URI uri(String path, String... kv) {
        UriComponentsBuilder b = UriComponentsBuilder.fromHttpUrl(url + path);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            b.queryParam(kv[i], kv[i + 1]);
        }
        // mediad decodes '+' as space, so make sure literal plus signs are escaped
        return URI.create(b.build().encode().toUriString().replace("+", "%2B"));
    }

    private JsonNode get(URI u) {
        return http.getForObject(u, JsonNode.class);
    }

    private String gain(MediaItem item) {
        try {
            if (item.getVolume() != null && !item.getVolume().isEmpty()) {
                float v = -10 - Float.parseFloat(item.getVolume());
                if (!Float.isNaN(v) && !Float.isInfinite(v)) {
                    return String.valueOf(Math.max(-40f, Math.min(25f, v)));
                }
            }
        } catch (NumberFormatException e) {
            log.info("Bad volume {}", item.getVolume());
        }
        return "0";
    }

    private void load(MediaItem item, long startMs, boolean now) {
        String subs = "";
        try {
            Optional<String> s = subtitlesService.buildSubtitlesFile(item, startMs);
            subs = s.orElse("");
        } catch (Exception e) {
            log.info("Subtitles failed: {}", e.toString());
        }
        JsonNode r = get(uri("/load",
                "path", item.getFullpath(),
                "title", item.getTitle() == null ? "" : item.getTitle(),
                "start_ms", String.valueOf(startMs),
                "gain_db", gain(item),
                "subs", subs,
                "now", now ? "1" : "0"));
        log.info("mediad load {} (start {} ms, now={}) -> {}", item.getFullpath(), startMs, now, r);
    }

    /** Plays whatever media-service says is current (used at startup, when idle, after seek/next). */
    public void reloadCurrent(boolean replace) {
        AtomicReference<RotationItem> ref = new AtomicReference<>();
        restRequestService.requestNow(ref::set);
        RotationItem item = ref.get();
        if (item == null) {
            return;
        }
        if (replace) {
            get(uri("/clear"));
        }
        nextQueued = false;
        load(item.getMediaItem(), item.getCurrentTime(), replace);
    }

    private void queueNext() {
        AtomicReference<MediaItem[]> ref = new AtomicReference<>();
        restRequestService.requestUpcoming(ref::set);
        MediaItem[] items = ref.get();
        // index 0 is the current item, 1 the next one; at the end of the rotation there is no next yet
        if (items != null && items.length >= 2) {
            load(items[1], 0, false);
            nextQueued = true;
        } else {
            nextQueued = false;
        }
    }

    private void handle(JsonNode e) {
        String type = e.path("type").asText();
        switch (type) {
            case "track_started":
                nextQueued = false;
                restRequestService.requestSync(e.path("path").asText(), e.path("start_ms").asLong());
                break;
            case "track_ending":
                queueNext();
                break;
            case "idle":
                // nothing left in mediad's queue: ask media-service what should play now
                reloadCurrent(false);
                break;
            case "track_error":
                log.warn("mediad track error: {}", e);
                break;
            default:
                log.info("mediad event {}", e);
        }
    }

    public void run() {
        long since = 0;
        while (true) {
            try {
                if (since == 0) {
                    // skip the event history (e.g. a stale "idle" from before we connected), then start playing
                    since = get(uri("/events", "since", "0", "wait", "0")).path("next").asLong();
                    reloadCurrent(true);
                }
                JsonNode r = get(uri("/events", "since", String.valueOf(since), "wait", "15000"));
                long next = r.path("next").asLong();
                if (next < since) { // mediad restarted, ids started over
                    since = 0;
                    continue;
                }
                for (JsonNode e : r.path("events")) {
                    since = Math.max(since, e.path("id").asLong());
                    handle(e);
                }
                since = Math.max(since, next);
            } catch (Exception ex) {
                log.warn("mediad loop error: {}", ex.toString());
                since = 0;
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
