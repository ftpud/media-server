package live.jmusic.chatservice.service;

import live.jmusic.shared.model.MediaItem;
import live.jmusic.shared.rest.RestRequestService;
import live.jmusic.shared.util.ConsoleUtilService;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
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
                r -> select(r.group(1)), this::isModerator);
        registerEvent("^!q (.+)$",
                r -> enqueue(r.group(1)), this::everyone);
        registerEvent("^!qq (.+)$",
                r -> enqueueAll(r.group(1)), this::isModerator);
        registerEvent("^!random (.+)$",
                r -> random(r.group(1)), this::isModerator);
        registerEvent("^!next$",
                r -> next(), this::isModerator);
        registerEvent("^!play (.+)$",
                r -> play(r.group(1)), this::isModerator);
        registerEvent("^!search (.+)$",
                r -> search(r.group(1)), this::everyone);
        registerEvent("^!time ([0-9]?[0-9]:[0-9][0-9])$",
                r -> seek(r.group(1)), this::isModerator);
        registerEvent("^!time ([0-9]?[0-9]:[0-9][0-9]:[0-9][0-9])$",
                r -> seek(r.group(1)), this::isModerator);

        registerEvent("^!youtube-dl ([^ ]+?) (.+)$",
                r -> {
                    log.info(r.group(1));
                    log.info(r.group(2));

                    consoleUtilService.youtubeDl(r.group(1), r.group(2), restRequestService);
                }, this::isModerator);

        registerEvent("^!list$",
                r -> list(), this::everyone);

        registerEvent("^!setlist$",
                r -> setlist(), this::everyone);

        registerEvent("^!setlist ([0-9]+)$",
                r -> setlist(Integer.parseInt(r.group(1))), this::isModerator);

        registerEvent("^!setlist ([^0-9].+)$",
                r -> setlist(r.group(1)), this::isModerator);

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


    Random random = new Random();

    private void random(String item) {
        restRequestService.requestSearch(item, i ->
        {
            int rnd = random.nextInt(i.length);
            play(i[rnd].fullpath);
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

    private Map<String, String> getSetList(String song) {

        Map<String, String> setList = new LinkedHashMap<>();

        String setlistFile = song.replaceFirst("[.][^.]+$", "").concat(".setlist");
        if (Files.exists(Path.of(setlistFile))) {
            try {
                List<String> songNames = Files.readAllLines(Path.of(setlistFile));

                for (int n = 0; n < songNames.size(); n++) {
                    var split = songNames.get(n).split(" ", 2);
                    setList.put(split[0], split[1]);
                }

            } catch (Exception e) {
                log.error(e.toString());
            }
        }

        return setList;
    }

    private void setlist() {
        restRequestService.requestNow(i -> {
            String file = i.getMediaItem().getFullpath();
            Map<String, String> setList = getSetList(file);
            if (setList.isEmpty()) {
                restRequestService.sendLiveMessage("Setlist is not found");
            } else {
                String out = "";

                var setListArr = setList.entrySet().toArray();

                for (int pos = 0; pos < setListArr.length; pos++) {
                    var e = (Map.Entry) setListArr[pos];
                    String s = String.format("%1$3s ", pos) + String.format(" %1$10s", e.getKey()) + " - " + e.getValue();
                    out += s + "\n";

                    log.info(pos + " -- " + e.getKey() + " -- " + e.getValue());
                }

                restRequestService.sendLiveMessage(out);
            }
        });
    }

    private void setlist(int num) {
        restRequestService.requestNow(i -> {
            String file = i.getMediaItem().getFullpath();
            Map<String, String> setList = getSetList(file);
            if (setList.isEmpty()) {
                restRequestService.sendLiveMessage("Setlist is not found");
            } else {
                //restRequestService.sendLiveMessage(setList.entrySet().stream().map(e -> String.format("%1$" + 10 + "s", e.getKey()) + " - " + e.getValue()).collect(Collectors.joining("\n")));
                if (num < setList.size()) {
                    Map.Entry<String, String> entry = (Map.Entry<String, String>) (setList.entrySet().toArray()[num]);
                    seek(entry.getKey());
                } else {
                    // nothing found
                    restRequestService.sendLiveMessage("Nothing found");
                }
            }
        });
    }

    private void setlist(String text) {
        restRequestService.requestNow(i -> {
            String file = i.getMediaItem().getFullpath();
            Map<String, String> setList = getSetList(file);
            if (setList.isEmpty()) {
                restRequestService.sendLiveMessage("Setlist is not found");
            } else {

                for (Map.Entry<String, String> entry : setList.entrySet()) {
                    if (entry.getValue().toLowerCase().contains(text.toLowerCase())) {
                        seek(entry.getKey());
                        return;
                    }
                }

                // nothing found
                restRequestService.sendLiveMessage("Nothing found");
            }
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
