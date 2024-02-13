package live.jmusic.chatservice.service.core;

import org.apache.commons.lang3.NotImplementedException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

public class ChatHandlerBase implements WebSocketHandler {

    boolean isAlive = true;

    protected WebSocketSession webSocketSession;

    @Override
    public void afterConnectionEstablished(WebSocketSession webSocketSession) throws Exception {
        this.webSocketSession = webSocketSession;
    }

    @Override
    public void handleMessage(WebSocketSession webSocketSession, WebSocketMessage<?> webSocketMessage) throws Exception {

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

    public boolean isAlive() {
        return isAlive;
    }


    public void sendPing() {
        throw new NotImplementedException();
    }
}
