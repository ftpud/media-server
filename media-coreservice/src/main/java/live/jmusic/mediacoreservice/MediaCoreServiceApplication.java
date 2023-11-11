package live.jmusic.mediacoreservice;

import live.jmusic.mediacoreservice.service.FileWatcherService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan("live.jmusic.*")
@EntityScan("live.jmusic.*")
public class MediaCoreServiceApplication implements ApplicationRunner {

    @Autowired
    FileWatcherService fileWatcherService;

    public static void main(String[] args) {
        SpringApplication.run(MediaCoreServiceApplication.class, args);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        fileWatcherService.run();
    }
}
