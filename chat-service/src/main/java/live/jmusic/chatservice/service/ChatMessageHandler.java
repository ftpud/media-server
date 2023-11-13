package live.jmusic.chatservice.service;

import live.jmusic.shared.rest.RestRequestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ChatMessageHandler extends ChatMessageHandlerBase {

    RestRequestService restRequestService;

    public ChatMessageHandler(RestRequestService restRequestService) {
        this.restRequestService = restRequestService;
        registerEvent("^@q (.+)$", r -> enqueue(r.group(1)), this::isModerator);
        registerEvent("^@next$", r -> next(), this::isModerator);
        registerEvent("^@play (.+)$", r -> play(r.group(1)), this::isModerator);
        registerEvent("^@search (.+)$", r -> search(r.group(1)), this::isModerator);
    }

    private void search(String item) {
        restRequestService.requestSearch(item, i ->
        {
            String foundText = Arrays.stream(i).map(found -> found.getTitle()).collect(Collectors.joining("\n"));
            restRequestService.sendLiveMessage(foundText);
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
        return true;
    }
}
