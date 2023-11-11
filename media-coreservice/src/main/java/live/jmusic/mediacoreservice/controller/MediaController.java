package live.jmusic.mediacoreservice.controller;

import live.jmusic.shared.model.RotationItem;
import live.jmusic.mediacoreservice.repository.RotationRepository;
import live.jmusic.mediacoreservice.repository.MediaRepository;
import live.jmusic.shared.model.MediaItem;
import live.jmusic.mediacoreservice.service.ChronoService;
import live.jmusic.shared.rest.RestRequestService;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/media")
public class MediaController {

    @Autowired
    RotationRepository rotationRepository;

    @Autowired
    MediaRepository mediaRepository;

    @Autowired
    RestRequestService restRequestService;

    @GetMapping("/now")
    public RotationItem now() {

        return rotationRepository.getItemForTime(ChronoService.getTimePointer());
    }

    @GetMapping("/next")
    public RotationItem next() {
        RotationItem item = rotationRepository.getItemForTime(ChronoService.getTimePointer());
        ChronoService.pushOffset(item.timeLeft);
        restRequestService.requestRestart();
        return rotationRepository.getItemForTime(ChronoService.getTimePointer());
    }

    @GetMapping("/search/{item}")
    public List<MediaItem> search(@PathVariable("item") String item) {
        return searchAll(item, 15);
    }

    @GetMapping("/enqueue/{item}")
    public MediaItem enqueue(@PathVariable("item") String item) {
        Optional<MediaItem> itemFound = searchAll(item, 1).stream().findFirst();

        if(itemFound.isPresent()) {
            rotationRepository.putNext(itemFound.get());
        }

        return itemFound.orElse(null);
    }



    LevenshteinDistance levenshteinDistance = new LevenshteinDistance();
    private List<MediaItem> searchAll(String query, int limit) {
        return mediaRepository
                .findAll()
                .stream()
                .filter(i -> Arrays.stream(query.split("\\W")).allMatch(token -> i.getFullpath().toLowerCase().contains(token.toLowerCase())))
                .sorted(
                        Comparator.comparingDouble(i -> levenshteinDistance.apply(((MediaItem)i).fullpath.toLowerCase(), query.toLowerCase()))
                )
                .limit(limit)
                .collect(Collectors.toList());
    }
}
