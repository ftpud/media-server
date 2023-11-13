package live.jmusic.streamservice.service;

import live.jmusic.shared.model.MediaItem;
import live.jmusic.shared.model.RotationItem;
import live.jmusic.shared.rest.RestRequestService;
import live.jmusic.streamservice.util.VideoFilterBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;

import static org.apache.logging.log4j.util.Strings.isNotEmpty;

@Service
@Slf4j
public class StreamService {

    @Autowired
    RestRequestService restRequestService;

    @Value("${media.ffmpeg.stream.path:/home/ftpud/server/ffmpeg}")
    public String streamPath;

    @Value("${media.ffmpeg.stream.app:ffmpeg}")
    public String streamApp;


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


            String volume = getAudioFilter(currentItem.getMediaItem());
            restRequestService.sendLiveMessage("Loaded with: " + volume);

            ffmpegProcess = new ProcessBuilder(
                    streamPath + "/" + streamApp,
                    "-ss",
                    String.format("%sms", currentItem.getCurrentTime()),
                    "-re",
                    "-i",
                    currentItem.getMediaItem().getFullpath(),
                    "-b:a",
                    "256k",
                    "-c:a",
                    "aac",
                    "-ar",
                    "44100",
                    "-ac",
                    "2",
                    "-vsync",
                    "1",
                    "-async",
                    "1",
                    "-flags",
                    "low_delay",
                    "-strict",
                    "strict",
                    "-avioflags",
                    "direct",
                    "-fflags",
                    "+discardcorrupt",
                    "-probesize",
                    "32",
                    "-analyzeduration",
                    "0",
                    "-movflags",
                    "+faststart",
                    "-bsf:v",
                    "h264_mp4toannexb",
                    "-c:v",
                    "h264",
                    "-vf",
                    VideoFilterBuilder.create()
                            .withScale(1920, 1080)
                            .withPad(1920, 1080)
                            .withDrawText()
                            .withText(currentItem.getMediaItem().getTitle())
                            .withFontFile("/usr/share/fonts/truetype/wqy/wqy-microhei.ttc")
                            .withPosition("2", "2")
                            .withFontSize(24)
                            .withFontColor("white")
                            .withBox("black")
                            .withBoxBorder(4)
                            .buildDrawText()

                            .withDrawText()
                            .withTextFile("live.txt")
                            .withFontFile("/usr/share/fonts/truetype/wqy/wqy-microhei.ttc")
                            .withPosition("2", "H-th")
                            .withFontSize(24)
                            .withFontColor("white")
                            .withBox("black@0.4")
                            .withBoxBorder(4)
                            .withReload(1)
                            .buildDrawText()
                            .build(),
                    "-r",
                    "30",
                    "-af", volume,
                    "-g",
                    "60",
                    "-b:v",
                    "3500k",
                    "-maxrate:v",
                    "3500k",
                    "-minrate:v",
                    "3500k",
                    "-f",
                    "flv",
                    "pipe:1"
            ).directory(new File(streamPath))
                    .redirectError(new File("log-stream-ffmpeg.log"))
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
