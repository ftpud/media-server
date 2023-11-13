package live.jmusic.chatservice.service;

import live.jmusic.chatservice.model.core.ChatCommand;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatMessageHandlerBase {

    Map<String, ChatCommand> chatEventMap = new HashMap<>();

    public void registerEvent(String pattern, Consumer<Matcher> action, Function<String, Boolean> privCheck) {
        ChatCommand command = new ChatCommand(action, privCheck);
        chatEventMap.put(pattern, command);
    }

    public void handleChatMessage(String payload, String sender) {
        chatEventMap.entrySet().stream().forEach(entry ->
        {
            Pattern p = Pattern.compile(entry.getKey());
            Matcher m = p.matcher(payload);
            if (m.find() && entry.getValue().getPrivCheck().apply(sender)) {
                entry.getValue().getAction().accept(m);
            }
        });
    }
}
