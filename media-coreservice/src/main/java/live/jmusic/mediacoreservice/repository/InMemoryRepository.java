package live.jmusic.mediacoreservice.repository;

import live.jmusic.mediacoreservice.model.TimedItem;
import live.jmusic.mediacoreservice.repository.model.MediaItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class InMemoryRepository {

    @Autowired
    MediaRepository mediaRepository;

    private List<MediaItem> mediaItemList = new ArrayList<>();

    public void updateMediaItemsList() {
        mediaItemList = mediaRepository.findAll();
        Collections.shuffle(mediaItemList);
    }

    public TimedItem getItemForTime(Long time) {
        int passedTime = 0;

        for (int i = 0; i < mediaItemList.size(); i++) {
            if (passedTime + mediaItemList.get(i).length > time) {
                TimedItem timedItem = new TimedItem();
                timedItem.setMediaItem(mediaItemList.get(i));
                timedItem.setCurrentTime(time - passedTime);
                timedItem.setTimeLeft(mediaItemList.get(i).length - (time - passedTime));
                return timedItem;
            }
            passedTime += mediaItemList.get(i).length;
        }

        throw new RuntimeException("Too much time");
    }
}
