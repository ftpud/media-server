package live.jmusic.publishercoreservice;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;


@SpringBootApplication
@Slf4j
public class PublisherCoreServiceApplication implements ApplicationRunner {

	public static void main(String[] args) {
		SpringApplication.run(PublisherCoreServiceApplication.class, args);
	}

	@Value("${media.publisher.ffmpeg.path}")
	public String publisherPath;

	@Value("${media.publisher.ffmpeg.app}")
	public String publisherApp;

	@Override
	public void run(ApplicationArguments args) throws Exception {

		System.out.println("Hello world from Command Line Runner");
		doShit();

	}

	private void doShit() throws IOException {
		log.info("Executing publisher");

		while(true) {
			Process process = new ProcessBuilder((publisherPath + publisherApp + " -loglevel error -f concat -i list.txt -flags low_delay -movflags +faststart -bsf:v h264_mp4toannexb -c copy -f flv rtmp://192.168.0.129/test").split(" "))
					.directory(new File(publisherPath))
					.redirectErrorStream(true).start();

			try (InputStreamReader isr = new InputStreamReader(process.getInputStream())) {
				int c;
				while ((c = isr.read()) >= 0) {
					System.out.print((char) c);
					System.out.flush();
				}
			}

			log.info("Publisher terminated. Restarting.");
		}

		// ffmpeg -loglevel error -i list.txt -flags low_delay -movflags +faststart -bsf:v h264_mp4toannexb c copy -f flv rtmp://192.168.0.129/test

		// ffmpeg/ffmpeg -re -i 1.flv -b:a 256k -c:a aac -ar 44100 -ac 2 -vsync 1 -async 1  -flags low_delay -strict strict -avioflags direct -fflags +discardcorrupt -probesize 32 -analyzeduration 0 -movflags +faststart -bsf:v h264_mp4toannexb -c:v h264 -r 30 -g 60 -b:v 3500k -maxrate:v 3500k -minrate:v 3500k -f flv pipe:1 >> ffmpeg/vid2
	}
}
