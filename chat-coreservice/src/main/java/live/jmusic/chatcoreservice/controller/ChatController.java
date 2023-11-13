package live.jmusic.chatcoreservice.controller;

import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/chat")
public class ChatController {

    @PostMapping("/message")
    public String message(@RequestBody String message) throws IOException {

        return "ok";
    }
}
