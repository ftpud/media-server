package live.jmusic.mediaservice.repository;

import live.jmusic.shared.model.RotationItem;
import live.jmusic.shared.model.MediaItem;
import live.jmusic.mediaservice.service.ChronoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class RotationRepository {

    @Autowired
    MediaRepository mediaRepository;

    private List<MediaItem> rotationList = new ArrayList<>();

    public void updateMediaItemsList() {
        rotationList = mediaRepository
                .findAll()
                .stream()
                .filter(mediaItem -> !mediaItem.getFullpath().contains("RequestOnly"))
                .collect(Collectors.toList());

        Collections.shuffle(rotationList);
    }

    public void updateMediaItem(RotationItem source, MediaItem target) {
        rotationList.set(source.getRotationPosition(), target);
    }

    public RotationItem getItemForTime(Long time) {
        int passedTime = 0;

        for (int i = 0; i < rotationList.size(); i++) {
            if (passedTime + rotationList.get(i).length > time) {
                RotationItem rotationItem = new RotationItem();
                rotationItem.setMediaItem(rotationList.get(i));
                rotationItem.setCurrentTime(time - passedTime);
                rotationItem.setTimeLeft(rotationList.get(i).length - (time - passedTime));
                rotationItem.setRotationPosition(i);
                return rotationItem;
            }
            passedTime += rotationList.get(i).length;
        }

        throw new RuntimeException("Too much time");
    }


    public void putNext(MediaItem mediaItem) {
        RotationItem item = getItemForTime(ChronoService.getTimePointer());
        rotationList.add(item.rotationPosition + 1, mediaItem);
    }
}
