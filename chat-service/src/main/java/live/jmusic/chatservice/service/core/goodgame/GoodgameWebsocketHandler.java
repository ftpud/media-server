package live.jmusic.chatservice.service.core.goodgame;

import com.fasterxml.jackson.databind.ObjectMapper;
import live.jmusic.chatservice.service.ChatMessageHandler;
import live.jmusic.chatservice.service.core.ChatHandlerBase;
import live.jmusic.chatservice.service.core.goodgame.model.GoodgameMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

@Slf4j
public class GoodgameWebsocketHandler extends ChatHandlerBase {

    ChatMessageHandler chatMessageHandler;

    String channelId;

    public GoodgameWebsocketHandler(String channelId, ChatMessageHandler chatMessageHandler) {
        this.channelId = channelId;
        this.chatMessageHandler = chatMessageHandler;
    }

    ObjectMapper mapper = new ObjectMapper();

    @Override
    public void handleMessage(WebSocketSession webSocketSession, WebSocketMessage<?> webSocketMessage) throws Exception {
        String payload = webSocketMessage.getPayload().toString();
        GoodgameMessage msg = mapper.readValue(payload, GoodgameMessage.class);

        if ("welcome".equals(msg.getType())) {
            // handshake
            log.info("GoodGame handshake received. Connected successfully.");
            webSocketSession.sendMessage(new TextMessage("{\"type\": \"join\",\"data\": {\"channel_id\": \"" + channelId + "\", \"hidden\": false}}"));
        } else if ("message".equals(msg.getType())) {
            log.info("Msg: {} --> {}", msg.getData().getUser_name(), msg.getData().getText());
            chatMessageHandler.handleChatMessage(msg.getData().getText(), msg.getData().getUser_name());
        }

    }

    public void sendPing() {
        try {
            webSocketSession.sendMessage(new TextMessage("{\"type\":\"ping\",\"data\":{}}"));
        } catch (IOException e) {
            log.info(e.getMessage());
        }
    }

}
