package live.jmusic.streamcoreservice.service;

import live.jmusic.shared.rest.RestClient;
import live.jmusic.streamcoreservice.model.TimedItem;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.jni.Proc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

@Service
@Slf4j
public class StreamService {

    @Value("${media.core.service.uri:http://localhost:8081}")
    public String coreServiceUri;

    @Value("${media.ffmpeg.stream.path:/home/ftpud/server/ffmpeg}")
    public String streamPath;

    @Value("${media.ffmpeg.stream.app:ffmpeg}")
    public String streamApp;


    private Process ffmpegProcess;

    public void run() {
        if (!System.getProperty("os.name").contains("Win")) {
            while (true) {
                RestClient.recoverableRequest(coreServiceUri + "/media/now", TimedItem.class, this::runFfmpeg);
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

    private void runFfmpeg(TimedItem currentItem) {
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
                    "-hide_banner",
                    "-loglevel",
                    "quiet",
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
                    "scale=(iw*sar)*min(1920/(iw*sar)\\,1080/ih):ih*min(1920/(iw*sar)\\,1080/ih),pad=1920:1080:(1920-iw*min(1920/iw\\,1080/ih))/2:(1080-ih*min(1920/iw\\,1080/ih))/2",
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
                    .redirectErrorStream(true)
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

}
