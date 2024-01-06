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

    //@Value("${media.ffmpeg.stream.path}")
    //public String streamPath;

    @Value("${media.ffmpeg.stream.app}")
    public String streamApp;

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


    public Optional<String> buildSubtitles(RotationItem rotationItem) {
        Optional<String> subtitleFilter = getSubtitlesVideoFilter(rotationItem);
        return subtitleFilter;
    }

    public void runSubtitlesConvertProcess(String source, String target, Long startTime) {
        try {
            Process processBuilder = new ProcessBuilder(
                    streamApp,
                    "-y", "-i", source, "-ss", convertLongToTime(startTime), target
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
