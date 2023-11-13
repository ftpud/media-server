package live.jmusic.chatservice.service.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import live.jmusic.chatservice.model.ChatMessageRoot;
import live.jmusic.chatservice.service.ChatMessageHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.*;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class Sc2tvWebsocketHandler implements WebSocketHandler {

    ChatMessageHandler chatMessageHandler;

    public Sc2tvWebsocketHandler(ChatMessageHandler chatMessageHandler) {
        this.chatMessageHandler = chatMessageHandler;
    }

    boolean isAlive = true;

    ObjectMapper mapper = new ObjectMapper();

    private WebSocketSession webSocketSession;

    @Override
    public void afterConnectionEstablished(WebSocketSession webSocketSession) throws Exception {
        this.webSocketSession = webSocketSession;
    }

    String msgRegex = "^\\d+\\[\\\"(.+)\\\",(\\{.+\\})\\]$";
    Pattern msgPattern = Pattern.compile(msgRegex);

    @Override
    public void handleMessage(WebSocketSession webSocketSession, WebSocketMessage<?> webSocketMessage) throws Exception {
        String payload = webSocketMessage.getPayload().toString();
        if (payload.equals("3")) {
            // ping
        } else if (payload.startsWith("0")) {
            // handshake
            log.debug(webSocketMessage.getPayload().toString());
            webSocketSession.sendMessage(new TextMessage("422[\"/chat/join\",{\"channel\":\"stream/56592\"}]"));
        } else if (payload.startsWith("42")) {
            log.debug(webSocketMessage.getPayload().toString());
            Matcher m = msgPattern.matcher(payload);
            m.find();
            if (m.groupCount() == 2 && m.group(1).equals("/chat/message")) {
                ChatMessageRoot response = mapper.readValue(m.group(2), ChatMessageRoot.class);
                log.info("Msg: {} --> {}", response.from.name, response.text);
                chatMessageHandler.handleChatMessage(response.text, response.from.name);
            }

        }
    }

    @Override
    public void handleTransportError(WebSocketSession webSocketSession, Throwable throwable) throws Exception {

    }

    @Override
    public void afterConnectionClosed(WebSocketSession webSocketSession, CloseStatus closeStatus) throws Exception {
        isAlive = false;
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    public boolean getIsAlive() {
        return isAlive;
    }

    public void sendPing() {
        try {
            webSocketSession.sendMessage(new TextMessage("2"));
        } catch (IOException e) {
            log.info(e.getMessage());
        }
    }
}
