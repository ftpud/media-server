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
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.hibernate.Hibernate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

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
        if (time.matches("[0-9][0-9]:[0-9][0-9]")) {
            time = "00:" + time;
        }

        try {
            LocalTime t = LocalTime.parse(time);
            Long fwdTime = t.toSecondOfDay() * 1000L;
            RotationItem item = rotationRepository.getItemForTime(ChronoService.getTimePointer());
            ChronoService.pushOffset(-item.currentTime + fwdTime);
            restRequestService.requestRestart();
            return "ok";
        } catch (Exception e) {
            log.info(e.toString());
            return "err";
        }
    }

    @GetMapping("/fixVolume/{id}")
    public String fixVolume(@PathVariable("id") Long id) {
        Optional<MediaItem> item = mediaRepository.findById(id);
        if (item.isPresent()) {
            mediaDbService.processItemVolume(item.get());
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

    @GetMapping("/now")
    public RotationItem now() {

        RotationItem item = rotationRepository.getItemForTime(ChronoService.getTimePointer());
        if (item.getMediaItem().volume == null) {
            log.info("Fixing volume initiated");
            restRequestService.requestVolumeFix(item.getMediaItem().getId());
        }
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
    public List<MediaItem> search(@RequestBody String item) {
        return searchAll(item, 30);
    }

    @PostMapping("/enqueue/")
    public MediaItem enqueue(@RequestBody String item) {
        return enqueueItem(item, false);
    }

    @PostMapping("/enqueue/next/")
    public MediaItem enqueueNext(@RequestBody String item) {
        return enqueueItem(item, true);
    }

    @GetMapping("/list")
    public List<MediaItem> list() {
        return listNext(30);
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
        return mediaRepository
                .findAll()
                .stream()
                .filter(i -> Arrays.stream(query.split("[ ]")).allMatch(token -> i.getFullpath().concat(i.getTags().stream().collect(Collectors.joining(" "))).toLowerCase().contains(token.toLowerCase())))
                .sorted(
                        Comparator.comparingDouble(i -> levenshteinDistance.apply(((MediaItem) i).getTitle().toLowerCase(), query.toLowerCase()))
                )
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
