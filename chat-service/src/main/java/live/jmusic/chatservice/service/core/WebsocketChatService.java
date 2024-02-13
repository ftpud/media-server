package live.jmusic.chatservice.service.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

@Slf4j
@Service
public class WebsocketChatService {

    public void run(String url, ChatHandlerBase handler) {
        StandardWebSocketClient client = new StandardWebSocketClient();

        while (true) {
            log.info("Connecting to {}", url);

            client.doHandshake(handler, url);

            while (handler.isAlive()) {
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
