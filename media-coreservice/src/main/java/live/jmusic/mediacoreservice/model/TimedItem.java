package live.jmusic.mediacoreservice.model;

import live.jmusic.mediacoreservice.repository.model.MediaItem;
import lombok.Data;

@Data
public class TimedItem {
    public Long currentTime;
    public Long timeLeft;
    public MediaItem mediaItem;
}
