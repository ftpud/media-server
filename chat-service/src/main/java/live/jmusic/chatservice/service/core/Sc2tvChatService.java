package live.jmusic.chatservice.service.core;

import live.jmusic.chatservice.service.ChatMessageHandler;
import live.jmusic.shared.rest.RestRequestService;
import live.jmusic.shared.util.ConsoleUtilService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

@Slf4j
@Service
public class Sc2tvChatService {

    @Value("${media.chat.sc2tv.ws.url}")
    String sc2tvUrl;

    @Autowired
    RestRequestService restRequestService;

    @Autowired
    ConsoleUtilService consoleUtilService;

    public void run() {
        StandardWebSocketClient client = new StandardWebSocketClient();

        while (true) {
            log.info("Connecting to {}", sc2tvUrl);
            Sc2tvWebsocketHandler handler = new Sc2tvWebsocketHandler(
                    new ChatMessageHandler(restRequestService, consoleUtilService)
            );
            client.doHandshake(handler, sc2tvUrl);

            while (handler.isAlive) {
                try {
                    Thread.sleep(10000);
                    handler.sendPing();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }
}
