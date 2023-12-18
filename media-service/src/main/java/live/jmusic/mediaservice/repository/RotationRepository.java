package live.jmusic.mediaservice.repository;

import live.jmusic.shared.model.QueuedItem;
import live.jmusic.shared.model.RotationItem;
import live.jmusic.shared.model.MediaItem;
import live.jmusic.mediaservice.service.ChronoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
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

                if((new File(rotationItem.getMediaItem().fullpath)).exists()) {
                    return rotationItem;
                } else {
                    rotationList.get(i).setLength(0L);
                }
            }
            passedTime += rotationList.get(i).getLength();
        }

        ChronoService.resetTime();

        rotationList.removeIf(i -> i instanceof QueuedItem);
        Collections.shuffle(rotationList);

        return getItemForTime(0L);
    }

    public List<MediaItem> listNext(long time, int number) {
        RotationItem item = getItemForTime(time);
        List<MediaItem> response = new ArrayList<>();
        for(int i=0; i<number;i++) {
            response.add(rotationList.get(item.rotationPosition + i));
        }
        return response;
    }

    public void putNextQueued(MediaItem mediaItem) {
        RotationItem item = getItemForTime(ChronoService.getTimePointer());

        int pos = 1;
        while(rotationList.get(item.rotationPosition + pos) instanceof QueuedItem) {
            pos++;
        }
        rotationList.add(item.rotationPosition + pos, mediaItem);
    }
}
