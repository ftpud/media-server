package live.jmusic.liveservice;

import live.jmusic.liveservice.service.LiveService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@Slf4j
@SpringBootApplication
@ComponentScan(basePackages = {"live.jmusic.*"})
public class LiveServiceApplication implements ApplicationRunner {

    public static void main(String[] args) {
        SpringApplication.run(LiveServiceApplication.class, args);
    }


    @Autowired
    LiveService liveService;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        liveService.run();
    }

}
