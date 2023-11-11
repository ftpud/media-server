package live.jmusic.streamcoreservice.service;

import live.jmusic.shared.model.RotationItem;
import live.jmusic.shared.rest.RestRequestService;
import live.jmusic.streamcoreservice.util.VideoFilterBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;

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
            //long pid = ffmpegProcess.pid();
            //Runtime.getRuntime().exec("kill -15 " + pid);

            ffmpegProcess.destroy();
        }
    }

    private void runFfmpeg(RotationItem currentItem) {
        try {
            log.info("Starting ffmpeg for {}", currentItem.getMediaItem().fullpath);
            //  String execCmd = String.format("%s/%s -ss \"%sms\" -re -i \"%s\" -b:a 256k -c:a aac -ar 44100 -ac 2 -vsync 1 -async 1  -flags low_delay -strict strict -avioflags direct -fflags +discardcorrupt -probesize 32 -analyzeduration 0 -movflags +faststart -bsf:v h264_mp4toannexb -c:v h264 -r 30 -g 60 -b:v 3500k -maxrate:v 3500k -minrate:v 3500k -f flv pipe:1 >> %s/input_pipe",
            //          streamPath,
            //          streamApp,
            //          currentItem.getCurrentTime(),
            //          currentItem.getMediaItem().getFullpath(),
            //          streamPath);
            //  log.info(execCmd);

            //   ffmpegProcess = new ProcessBuilder(
            //           "/bin/sh", "-c", execCmd)
            //           .directory(new File(streamPath))
            //           .redirectErrorStream(true).start();

            ffmpegProcess = new ProcessBuilder(
                    streamPath + "/" + streamApp,
                    // "-hide_banner",
                    // "-loglevel",
                    // "quiet",
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
                            .withScale(1920,1080)
                            .withPad(1920,1080)
                            .withDrawText()
                                .withText(currentItem.getMediaItem().getTitle())
                                .withFontFile("/usr/share/fonts/truetype/wqy/wqy-microhei.ttc")
                                .withPosition("2","2")
                                .withFontSize(24)
                                .withFontColor("white")
                                .withBox("black")
                                .withBoxBorder(4)
                                .buildDrawText()

                            .withDrawText()
                                .withTextFile("live.txt")
                                .withFontFile("/usr/share/fonts/truetype/wqy/wqy-microhei.ttc")
                                .withPosition("2","H-th")
                                .withFontSize(24)
                                .withFontColor("white")
                                .withBox("black@0.4")
                                .withBoxBorder(4)
                                .withReload(1)
                                .buildDrawText()
                            .build(),
                    "-r",
                    "30",
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
                    //.redirectErrorStream(true)
                    .redirectError(new File("log-stream-ffmpeg.log"))
                    .redirectOutput(new File(streamPath + "/input_pipe"))
                    .start();

            try {
                ffmpegProcess.waitFor();

            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
                /*
            try (InputStreamReader isr = new InputStreamReader(ffmpegProcess.getInputStream())) {
                int c;
                while ((c = isr.read()) >= 0) {
                    System.out.print((char) c);
                    System.out.flush();
                }
            } */
        } catch (IOException e) {
            //throw new RuntimeException(e);
        }
    }


    /*

       $"drawtext=text='{title.Replace("'", "").Replace("\"", "")}':fontcolor=white@0.8:fontfile=/usr/share/fonts/truetype/wqy/wqy-microhei.ttc:fontsize={SONG_NAME_FONT_SIZE}:x=2:y=2:box=1:boxcolor=black@0.4:boxborderw=4," +
                $"drawtext=textfile=live.txt:fontcolor=white@0.8:fontfile=/usr/share/fonts/truetype/wqy/wqy-microhei.ttc:fontsize={INFO_FONT_SIZE}:x=0:y=H-th-0:box=1:boxcolor=black@0.4:reload=1," +

     */
}
