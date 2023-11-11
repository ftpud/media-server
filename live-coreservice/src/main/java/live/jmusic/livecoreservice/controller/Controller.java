package live.jmusic.livecoreservice.controller;

import live.jmusic.livecoreservice.service.LiveService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/live")
public class Controller {

    @Autowired
    LiveService liveService;

    @PostMapping("/message/{slot}")
    public String message(@PathVariable("slot") String slot, @RequestBody String message) throws IOException {
        liveService.putMessage(slot, message);
        return "ok";
    }

    @PostMapping("/message")
    public String message(@RequestBody String message) throws IOException {
        liveService.putMessage(message);
        return "ok";
    }
}
