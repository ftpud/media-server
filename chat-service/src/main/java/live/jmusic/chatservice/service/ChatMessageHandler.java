package live.jmusic.chatservice.service;

import live.jmusic.shared.rest.RestRequestService;
import live.jmusic.shared.util.ConsoleUtilService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.stream.Collectors;

@Slf4j
public class ChatMessageHandler extends ChatMessageHandlerBase {

    ConsoleUtilService consoleUtilService;

    RestRequestService restRequestService;

    public ChatMessageHandler(RestRequestService restRequestService, ConsoleUtilService consoleUtilService) {
        this.consoleUtilService = consoleUtilService;
        this.restRequestService = restRequestService;
        registerEvent("^!q (.+)$",
                r -> enqueue(r.group(1)), this::everyone);
        registerEvent("^!next$",
                r -> next(), this::everyone);
        registerEvent("^!play (.+)$",
                r -> play(r.group(1)), this::everyone);
        registerEvent("^!search (.+)$",
                r -> search(r.group(1)), this::everyone);
        registerEvent("^!time ([0-9]?[0-9]:[0-9][0-9])$",
                r -> seek(r.group(1)), this::everyone);
        registerEvent("^!time ([0-9]?[0-9]:[0-9][0-9]:[0-9][0-9])$",
                r -> seek(r.group(1)), this::everyone);

        registerEvent("^!youtube-dl ([^ ]+?) (.+)$",
                r -> {
                    log.info(r.group(1));
                    log.info(r.group(2));

                    consoleUtilService.youtubeDl(r.group(1), r.group(2), restRequestService);
                }, this::isModerator);
    }

    private void search(String item) {
        restRequestService.requestSearch(item, i ->
        {
            String foundText = Arrays.stream(i).map(found -> found.getTitle()).collect(Collectors.joining("\n"));
            restRequestService.sendLiveMessage(foundText);
        });
    }

    private void seek(String time) {
        restRequestService.requestSeek(time, i -> {
            restRequestService.sendLiveMessage(i);
        });
    }

    private void enqueue(String item) {
        restRequestService.requestEnqueue(item, itm -> {
                    if (itm == null) {
                        restRequestService.sendLiveMessage(item + " not found");
                    } else {
                        restRequestService.sendLiveMessage("Now in queue: " + itm.getTitle());
                    }
                }
        );
    }

    private void play(String item) {
        restRequestService.requestEnqueue(item, itm -> {
                    if (itm == null) {
                        restRequestService.sendLiveMessage(item + " not found");
                    } else {
                        restRequestService.sendLiveMessage("Now in queue: " + itm.getTitle());
                        restRequestService.requestNext(next -> {
                        });
                    }
                }
        );
    }

    private void next() {
        restRequestService.requestNext(itm -> {
                    if (itm != null) {
                        restRequestService.sendLiveMessage("Now in queue: " + itm.getMediaItem().getTitle());
                    }
                }
        );
    }

    public Boolean isModerator(String sender) {
        return Arrays.asList("bober12", "tdsfog", "Акимотыч").contains(sender);
    }

    public Boolean everyone(String sender) {
        return true;
    }
}
