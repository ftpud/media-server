package live.jmusic.chatservice.service.core.goodgame.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GoodgameMessageData {
    public String channel_id;
    public int user_id;
    public String user_name;
    public int user_rights;
    public int premium;
    public int staff;
    public String color;
    public String icon;
    public String role;
    public int mobile;
    public int payments;
    public int gg_plus_tier;
    public int isStatus;
    public long message_id;
    public int timestamp;
    public String text;
    public int regtime;
}
