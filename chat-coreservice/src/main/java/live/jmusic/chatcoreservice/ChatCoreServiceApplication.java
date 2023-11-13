package live.jmusic.chatcoreservice;

import live.jmusic.chatcoreservice.service.core.Sc2tvChatService;
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
public class ChatCoreServiceApplication implements ApplicationRunner {

    public static void main(String[] args) {
        SpringApplication.run(ChatCoreServiceApplication.class, args);
    }


    @Autowired
    Sc2tvChatService sc2tvChatService;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        sc2tvChatService.run();
    }

}
