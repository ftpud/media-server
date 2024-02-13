package live.jmusic.chatservice.service.core.sc2tv;

import com.fasterxml.jackson.databind.ObjectMapper;
import live.jmusic.chatservice.model.ChatMessageRoot;
import live.jmusic.chatservice.service.ChatMessageHandler;
import live.jmusic.chatservice.service.core.ChatHandlerBase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class Sc2tvWebsocketHandler extends ChatHandlerBase {

    final ChatMessageHandler chatMessageHandler;

    final String channelId;

    public Sc2tvWebsocketHandler(String channelId, ChatMessageHandler chatMessageHandler) {
        this.chatMessageHandler = chatMessageHandler;
        this.channelId = channelId;
    }

    final ObjectMapper mapper = new ObjectMapper();


    final String msgRegex = "^\\d+\\[\\\"(.+)\\\",(\\{.+\\})\\]$";
    final Pattern msgPattern = Pattern.compile(msgRegex);

    @Override
    public void handleMessage(WebSocketSession webSocketSession, WebSocketMessage<?> webSocketMessage) throws Exception {
        String payload = webSocketMessage.getPayload().toString();
        if (payload.equals("3")) {
            // ping
        } else if (payload.startsWith("0")) {
            // handshake
            log.debug(webSocketMessage.getPayload().toString());
            webSocketSession.sendMessage(new TextMessage("422[\"/chat/join\",{\"channel\":\"" + channelId + "\"}]"));
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


    public void sendPing() {
        try {
            webSocketSession.sendMessage(new TextMessage("2"));
        } catch (IOException e) {
            log.info(e.getMessage());
        }
    }

}
