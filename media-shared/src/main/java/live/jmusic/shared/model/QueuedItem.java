package live.jmusic.shared.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class QueuedItem extends MediaItem {

    boolean isQueued;
    public QueuedItem(MediaItem mediaItem) {
        id = mediaItem.getId();
        fullpath = mediaItem.getFullpath();
        title = mediaItem.getTitle();
        tags = mediaItem.getTags();
        volume = mediaItem.getVolume();
        length = mediaItem.length;
        isProcessed = mediaItem.isProcessed();
        isQueued = true;
    }
}
