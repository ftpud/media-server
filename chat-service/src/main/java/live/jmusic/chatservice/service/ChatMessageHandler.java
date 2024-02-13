package live.jmusic.chatservice.service;

import live.jmusic.shared.model.MediaItem;
import live.jmusic.shared.rest.RestRequestService;
import live.jmusic.shared.util.ConsoleUtilService;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Collectors;

@Slf4j
public class ChatMessageHandler extends ChatMessageHandlerBase {

    final ConsoleUtilService consoleUtilService;

    final RestRequestService restRequestService;

    final List<String> moderatorsList;

    public ChatMessageHandler(RestRequestService restRequestService, ConsoleUtilService consoleUtilService, List<String> moderatorsList) {
        this.consoleUtilService = consoleUtilService;
        this.restRequestService = restRequestService;
        this.moderatorsList = moderatorsList;

        registerEvent("^!select (.+)$",
                r -> select(r.group(1)), this::everyone);
        registerEvent("^!q (.+)$",
                r -> enqueue(r.group(1)), this::everyone);
        registerEvent("^!qq (.+)$",
                r -> enqueueAll(r.group(1)), this::everyone);
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

        registerEvent("^!list$",
                r -> list(), this::everyone);

        registerEvent("^\\+(.+)$",
                r -> addTag(r.group(1)), this::isModerator);
        registerEvent("^-(.+)$",
                r -> removeTag(r.group(1)), this::isModerator);
        registerEvent("^!tags",
                r -> viewTags(), this::isModerator);
    }

    private MediaItem[] lastResponse;

    private void search(String item) {
        restRequestService.requestSearch(item, i ->
        {
            lastResponse = i;
            StringJoiner joiner = new StringJoiner("\n");
            for (int j = 0; j < i.length; j++) {
                MediaItem mediaItem = i[j];
                String title = mediaItem.getTitle();
                if (mediaItem.getFullpath().contains("RequestOnly")) {
                    title = "[Request] ".concat(title);
                }
                title = (String.format("[%3s] ", j)).concat(title);
                String requestOnly = title;
                joiner.add(requestOnly);
            }
            String foundText = joiner.toString();
            if ("".equals(foundText)) {
                restRequestService.sendLiveMessage("Nothing found");
            } else {
                restRequestService.sendLiveMessage(foundText);
            }
        });
    }

    private void select(String num) {
        try {
            int number = Integer.parseInt(num);
            if (lastResponse != null && lastResponse.length > number) {
                play(lastResponse[number].fullpath);
            }

        } catch (Exception e) {
            log.info(e.toString());
        }
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

    private void enqueueAll(String item) {
        restRequestService.requestSearch(item, i ->
        {
            Arrays.stream(i).forEach(mi -> enqueue(mi.getFullpath()));
        });
    }

    private void play(String item) {
        restRequestService.requestEnqueueNext(item, itm -> {
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

    private void list() {
        restRequestService.requestList(i -> {
            Arrays.stream(i).forEach(itm -> restRequestService.sendLiveMessage(itm.getTitle()));
        });
    }

    private void viewTags() {
        restRequestService.listTags(i ->
            restRequestService.sendLiveMessage(Arrays.stream(i).collect(Collectors.joining(", ")))
        );
    }

    private void addTag(String tag) {
        restRequestService.addTag(tag, i ->
                restRequestService.sendLiveMessage("+" + tag)
        );
    }

    private void removeTag(String tag) {
        restRequestService.removeTag(tag, i ->
                restRequestService.sendLiveMessage("-" + tag)
        );
    }

    public Boolean isModerator(String sender) {
        return moderatorsList.contains(sender);
    }

    public Boolean everyone(String sender) {
        return true;
    }

}
