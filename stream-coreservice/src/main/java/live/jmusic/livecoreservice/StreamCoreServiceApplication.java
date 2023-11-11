package live.jmusic.livecoreservice;

import live.jmusic.livecoreservice.service.StreamService;
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
public class StreamCoreServiceApplication implements ApplicationRunner {

    public static void main(String[] args) {
        SpringApplication.run(StreamCoreServiceApplication.class, args);
    }


    @Autowired
    StreamService streamService;


    @Override
    public void run(ApplicationArguments args) throws Exception {
        streamService.run();
    }

}
