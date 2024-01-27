package live.jmusic.mediaservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import live.jmusic.mediaservice.repository.RotationRepository;
import live.jmusic.mediaservice.repository.MediaRepository;
import live.jmusic.shared.model.MediaItem;
import live.jmusic.mediaservice.util.ProcessUtil;
import live.jmusic.shared.rest.RestRequestService;
import lombok.extern.slf4j.Slf4j;
import net.bramp.ffmpeg.FFmpeg;
import net.bramp.ffmpeg.FFmpegExecutor;
import net.bramp.ffmpeg.builder.FFmpegBuilder;
import net.bramp.ffmpeg.progress.Progress;
import net.bramp.ffmpeg.progress.ProgressListener;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.apache.commons.lang3.StringUtils.isNotEmpty;

@Service
@Slf4j
public class MediaDbService {

    @Autowired
    MediaRepository mediaRepository;

    @Autowired
    RotationRepository inMemoryRepository;

    @Autowired
    RestRequestService restRequestService;

    @Value("${media.volume.app}")
    private String volumeApp;

    @Value("${media.library.path}")
    private String mediaLibraryPath;

    @Value("${media.ffprobe.app}")
    private String ffprobeApp;

    @Value("${media.ffmpeg.app}")
    private String ffmpegApp;

    @Value("${media.library.supported.ext}")
    private String[] supportedExtensions;


    @PostConstruct
    public void init() {
        log.info("Library processing started");


        FileUtils.listFiles(new File(mediaLibraryPath), supportedExtensions, true).forEach(
                f -> processFile(f, false)
        );

        log.info("All files processed");
        inMemoryRepository.updateMediaItemsList();
    }

    public MediaItem processFile(File file, boolean full) {
        try {
            log.info("Processing {}", file.getAbsolutePath());
            var item = mediaRepository.findByFullpath(file.getAbsolutePath());
            if (!item.isPresent()) {
                log.info("Saving to db");
                MediaItem mediaItem = new MediaItem();
                mediaItem.setFullpath(file.getAbsolutePath());
                mediaItem.setTitle(file.getName().replaceFirst("[.][^.]+$", ""));
                if (full) {
                    fullProcessMediaItem(mediaItem);
                } else {
                    processMediaItem(mediaItem);
                }
                mediaRepository.save(mediaItem);
                return mediaItem;
            } else {
                if (full) {
                    fullProcessMediaItem(item.get());
                } else {
                    processMediaItem(item.get());
                }
                mediaRepository.save(item.get());
                return item.get();
            }
        } catch (Exception e) {
            log.error(e.toString());
        }
        return null;

    }

    public void startFullProcessing() {
        CompletableFuture f = CompletableFuture.runAsync(() -> {
            mediaRepository.findAll().forEach(i -> {
                File file = new File(i.fullpath);
                if (file.exists()) {
                    processFile(file, true);
                } else {
                    restRequestService.sendLiveMessage("File doesn't exists anymore: " + i.getFullpath());
                    mediaRepository.delete(i);
                }
            });
        });
    }

    private void processMediaItem(MediaItem item) throws IOException, InterruptedException, ParseException {
        if (item.length == null) {
            Long ms = getItemDurationFromStream(item);
            item.setLength(ms);
            log.info("Length set for {} to {}", item.fullpath, ms);
        }

        /* if (item.volume == null) {
            String volume = getItemVolume(item);
            item.setVolume(volume);
            log.info("Volume set for {} to {}", item.fullpath, volume);
        } */

    }

    public void fullProcessMediaItem(MediaItem item) throws IOException, InterruptedException, ParseException {
        //if (item.length == null) {
            Long ms = getItemDurationFromStream(item);
            item.setLength(ms);
            log.info("Length set for {} to {}", item.fullpath, ms);
            restRequestService.sendLiveMessage(String.format("%s length set to %s", item.getTitle(), ms / 1000));
        //}

        if (item.volume == null) {
            String volume = getItemVolume(item);
            item.setVolume(volume);
            log.info("Volume set for {} to {}", item.fullpath, volume);
            restRequestService.sendLiveMessage(String.format("%s volume set to %s", item.getTitle(), volume));
        }

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

    private Long getItemDurationFromMeta(MediaItem item) throws IOException, InterruptedException, ParseException {
        final String json;
        json = ProcessUtil.executeProcess(
                ffprobeApp,
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

    private Long getItemDurationFromStream(MediaItem item) throws IOException, InterruptedException, ParseException {
        final String json;
        json = ProcessUtil.executeProcess(
                ffprobeApp,
                "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                "-print_format", "json",
                "-i",
                item.fullpath
        );

        log.info(json);

        ObjectMapper mapper = new ObjectMapper();
        //Map<String, Object> map = mapper.readValue(json, Map.class);

        try {
            //String duration = (String) ((Map<String, Object>) map.get("streams")).get("duration");

            JsonNode jsonNode = mapper.readTree(json);

            // Extract the duration
            String duration = jsonNode
                    .path("streams")
                    .get(0)
                    .path("duration")
                    .asText();

            log.info("dur: " + duration);

            Duration d = Duration.parse(String.format("PT%sS", duration));
            Long ms = d.toMillis();
            return ms;
        } catch (Exception e) {
            log.info(e.toString());
        }

        return getItemDurationFromMeta(item);
    }

    /** public long getVideoDuration(String filePath) {
        Path videoPath = Path.of(filePath);

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(ffmpegApp, "-i", videoPath.toString());
            Process process = processBuilder.start();

            // Read the output of the FFmpeg command
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("Duration:")) {
                        // Parse the duration information
                        String durationString = line.split("Duration: ")[1].split(",")[0].trim();
                        return parseDurationString(durationString);
                    }
                }
            }

            process.waitFor(); // Wait for the process to complete

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }

        return 0; // Return 0 if the duration couldn't be determined
    } **/

    private static long parseDurationString(String durationString) {
        String[] parts = durationString.split(":");
        int hours = Integer.parseInt(parts[0]);
        int minutes = Integer.parseInt(parts[1]);
        double seconds = Double.parseDouble(parts[2]);

        return (long) (((double) hours * 3600 + (double) minutes * 60 + seconds) * 1000); // Round to the nearest second
    }

    private String getItemVolume(MediaItem item) throws IOException, InterruptedException, ParseException {
        final String volumeString = ProcessUtil.executeProcess(
                volumeApp, item.fullpath
        );

        try {
            log.info(volumeApp + " " + item.fullpath);
            log.info(volumeString);

            Float volume = Float.parseFloat(volumeString);
            return volume.toString();
        } catch (NullPointerException | NumberFormatException e) {
            log.info(e.toString());
        }

        return null;
    }

    public  void addTag(MediaItem item, String tag) {
        if(isNotEmpty(tag)) {
            if(item.getTags() == null) {
                item.setTags(new ArrayList<>());
            }

            MediaItem repoItm = mediaRepository.findById(item.id).get();
            repoItm.getTags().add(tag);
            item.getTags().add(tag);
            mediaRepository.save(repoItm);
        }
    }


    public void removeTag(MediaItem item, String tag) {
        if(isNotEmpty(tag)) {
            if(item.getTags() == null) {
                item.setTags(new ArrayList<>());
            }

            MediaItem repoItm = mediaRepository.findById(item.id).get();
            repoItm.getTags().removeIf(t -> t.equals(tag));
            item.getTags().removeIf(t -> t.equals(tag));
            mediaRepository.save(repoItm);
        }
    }
}
