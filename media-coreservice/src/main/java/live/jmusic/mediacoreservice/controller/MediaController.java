package live.jmusic.mediacoreservice.controller;

import live.jmusic.mediacoreservice.model.TimedItem;
import live.jmusic.mediacoreservice.repository.InMemoryRepository;
import live.jmusic.mediacoreservice.service.ChronoService;
import live.jmusic.shared.rest.RestClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/media")
public class MediaController {

    @Autowired
    InMemoryRepository inMemoryRepository;

    @GetMapping("/now")
    public TimedItem now() {

        return inMemoryRepository.getItemForTime(ChronoService.getTimePointer());
    }

    @GetMapping("/next")
    public TimedItem next() {
        TimedItem item = inMemoryRepository.getItemForTime(ChronoService.getTimePointer());
        ChronoService.pushOffset(item.timeLeft);
        RestClient.recoverableRequest("http://localhost:8088/media/restart", String.class, s -> {} );
        return inMemoryRepository.getItemForTime(ChronoService.getTimePointer());
    }
}
