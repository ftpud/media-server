package live.jmusic.publishercoreservice;

import jdk.jfr.DataAmount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;


@SpringBootApplication
@Slf4j
public class PublisherCoreServiceApplication implements ApplicationRunner {

	public static void main(String[] args) {
		SpringApplication.run(PublisherCoreServiceApplication.class, args);
	}

	public static String PUBLISHER_PATH = "c:/ffmpeg/pub/";

	@Override
	public void run(ApplicationArguments args) throws Exception {

		System.out.println("Hello world from Command Line Runner");
		doShit();

	}

	private void doShit() throws IOException {
		log.info("Executing publisher");

		Process process = new ProcessBuilder("c:/ffmpeg/pub/ffmpeg.exe -loglevel error -f concat -i list.txt -flags low_delay -movflags +faststart -bsf:v h264_mp4toannexb c copy -f flv rtmp://192.168.0.129/test".split(" "))
				.directory(new File(PUBLISHER_PATH))
				.redirectErrorStream(true).start();

		try(InputStreamReader isr = new InputStreamReader(process.getInputStream())) {
			int c;
			while((c = isr.read()) >= 0) {
				System.out.print((char) c);
				System.out.flush();
			}
		}

		// ffmpeg -loglevel error -i list.txt -flags low_delay -movflags +faststart -bsf:v h264_mp4toannexb c copy -f flv rtmp://192.168.0.129/test

	}
}
