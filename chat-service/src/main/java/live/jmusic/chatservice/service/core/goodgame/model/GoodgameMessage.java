package live.jmusic.chatservice.service.core.goodgame.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GoodgameMessage {
    public String type;
    public GoodgameMessageData data;
}
