package live.jmusic.balancecoreservice;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

@SpringBootApplication
public class BalanceCoreServiceApplication implements ApplicationRunner {

	public static void main(String[] args) {
		SpringApplication.run(BalanceCoreServiceApplication.class, args);
	}


	@Value("${media.pipe.input}")
	private String INPUT_PIPE;

	@Value("${media.pipe.output_pipe1}")
	private String OUTPUT_PIPE1;

	@Value("${media.pipe.output_pipe2}")
	private String OUTPUT_PIPE2;
	private boolean useFirstPipe = true;

	@Override
	public void run(ApplicationArguments args) throws Exception {

		while(true) {
			FileInputStream is = new FileInputStream(INPUT_PIPE);
			FileOutputStream os = new FileOutputStream(useFirstPipe ? OUTPUT_PIPE1 : OUTPUT_PIPE2);
			useFirstPipe = !useFirstPipe;

			byte[] bytes = new byte[0x10000]; /* 0x10000 = 65536 */
			int numRead = 0;
			while ((numRead = is.read(bytes, 0, bytes.length)) >= 0) {
				//res.write(bytes, 0, numRead);
				os.write(bytes,0, numRead);
			}

			os.flush();
			os.close();
			is.close();
		}
	}
}
