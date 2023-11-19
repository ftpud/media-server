package live.jmusic.mediaservice.controller;

import live.jmusic.mediaservice.service.MediaDbService;
import live.jmusic.shared.model.RotationItem;
import live.jmusic.mediaservice.repository.RotationRepository;
import live.jmusic.mediaservice.repository.MediaRepository;
import live.jmusic.shared.model.MediaItem;
import live.jmusic.mediaservice.service.ChronoService;
import live.jmusic.shared.rest.RestRequestService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
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

    @GetMapping("/processing/trigger")
    public String triggerProcessing() {
        mediaDbService.startFullProcessing();
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
        Optional<MediaItem> itemFound = searchAll(item, 1).stream().findFirst();

        if (itemFound.isPresent()) {
            rotationRepository.putNext(itemFound.get());
        }

        return itemFound.orElse(null);
    }


    LevenshteinDistance levenshteinDistance = new LevenshteinDistance();

    private List<MediaItem> searchAll(String query, int limit) {
        return mediaRepository
                .findAll()
                .stream()
                .filter(i -> Arrays.stream(query.split("[ ]")).allMatch(token -> i.getFullpath().toLowerCase().contains(token.toLowerCase())))
                .sorted(
                        Comparator.comparingDouble(i -> levenshteinDistance.apply(((MediaItem) i).fullpath.toLowerCase(), query.toLowerCase()))
                )
                .limit(limit)
                .collect(Collectors.toList());
    }
}
