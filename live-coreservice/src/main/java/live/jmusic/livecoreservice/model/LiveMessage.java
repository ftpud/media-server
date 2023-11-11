package live.jmusic.livecoreservice.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LiveMessage {
    String slot;
    String text;
    int timeout;
}
