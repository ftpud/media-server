package live.jmusic.livecoreservice.model;

import lombok.Data;

@Data
public class TimedItem {
    public Long currentTime;
    public Long timeLeft;
    public MediaItem mediaItem;
}
