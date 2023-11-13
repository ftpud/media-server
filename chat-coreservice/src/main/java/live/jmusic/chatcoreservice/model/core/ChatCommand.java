package live.jmusic.chatcoreservice.model.core;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;

@Data
@AllArgsConstructor
public class ChatCommand {
    Consumer<Matcher> action;
    Function<String, Boolean> privCheck;
}
