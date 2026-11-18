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

    public synchronized void updateMediaItemsList() {
        rotationList = mediaRepository
                .findAll()
                .stream()
                .filter(mediaItem -> mediaItem.getFullpath().contains("___UNDERGROUND"))
                .filter(RotationRepository::isPlayable)
                .collect(Collectors.toList());

        Collections.shuffle(rotationList);
    }

    /** Adds rotation-eligible items from the database that are not in the rotation yet (no reshuffle). */
    public synchronized void appendNewItems() {
        java.util.Set<String> known = rotationList.stream().map(MediaItem::getFullpath).collect(Collectors.toSet());
        List<MediaItem> fresh = mediaRepository.findAll().stream()
                .filter(m -> m.getFullpath().contains("___UNDERGROUND") && !known.contains(m.getFullpath()))
                .filter(RotationRepository::isPlayable)
                .collect(Collectors.toList());
        Collections.shuffle(fresh);
        rotationList.addAll(fresh);
    }

    public synchronized void updateMediaItem(RotationItem source, MediaItem target) {
        if (source.getRotationPosition() < rotationList.size()) rotationList.set(source.getRotationPosition(), target);
    }

    public synchronized RotationItem getItemForTime(Long time) {
        return getItemForTime(time, true);
    }

    private RotationItem getItemForTime(Long time, boolean allowWrap) {
        long passedTime = 0;

        for (int i = 0; i < rotationList.size(); i++) {
            if (rotationList.get(i).length != null && passedTime + rotationList.get(i).length > time) {
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
            passedTime += rotationList.get(i).getLength() == null ? 0 : rotationList.get(i).getLength();
        }

        if (!allowWrap) {
            // empty library or every file missing: nothing playable (avoid infinite recursion)
            throw new IllegalStateException("No playable items in rotation");
        }
        ChronoService.resetTime();

        rotationList.removeIf(i -> i instanceof QueuedItem);
        Collections.shuffle(rotationList);

        return getItemForTime(0L, false);
    }

    /** A real video with a known duration (length 0 = ffprobe failed; such entries can never play). */
    public static boolean isPlayable(MediaItem m) {
        return m != null && m.getFullpath() != null && m.getLength() != null && m.getLength() > 0
                && !live.jmusic.mediaservice.service.MediaDbService.isJunkName(new File(m.getFullpath()).getName());
    }

    /** The current item followed by the next playable ones (what mediad will play). */
    public synchronized List<MediaItem> listNext(long time, int number) {
        RotationItem item = getItemForTime(time);
        List<MediaItem> response = new ArrayList<>();
        response.add(rotationList.get(item.rotationPosition));
        for (int i = item.rotationPosition + 1; i < rotationList.size() && response.size() < number; i++) {
            if (isPlayable(rotationList.get(i))) {
                response.add(rotationList.get(i));
            }
        }
        return response;
    }

    /**
     * Re-anchors the time pointer so that the item with this path starts "now" (plus startMs).
     * Used when the external player reports which track it actually started. If the same file
     * appears several times (queued copies) the occurrence closest to the current pointer wins.
     */
    public synchronized boolean syncTo(String fullpath, long startMs) {
        long pointer = ChronoService.getTimePointer();
        long acc = 0, best = -1, bestDist = Long.MAX_VALUE;
        for (MediaItem m : rotationList) {
            if (m.getFullpath().equals(fullpath)) {
                long d = Math.abs(acc - pointer);
                if (d < bestDist) { bestDist = d; best = acc; }
            }
            acc += m.getLength() == null ? 0 : m.getLength();
        }
        if (best < 0) return false;
        ChronoService.setPointer(best + startMs);
        return true;
    }

    public synchronized void putNextQueued(MediaItem mediaItem, boolean putFirst) {
        RotationItem item = getItemForTime(ChronoService.getTimePointer());

        int pos = 1;
        if(!putFirst) {
            while (item.rotationPosition + pos < rotationList.size()
                    && rotationList.get(item.rotationPosition + pos) instanceof QueuedItem) {
                pos++;
            }
        }
        rotationList.add(item.rotationPosition + pos, mediaItem);
    }
}
