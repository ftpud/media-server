package live.jmusic.streamservice.service;


import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

@Service
public class SubtitlesService {

    List<String> supportedSubExt = Arrays.asList("ass", "srt");

    //public Optional<String> getSubtitlesFile(String mediaFile) {
    //    String ext = FilenameUtils.getExtension(mediaFile);
    //    String fileWithNoExtension = mediaFile.substring(0, mediaFile.length() - ext.length());

    //    return supportedSubExt.stream().filter(x -> checkFile(fileWithNoExtension, x)).findFirst();
    //}

    public String getAssSubtitlesFilter(String filename) {
        return "";
    }

    public String getSrtSubtitlesParameter(String filename) {
        return "";
    }


    private boolean checkFile(String file, String ext) {
        File subFile = new File(String.format(file, ext));
        return subFile.exists();
    }

}
