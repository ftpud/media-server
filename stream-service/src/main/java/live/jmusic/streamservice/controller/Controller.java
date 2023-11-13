package live.jmusic.streamservice.controller;

import live.jmusic.streamservice.service.StreamService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/media")
public class Controller {

    @Autowired
    StreamService streamService;

    @GetMapping("/restart")
    public String restart() throws IOException {
        streamService.restart();
        return "ok";
    }
}
