package live.jmusic.shared.model;

import lombok.Data;

@Data
public class RotationItem {
    public Long currentTime;
    public Long timeLeft;
    public MediaItem mediaItem;
    public int rotationPosition;
}
