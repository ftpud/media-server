package live.jmusic.streamservice.util;


import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.util.*;

public class SubtitlesHelper {

    private static Map<String, String> supportedSubtitles;

    static {
        supportedSubtitles = new HashMap<>();
        supportedSubtitles.put("ass", "ass");
        supportedSubtitles.put("srt", "subtitles");
    }

    public static Optional<String> getSubtitlesVideoFilter(String filename) {
        return supportedSubtitles
                .entrySet()
                .stream()
                .map(e -> {
                            Optional<String> file = getSubtitlesFile(filename, e.getKey());
                            if (file.isPresent()) {
                                return Optional.of(String.format("%s='%s'", e.getValue(), file.get()));
                            }
                            return Optional.empty();
                        }
                ).filter(Optional::isPresent)
                .map(e -> (String) e.get())
                .findFirst();
    }


    private static Optional<String> getSubtitlesFile(String file, String subExt) {
        String ext = FilenameUtils.getExtension(file);
        String fileWithNoExtension = file.substring(0, file.length() - ext.length());

        File subFile = new File(String.format(fileWithNoExtension, subExt));
        if (subFile.exists()) {
            return Optional.of(subFile.getAbsolutePath());
        }
        return Optional.empty();
    }

}
