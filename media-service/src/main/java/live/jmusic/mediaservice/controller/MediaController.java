package live.jmusic.mediaservice.controller;

import live.jmusic.mediaservice.service.MediaDbService;
import live.jmusic.shared.model.QueuedItem;
import live.jmusic.shared.model.RotationItem;
import live.jmusic.mediaservice.repository.RotationRepository;
import live.jmusic.mediaservice.repository.MediaRepository;
import live.jmusic.shared.model.MediaItem;
import live.jmusic.mediaservice.service.ChronoService;
import live.jmusic.shared.rest.RestRequestService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.hibernate.Hibernate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@RestController
@RequestMapping("/media")
public class MediaController {

    @Autowired
    RotationRepository rotationRepository;

    @Autowired
    MediaRepository mediaRepository;

    @Autowired
    RestRequestService restRequestService;

    @Autowired
    MediaDbService mediaDbService;

    @GetMapping("/process/trigger")
    public String triggerProcessing() {
        mediaDbService.startFullProcessing();
        return "ok";
    }

    @GetMapping("/process/cleanup")
    public String triggerCleanup() {
        mediaDbService.startCleanup();
        return "ok";
    }

    @PostMapping("/process/file")
    public String processFile(@RequestBody String item) {

        Optional<MediaItem> itemFound = searchAll(item, 1).stream().findFirst();

        if (itemFound.isPresent()) {
            mediaDbService.processFile(new File(itemFound.get().getFullpath()), true);
        }


        return "ok";
    }

    @GetMapping("/seek/{time}")
    public String seek(@PathVariable String time) {
        try {
            // accepts M:SS, MM:SS (minutes may exceed 59) and H:MM:SS
            String[] parts = time.trim().split(":");
            long secs = 0;
            for (String p : parts) {
                secs = secs * 60 + Long.parseLong(p);
            }
            if (parts.length < 2 || parts.length > 3) {
                return "err";
            }
            Long fwdTime = secs * 1000L;
            RotationItem item = rotationRepository.getItemForTime(ChronoService.getTimePointer());
            ChronoService.pushOffset(-item.currentTime + fwdTime);
            restRequestService.requestRestart();
            return "ok";
        } catch (Exception e) {
            log.info(e.toString());
            return "err";
        }
    }

    private final Set<Long> volumeFixAttempted = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @GetMapping("/fixVolume/{id}")
    public String fixVolume(@PathVariable("id") Long id) {
        Optional<MediaItem> item = mediaRepository.findById(id);
        if (item.isPresent()) {
            if (!mediaDbService.processItemVolume(item.get())) {
                return "no volume"; // nothing changed: do not restart the stream
            }
            RotationItem currentItem = rotationRepository.getItemForTime(ChronoService.getTimePointer());
            if (currentItem.getMediaItem().fullpath.equals(item.get().fullpath)) {
                //currentItem.setMediaItem(item.get());
                rotationRepository.updateMediaItem(currentItem, item.get());
                CompletableFuture.runAsync(() -> {
                    try {
                        Thread.sleep(2000);
                        restRequestService.requestRestart();
                    } catch (Exception e) {
                        log.info(e.getMessage());
                    }
                });
            }
        }
        return "ok";
    }

    @PostMapping("/sync/{startMs}")
    public String sync(@PathVariable("startMs") long startMs, @RequestBody String fullpath) {
        return rotationRepository.syncTo(fullpath, startMs) ? "ok" : "unknown";
    }

    @GetMapping("/now")
    public RotationItem now() {

        RotationItem item = rotationRepository.getItemForTime(ChronoService.getTimePointer());
        if (item.getMediaItem().volume == null && volumeFixAttempted.add(item.getMediaItem().getId())) {
            // once per item and asynchronously: never block /now, never loop on files without a measurable volume
            log.info("Fixing volume initiated");
            Long id = item.getMediaItem().getId();
            CompletableFuture.runAsync(() -> restRequestService.requestVolumeFix(id));
        }
        decorateTitle(item.getMediaItem());
        return item;
    }

    @GetMapping("/next")
    public RotationItem next() {
        RotationItem item = rotationRepository.getItemForTime(ChronoService.getTimePointer());
        ChronoService.pushOffset(item.timeLeft);
        restRequestService.requestRestart();
        return rotationRepository.getItemForTime(ChronoService.getTimePointer());
    }

    @PostMapping("/search/")
    public List<MediaItem> search(@RequestBody String item, @RequestParam(value = "internal", required = false) String internal) {
        List<MediaItem> found = searchAll(item, 30);
        if (internal == null) {
            // a chat !search: let the overlay show the results (numbered like the chat, from 0)
            List<Map<String, Object>> shown = new ArrayList<>();
            for (MediaItem m : found.subList(0, Math.min(8, found.size()))) {
                shown.add(Map.of("title", displayTitle(m), "request", m.getFullpath().contains("RequestOnly")));
            }
            lastSearch = Map.of("at", System.currentTimeMillis(), "query", item.trim(), "total", found.size(), "results", shown);
        }
        return found;
    }

    private volatile Map<String, Object> lastSearch = Map.of("at", 0L, "query", "", "total", 0, "results", List.of());

    /** Polled by the overlay: last !list and !search (also proves that the overlay is alive). */
    @GetMapping("/overlay/state")
    public Map<String, Object> overlayState() {
        overlaySeenAt = System.currentTimeMillis();
        return Map.of("list_at", listRequestedAt, "search", lastSearch);
    }

