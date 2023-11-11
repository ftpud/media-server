package live.jmusic.mediacoreservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan("live.jmusic.*")
@EntityScan("live.jmusic.*")
public class MediaCoreServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MediaCoreServiceApplication.class, args);
    }

}
