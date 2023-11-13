package live.jmusic.streamservice.model;

import lombok.Data;

@Data
public class TimedItem {
    public Long currentTime;
    public Long timeLeft;
    public MediaItem mediaItem;
}
