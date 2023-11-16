package live.jmusic.streamservice.service;

import live.jmusic.shared.model.MediaItem;
import live.jmusic.shared.model.RotationItem;
import live.jmusic.shared.rest.RestRequestService;
import live.jmusic.streamservice.util.FfmpegHelper;
import live.jmusic.streamservice.util.SubtitlesService;
import live.jmusic.streamservice.util.videofilter.VideoFilterBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

import static org.apache.logging.log4j.util.Strings.isNotEmpty;

@Service
@Slf4j
public class StreamService {

    @Autowired
    RestRequestService restRequestService;

    @Autowired
    SubtitlesService subtitlesService;

    @Value("${media.ffmpeg.stream.path:/home/ftpud/server/ffmpeg}")
    public String streamPath;

    @Value("${media.ffmpeg.stream.app:ffmpeg}")
    public String streamApp;

    @Value("${profile:dev}")
    public String profile;


    private Process ffmpegProcess;

    public void run() {
        if (!System.getProperty("os.name").contains("Win")) {
            while (true) {
                restRequestService.requestNow(i -> runFfmpeg(i));
            }
        }
    }

    public void restart() throws IOException {
        if (ffmpegProcess != null) {
            ffmpegProcess.destroy();
        }
    }


    private void runFfmpeg(RotationItem currentItem) {
        try {
            log.info("Starting ffmpeg for {}", currentItem.getMediaItem().fullpath);

            ProcessBuilder processBuilder;

            Optional<String> subtitlesFilter = subtitlesService.buildSubtitles(currentItem);

            String vf = FfmpegHelper.buildVideoFilter(currentItem, subtitlesFilter);
            String volume = getAudioFilter(currentItem.getMediaItem());
            restRequestService.sendLiveMessage("Loaded with: " + volume);


            if ("prod".equals(profile)) {
                processBuilder = new ProcessBuilder(FfmpegHelper.getFfmpegProdCommand(
                        streamPath + "/" + streamApp,
                        currentItem.getCurrentTime(), currentItem.getMediaItem().getFullpath(), vf, volume));
            } else {
                processBuilder = new ProcessBuilder(FfmpegHelper.getFfmpegPreProdCommand(
                        streamPath + "/" + streamApp,
                        currentItem.getCurrentTime(), currentItem.getMediaItem().getFullpath(), vf, volume));
            }

            ffmpegProcess = processBuilder.directory(new File(streamPath))
                    .redirectError(new File("logs/log-stream-ffmpeg.log"))
                    .redirectOutput(new File(streamPath + "/input_pipe"))
                    .start();

            try {
                ffmpegProcess.waitFor();

            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        } catch (IOException e) {
            //throw new RuntimeException(e);
        }
    }

    private String getAudioFilter(MediaItem item) {
        if (isNotEmpty(item.getVolume())) {
            return "volume=" + (-10 - Float.parseFloat(item.getVolume())) + "dB";
        }
        return "volume=1";
    }

}
