package live.jmusic.chatservice;

import live.jmusic.chatservice.service.ChatMessageHandler;
import live.jmusic.chatservice.service.core.WebsocketChatService;
import live.jmusic.chatservice.service.core.goodgame.GoodgameWebsocketHandler;
import live.jmusic.chatservice.service.core.sc2tv.Sc2tvWebsocketHandler;
import live.jmusic.chatservice.service.core.twitch.TwitchWebsocketHandler;
import live.jmusic.chatservice.utils.ExceptionUtil;
import live.jmusic.shared.rest.RestRequestService;
import live.jmusic.shared.util.ConsoleUtilService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

import java.util.Arrays;
import java.util.List;

@Slf4j
@SpringBootApplication
@ComponentScan(basePackages = {"live.jmusic.*"})
public class ChatServiceApplication implements ApplicationRunner {

    public static void main(String[] args) {
        SpringApplication.run(ChatServiceApplication.class, args);
    }

    @Value("${media.chat.sc2tv.ws.url}")
    private String SC2TV_URL;

    @Value("${media.chat.gg.ws.url}")
    private String GG_URL;

    @Value("${media.chat.twitch.ws.url}")
    private String TWITCH_URL;

    @Value("${media.chat.twitch.username}")
    private String twitchUsername;

    @Value("${media.chat.twitch.password}")
    private String twitchPassword;

    @Autowired
    WebsocketChatService chatService;

    @Autowired
    RestRequestService restRequestService;

    @Autowired
    ConsoleUtilService consoleUtilService;

    @Override
    public void run(ApplicationArguments args) throws Exception {

        List<String> sc2tvModers = Arrays.asList("bober12", "tdsfog", "Акимотыч");
        List<String> ggModers = Arrays.asList("ftpud", "Arimas48");
        List<String> twitchModers = Arrays.asList("ftpud00");

        startChatListener("Sc2tv", () -> chatService.run(SC2TV_URL, new Sc2tvWebsocketHandler("stream/56592", new ChatMessageHandler(restRequestService, consoleUtilService, sc2tvModers))), 1000 * 60 * 5);
        startChatListener("Goodgame", () -> chatService.run(GG_URL, new GoodgameWebsocketHandler("196745", new ChatMessageHandler(restRequestService, consoleUtilService, ggModers))), 60000);
        startChatListener("Twitch", () -> chatService.run(TWITCH_URL, new TwitchWebsocketHandler(
                twitchUsername,
                twitchPassword,
                twitchUsername,
                new ChatMessageHandler(restRequestService, consoleUtilService, twitchModers))), 60000);

        // Sleep
        Thread.currentThread().join();
    }


    private void startChatListener(String chatName, Runnable runnable, int reconnectDelay) {
        Runnable r = () -> {
            while (true) {
                log.info("Connecting to " + chatName);
                ExceptionUtil.recoverable(runnable::run);
                log.info(chatName + " connection failure");
                ExceptionUtil.recoverable(() -> Thread.sleep(reconnectDelay));
            }
        };

        Thread chatThread = new Thread(r);
        chatThread.start();
    }

}
