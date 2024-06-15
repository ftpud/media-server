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
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.apache.logging.log4j.util.Strings.isNotEmpty;

@Service
@Slf4j
public class StreamService {

    @Autowired
    RestRequestService restRequestService;

    @Autowired
    SubtitlesService subtitlesService;

    @Value("${media.ffmpeg.stream.path}")
    public String streamPath;

    @Value("${media.ffmpeg.stream.app}")
    public String streamApp;

    @Value("${media.profile}")
    public String profile;


    @Value("${media.live.file}")
    public String liveFile;

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

            detectAbnormality(currentItem.getMediaItem().fullpath);

            ProcessBuilder processBuilder;

            Optional<String> subtitlesFilter = subtitlesService.buildSubtitles(currentItem);

            String vf = FfmpegHelper.buildVideoFilter(currentItem, subtitlesFilter, liveFile);
            String volume = getAudioFilter(currentItem.getMediaItem());
            restRequestService.sendLiveMessage("Loaded with: " + volume);


            if ("prod".equals(profile)) {
                String[] cmd = FfmpegHelper.getFfmpegProdCommand(
                        streamApp,
                        currentItem.getCurrentTime(), currentItem.getMediaItem().getFullpath(), vf, volume);
                processBuilder = new ProcessBuilder(cmd);

                log.info(Arrays.stream(cmd).collect(Collectors.joining(" ")));
            } else {
                processBuilder = new ProcessBuilder(FfmpegHelper.getFfmpegPreProdCommand(
                        streamApp,
                        currentItem.getCurrentTime(), currentItem.getMediaItem().getFullpath(), vf, volume));


            }

            ffmpegProcess = processBuilder.directory(new File(streamPath))
                    .redirectError(new File("log-stream-ffmpeg.log"))
                    .redirectOutput(new File(streamPath + "/input_pipe"))
                    .start();

            try {
                ffmpegProcess.waitFor();

                if (ffmpegProcess.exitValue() != 0) {
                    log.info("Abnormal ffmpeg termination {}", ffmpegProcess.exitValue());
                    // restRequestService.requestNext(x -> {});
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        } catch (IOException e) {
            //throw new RuntimeException(e);
        }
    }

    LocalDateTime lastInvoke;
    int invokeCount=0;
    String lastInvokeItem;

    void detectAbnormality(String lastItem) {
        if(lastItem.equals(lastInvokeItem)){
            long diff = ChronoUnit.SECONDS.between(lastInvoke, LocalDateTime.now());
            if(diff <= 2) {
                log.info("Abnormal invoke for {}", lastItem);
                invokeCount++;
            } else {
                invokeCount = 0;
            }
            if (invokeCount >= 3) {
                log.info("Abnormality detected {}", ffmpegProcess.exitValue());
                restRequestService.requestNext(x -> {});
            }
            lastInvoke = LocalDateTime.now();
        } else {
            log.info("First invoke for {}", lastItem);
            lastInvoke = LocalDateTime.now();
            invokeCount=0;
            lastInvokeItem=lastItem;
        }

    }

    private String getAudioFilter(MediaItem item) {
        if (isNotEmpty(item.getVolume())) {
            return "volume=" + (-27 - Float.parseFloat(item.getVolume())) + "dB";
        }
        return "volume=1";
    }

}
