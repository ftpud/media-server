package live.jmusic.streamservice.util;


import live.jmusic.shared.model.RotationItem;
import live.jmusic.streamservice.model.SubtitlesFile;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

@Slf4j
@Service
public class SubtitlesService {

    @Value("${subsync.app}")
    public String subsyncApp;

    private Map<String, String> supportedSubtitles;

    @PostConstruct
    public void Init() {
        supportedSubtitles = new HashMap<>();
        supportedSubtitles.put("ass", "ass");
        supportedSubtitles.put("srt", "subtitles");
    }

    private Optional<String> getSubtitlesVideoFilter(RotationItem rotationItem) {
        Optional<SubtitlesFile> subtitlesFile = supportedSubtitles
                .entrySet()
                .stream()
                .map(e -> {
                            Optional<String> file = getSubtitlesFile(rotationItem.getMediaItem().getFullpath(), e.getKey());
                            if (file.isPresent()) {
                                //return Optional.of(String.format("%s='%s'", e.getValue(), file.get()));
                                return Optional.of(new SubtitlesFile(file.get(), e.getKey()));
                            }
                            return Optional.empty();
                        }
                ).filter(Optional::isPresent)
                .map(e -> (SubtitlesFile) e.get())
                .findFirst();

        if (subtitlesFile.isPresent()) {
            log.info("Processing subs");
            String convertedSubFileName = String.format("/tmp/converted_sub.%s", subtitlesFile.get().getFormat());
            runSubtitlesConvertProcess(subtitlesFile.get().getFile(), convertedSubFileName, rotationItem.getCurrentTime());
            log.info("Subtitles converted");
            return Optional.of(String.format("%s='%s'", supportedSubtitles.get(subtitlesFile.get().getFormat()), convertedSubFileName));
        }
        return Optional.empty();
    }


    private Optional<String> getSubtitlesFile(String file, String subExt) {
        String ext = FilenameUtils.getExtension(file);
        String fileWithNoExtension = file.substring(0, file.length() - ext.length());

        File subFile = new File(String.format("%s%s", fileWithNoExtension, subExt));
        if (subFile.exists()) {
            log.info("Found: " + subFile);
            return Optional.of(subFile.getAbsolutePath());
        }
        return Optional.empty();
    }


    /** For mediad: converts the matching subtitle file (aligned to startMs) to a unique temp file, returns its path. */
    public Optional<String> buildSubtitlesFile(live.jmusic.shared.model.MediaItem item, long startMs) {
        File[] old = new File("/tmp").listFiles((d, n) -> n.startsWith("mediad_sub_"));
        if (old != null) {
            long cutoff = System.currentTimeMillis() - 6 * 3600_000L;
            for (File f : old) {
                if (f.lastModified() < cutoff) {
                    f.delete();
                }
            }
        }
        for (String ext : supportedSubtitles.keySet()) {
            Optional<String> file = getSubtitlesFile(item.getFullpath(), ext);
            if (file.isPresent()) {
                String target = String.format("/tmp/mediad_sub_%s_%d.%s", item.getId(), System.nanoTime(), ext);
                runSubtitlesConvertProcess(file.get(), target, startMs);
                if (new File(target).exists()) {
                    return Optional.of(target);
                }
            }
        }
        return Optional.empty();
    }

    public Optional<String> buildSubtitles(RotationItem rotationItem) {
        Optional<String> subtitleFilter = getSubtitlesVideoFilter(rotationItem);
        return subtitleFilter;
    }

    public void runSubtitlesConvertProcess(String source, String target, Long startTime) {
        try {
            Process processBuilder = new ProcessBuilder(
                    subsyncApp,
                     "-" + startTime.toString(), "-w", target, source
            )
                    .redirectError(new File("logs/log-sub-ffmpeg.log"))
                    .start();
            processBuilder.waitFor();

        } catch (Exception e) {
            log.info(e.toString());
        }
    }

    private String convertLongToTime(Long ms) {
        Date date = new Date(ms);
        DateFormat formatter = new SimpleDateFormat("HH:mm:ss.SSS");
        formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        return formatter.format(date);
    }
}