    /** Asked by live-service: is the overlay drawing chat results, so the text version can be skipped? */
    @GetMapping("/overlay/alive")
    public Map<String, Boolean> overlayAlive() {
        return Map.of("alive", System.currentTimeMillis() - overlaySeenAt < 10_000);
    }

    @PostMapping("/enqueue/")
    public MediaItem enqueue(@RequestBody String item) {
        return enqueueItem(item, false);
    }

    @PostMapping("/enqueue/next/")
    public MediaItem enqueueNext(@RequestBody String item) {
        return enqueueItem(item, true);
    }

    private volatile long listRequestedAt = 0;
    private volatile long overlaySeenAt = 0;

    /**
     * Up next. Internal callers (stream-service, dashboard, overlay) pass ?internal=1. A call
     * without it is a user's !list from chat: the overlay is told to animate the list, and while
     * the overlay is alive the chat bot gets an empty answer so it does not also print 30 lines.
     */
    @GetMapping("/list")
    public List<MediaItem> list(@RequestParam(value = "internal", required = false) String internal) {
        List<MediaItem> items = listNext(30);
        items.forEach(this::decorateTitle);
        if (internal == null) {
            listRequestedAt = System.currentTimeMillis();
            if (System.currentTimeMillis() - overlaySeenAt < 10_000) {
                return Collections.emptyList();
            }
        }
        return items;
    }

    /** Polled by the overlay: when the last !list happened (and proof that the overlay is alive). */
    @GetMapping("/list/requested")
    public Map<String, Long> listRequested() {
        overlaySeenAt = System.currentTimeMillis();
        return Map.of("at", listRequestedAt);
    }

    /** Stream title "≫　Artist／Song" from the path below ___UNDERGROUND/ (as in the deployed build). */
    private void decorateTitle(MediaItem mi) {
        if (mi == null || mi.title == null || mi.fullpath == null) {
            return;
        }
        mi.setTitle(displayTitle(mi));
    }

    /** "≫　Artist／Song" for ___UNDERGROUND files, the plain title otherwise (no mutation). */
    private static String displayTitle(MediaItem mi) {
        if (mi.title == null || mi.fullpath == null) {
            return String.valueOf(mi.title);
        }
        if (!mi.title.startsWith("\u226b\u3000") && mi.fullpath.contains("___UNDERGROUND/")) {
            String name = mi.fullpath.split("___UNDERGROUND/")[1];
            if (name.lastIndexOf('.') > 0) {
                name = name.substring(0, name.lastIndexOf('.'));
            }
            return "\u226b\u3000" + name.replace("/", "\uff0f");
        }
        return mi.title;
    }

    private MediaItem enqueueItem(String item, boolean next) {
        Optional<MediaItem> itemFound = searchAll(item, 1).stream().findFirst();

        if (itemFound.isPresent()) {
            QueuedItem queued = new QueuedItem(itemFound.get());
            rotationRepository.putNextQueued(queued, next);
        }

        return itemFound.orElse(null);
    }


    LevenshteinDistance levenshteinDistance = new LevenshteinDistance();

    private List<MediaItem> searchAll(String query, int limit) {
        var items = mediaRepository.findAll().stream()
                .filter(i -> i.getFullpath() != null && !MediaDbService.isJunkName(new File(i.getFullpath()).getName()));
        Stream<MediaItem> filtered;

        if (query.length() > 2 && query.startsWith("/") && query.endsWith("/")) {
            java.util.regex.Pattern p;
            try {
                p = java.util.regex.Pattern.compile(query.substring(1, query.length() - 1), java.util.regex.Pattern.CASE_INSENSITIVE);
            } catch (java.util.regex.PatternSyntaxException e) {
                return Collections.emptyList();
            }
            filtered = items.filter(i -> p.matcher(i.getFullpath()
                    .concat(" ")
                    .concat(i.getTags().stream().collect(Collectors.joining(" ")))).find());
        } else {
            filtered = items.filter(i -> Arrays.stream(query.split("[ ]"))
                    .allMatch(token -> i.getFullpath()
                            .concat(i.getTags()
                                    .stream()
                                    .collect(Collectors.joining(" ")))
                            .toLowerCase()
                            .contains(token.toLowerCase())));
        }
        return filtered
                .sorted(Comparator.comparingDouble(i -> levenshteinDistance.apply(((MediaItem) i).getTitle().toLowerCase(), query.toLowerCase())))
                .limit(limit)
                .collect(Collectors.toList());
    }

    private List<MediaItem> listNext(int limit) {
        return rotationRepository.listNext(ChronoService.getTimePointer(), limit);
    }


    @PostMapping("/tags/add")
    public String tagsAdd(@RequestBody String tag) {
        RotationItem item = rotationRepository.getItemForTime(ChronoService.getTimePointer());
        mediaDbService.addTag(item.getMediaItem(), tag);
        return "ok";
    }

    @PostMapping("/tags/remove")
    public String tagsRemove(@RequestBody String tag) {
        RotationItem item = rotationRepository.getItemForTime(ChronoService.getTimePointer());
        mediaDbService.removeTag(item.getMediaItem(), tag);
        return "ok";
    }

    @GetMapping("/tags/list")
    public String[] tagsList() {
        RotationItem item = rotationRepository.getItemForTime(ChronoService.getTimePointer());
        if (item.getMediaItem().getTags() != null) {
            return item.getMediaItem().getTags().toArray(String[]::new);
        } else {
            return new String[]{};
        }
    }

}
