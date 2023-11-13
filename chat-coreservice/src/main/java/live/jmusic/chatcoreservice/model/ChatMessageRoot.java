package live.jmusic.chatcoreservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ChatMessageRoot {
    public int id;
    public String channel;
    public From from;
    @JsonProperty("to")
    public Object myto;
    public String text;
    public String type;
    public int time;
    public Store store;
    public int parentId;
    public boolean anonymous;
}
