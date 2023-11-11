package live.jmusic.publishercoreservice;

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
@ComponentScan("live.jmusic.*")
public class PublisherCoreServiceApplication implements ApplicationRunner {

	public static void main(String[] args) {
		SpringApplication.run(PublisherCoreServiceApplication.class, args);
	}

	@Value("${media.publisher.ffmpeg.path}")
	public String publisherPath;

	@Value("${media.publisher.ffmpeg.app}")
	public String publisherApp;

	Process blancerProcess;
	@Override
	public void run(ApplicationArguments args) throws Exception {

		System.out.println("Started");
		run();

	}

	private void runBalancer() throws IOException {
		blancerProcess = new ProcessBuilder("java -jar balance-coreservice.jar".split(" "))
				.redirectErrorStream(true)
				.redirectOutput(new File("log-balance.log"))
				.start();
		log.info("Balancer started.");
	}

	private void stopBalancer() throws InterruptedException {
		if(blancerProcess != null && blancerProcess.isAlive()) {
			log.info("Balancer destroyed.");
			blancerProcess.destroy();
			blancerProcess.waitFor();
		}
	}

	private void run() throws IOException, InterruptedException {
		log.info("Executing publisher");

		while(true) {
			runBalancer();


			Process process = new ProcessBuilder((publisherPath + publisherApp + " -loglevel error -f concat -i list.txt -flags low_delay -movflags +faststart -bsf:v h264_mp4toannexb -c copy -f flv rtmp://192.168.0.129/test").split(" "))
					.directory(new File(publisherPath))
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
