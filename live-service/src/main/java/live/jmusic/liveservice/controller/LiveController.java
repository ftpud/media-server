package live.jmusic.liveservice.controller;

import live.jmusic.liveservice.service.LiveService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/live")
public class LiveController {

    @Autowired
    LiveService liveService;

    @Value("${media.core.service.uri:http://localhost:8084}")
    String mediaServiceUri;

    /** A chat !search result block: every line looks like "[  3] title". */
    static boolean isSearchBlock(String message) {
        String[] lines = message.trim().split("\n");
        if (lines.length == 0 || lines[0].isEmpty()) {
            return false;
        }
        for (String l : lines) {
            if (!l.matches("^\\[\\s*\\d+\\] .*")) {
                return false;
            }
        }
        return true;
    }

    /** The overlay shows search results itself; skip the text version while it is alive. */
    private boolean overlayAlive() {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(mediaServiceUri + "/media/overlay/alive").openConnection();
            c.setConnectTimeout(500);
            c.setReadTimeout(1000);
            String body = new String(c.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return body.contains("true");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean drop(String message) {
        return isSearchBlock(message) && overlayAlive();
    }

    @PostMapping("/message/{slot}")
    public String message(@PathVariable("slot") String slot, @RequestBody String message) throws IOException {
        if (drop(message)) {
            return "ok";
        }
        liveService.putMessage(slot, message);
        return "ok";
    }

    @PostMapping("/message")
    public String message(@RequestBody String message) throws IOException {
        if (drop(message)) {
            return "ok";
        }
        liveService.putMessage(message);
        return "ok";
    }
}
