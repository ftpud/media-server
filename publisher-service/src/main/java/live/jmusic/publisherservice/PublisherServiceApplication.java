package live.jmusic.publisherservice;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;


@Slf4j
@SpringBootApplication
@ComponentScan(basePackages = {"live.jmusic.*"})
public class PublisherServiceApplication implements ApplicationRunner {

    public static void main(String[] args) {
        SpringApplication.run(PublisherServiceApplication.class, args);
    }

    @Value("${media.publisher.ffmpeg.path}")
    public String publisherPath;

    @Value("${media.publisher.ffmpeg.app}")
    public String publisherApp;

    @Value("${media.output.rtmp}")
    public String outputRtmp;

    @Value("${media.balancer.script}")
    public String balancerScript;

    Process blancerProcess;

    @Override
    public void run(ApplicationArguments args) throws Exception {

        System.out.println("Started");
        run();

    }

    private void runBalancer() throws IOException {
        //blancerProcess = new ProcessBuilder("java -jar balance-service.jar".split(" "))
        blancerProcess = new ProcessBuilder(balancerScript)
                .redirectErrorStream(true)
                .redirectOutput(new File("./logs/log-balancer.log"))
                .start();
        log.info("Balancer started.");
    }

    private void stopBalancer() throws InterruptedException {
        if (blancerProcess != null && blancerProcess.isAlive()) {
            log.info("Balancer destroyed.");
            blancerProcess.destroyForcibly(); //.destroy();
            blancerProcess.waitFor();
            Thread.sleep(1000);
        }
    }

    private void run() throws IOException, InterruptedException {
        log.info("Executing publisher");

        while (true) {
            runBalancer();


            Process process = new ProcessBuilder((publisherPath + publisherApp + " -loglevel error -f concat -i list.txt -flags low_delay -movflags +faststart -bsf:v h264_mp4toannexb -c copy -f flv " + outputRtmp).split(" "))
                    .directory(new File(publisherPath))
                    .redirectOutput(new File("./logs/log-publisher.log"))
                    .redirectErrorStream(true)
                    .start();

            try (InputStreamReader isr = new InputStreamReader(process.getInputStream())) {
                int c;
                while ((c = isr.read()) >= 0) {
                    System.out.print((char) c);
                    System.out.flush();
                }
            }

            process.waitFor();
            log.info("Publisher terminated.");
            stopBalancer();
        }

    }
}
