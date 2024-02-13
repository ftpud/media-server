package live.jmusic.chatservice.service.core.twitch;

import live.jmusic.chatservice.service.ChatMessageHandler;
import live.jmusic.chatservice.service.core.ChatHandlerBase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

@Slf4j
public class TwitchWebsocketHandler extends ChatHandlerBase {

    ChatMessageHandler chatMessageHandler;

    String channelId;
    String username;
    String password;

    public TwitchWebsocketHandler(String username, String password, String channelId, ChatMessageHandler chatMessageHandler) {
        this.channelId = channelId;
        this.password = password;
        this.username = username;
        this.chatMessageHandler = chatMessageHandler;
    }

    @Override
    public void handleMessage(WebSocketSession webSocketSession, WebSocketMessage<?> webSocketMessage) throws Exception {
        String payload = webSocketMessage.getPayload().toString();
        String[] split = payload.split(" ");

        if (payload.startsWith("PING")) {
            webSocketSession.sendMessage(new TextMessage("PONG"));
        } else if (split.length > 2 && "PRIVMSG".equals(split[1])) {
            int exclamationPointPosition = split[0].indexOf("!");
            String username = split[0].substring(1, exclamationPointPosition);
            int secondColonPosition = payload.indexOf(':', 1);
            String message = payload.substring(secondColonPosition + 1);
            log.info("Msg: {} --> {}", username, message);
            chatMessageHandler.handleChatMessage(message, username);
        }

    }

    @Override
    public void afterConnectionEstablished(WebSocketSession webSocketSession) throws Exception {
        super.afterConnectionEstablished(webSocketSession);

        webSocketSession.sendMessage(new TextMessage("PASS " + password));
        webSocketSession.sendMessage(new TextMessage("NICK " + username));
        webSocketSession.sendMessage(new TextMessage("JOIN #" + channelId));
    }

    public void sendPing() {
        try {
            webSocketSession.sendMessage(new TextMessage("PING"));
        } catch (IOException e) {
            log.info(e.getMessage());
        }
    }

}
