package live.jmusic.mediacoreservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import live.jmusic.mediacoreservice.repository.RotationRepository;
import live.jmusic.mediacoreservice.repository.MediaRepository;
import live.jmusic.shared.model.MediaItem;
import live.jmusic.mediacoreservice.util.ProcessUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.time.Duration;
import java.util.Map;

@Service
@Slf4j
public class MediaDbService {

    @Autowired
    MediaRepository mediaRepository;

    @Autowired
    RotationRepository inMemoryRepository;

    @Value("${media.volume.app}")
    private String volumeApp;

    @Value("${media.library.path}")
    private String mediaLibraryPath;
    @Value("${media.ffprobe.path}")
    private String ffprobePath;

    @Value("${media.library.supported.ext}")
    private String[] supportedExtensions;


    @PostConstruct
    public void init() {
        log.info("Library processing started");


        FileUtils.listFiles(new File(mediaLibraryPath), supportedExtensions, true).forEach(
                this::processFile
        );

        log.info("All files processed");
        inMemoryRepository.updateMediaItemsList();
    }

    public MediaItem processFile(File file) {
        try {
            log.info("Processing {}", file.getAbsolutePath());
            var item = mediaRepository.findByFullpath(file.getAbsolutePath());
            if (!item.isPresent()) {
                log.info("Saving to db");
                MediaItem mediaItem = new MediaItem();
                mediaItem.setFullpath(file.getAbsolutePath());
                mediaItem.setTitle(file.getName().replaceFirst("[.][^.]+$", ""));
                processMediaItem(mediaItem);
                mediaRepository.save(mediaItem);
                return mediaItem;
            } else {
                processMediaItem(item.get());
                mediaRepository.save(item.get());
                return item.get();
            }
        } catch (Exception e) {
            log.error(e.toString());
        }
        return null;

    }

    private void processMediaItem(MediaItem item) throws IOException, InterruptedException, ParseException {
        if (item.length == null) {
            Long ms = getItemDuration(item);
            item.setLength(ms);
            log.info("Length set for {} to {}", item.fullpath, ms);
        }

        /* if (item.volume == null) {
            String volume = getItemVolume(item);
            item.setVolume(volume);
            log.info("Volume set for {} to {}", item.fullpath, volume);
        } */

    }

    public void processItemVolume(MediaItem item) {
        try {
            String volume = getItemVolume(item);
            item.setVolume(volume);
            mediaRepository.save(item);
        } catch (Exception e) {
            log.info(e.getMessage());
        }
    }

    private Long getItemDuration(MediaItem item) throws IOException, InterruptedException, ParseException {
        final String json;
        json = ProcessUtil.executeProcess(
                ffprobePath,
                "-v", "quiet",
                "-print_format", "json",
                "-show_entries",
                "format=duration",
                "-i",
                item.fullpath
        );
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> map = mapper.readValue(json, Map.class);

        try {
            String duration = (String) ((Map<String, Object>) map.get("format")).get("duration");
            Duration d = Duration.parse(String.format("PT%sS", duration));
            Long ms = d.toMillis();
            return ms;
        } catch (NullPointerException e) {

        }

        return 0L;
    }


    private String getItemVolume(MediaItem item) throws IOException, InterruptedException, ParseException {
        final String volumeString = ProcessUtil.executeProcess(
                "bash", "-c", volumeApp + " '" + item.fullpath.replace("'", "\\\\'") + "'"
        );

        try {
            log.info(volumeApp + " '" + item.fullpath.replace("'", "\\'") + "'");
            log.info(volumeString);

            Float volume = Float.parseFloat(volumeString);
            return volume.toString();
        } catch (NullPointerException | NumberFormatException e) {
            log.info(e.getMessage());
        }

        return null;
    }
}
