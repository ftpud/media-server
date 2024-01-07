package live.jmusic.shared.rest;

import live.jmusic.shared.model.MediaItem;
import live.jmusic.shared.model.RotationItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Service
public class RestRequestService {

    @Value("${media.stream.service.url:http://localhost:8088}")
    private String streamServiceUrl;

    @Value("${media.core.service.uri:http://localhost:8084}")
    public String coreServiceUri;

    @Value("${media.live.service.uri:http://localhost:8089}")
    public String liveServiceUri;

    public void requestRestart() {
        RestClient.recoverableRequest(streamServiceUrl + "/media/restart", String.class, s -> {
        });
    }

    public void requestNow(Consumer<RotationItem> onSuccess) {
        RestClient.recoverableRequest(coreServiceUri + "/media/now", RotationItem.class, onSuccess);
    }

    public void requestEnqueue(String item, Consumer<MediaItem> onSuccess) {
        RestClient.recoverablePostRequest(coreServiceUri + "/media/enqueue/", item, MediaItem.class, onSuccess);
    }

    public void requestEnqueueNext(String item, Consumer<MediaItem> onSuccess) {
        RestClient.recoverablePostRequest(coreServiceUri + "/media/enqueue/next/", item, MediaItem.class, onSuccess);
    }

    public void requestSearch(String item, Consumer<MediaItem[]> onSuccess) {
        RestClient.recoverablePostRequest(coreServiceUri + "/media/search/", item, MediaItem[].class, onSuccess);
    }

    public void requestList(Consumer<MediaItem[]> onSuccess) {
        RestClient.recoverableRequest(coreServiceUri + "/media/list", MediaItem[].class, onSuccess);
    }

    public void requestSeek(String time, Consumer<String> onSuccess) {
        RestClient.recoverableRequest(coreServiceUri + "/media/seek/" + time, String.class, onSuccess);
    }

    public void requestNext(Consumer<RotationItem> onSuccess) {
        RestClient.recoverableRequest(coreServiceUri + "/media/next", RotationItem.class, onSuccess);
    }

    public void sendLiveMessage(String slot, String message) {
        RestClient.recoverablePostRequest(liveServiceUri + String.format("/live/message/__%s", slot), message.concat(" "), String.class, r -> {
        });
    }


    public void addTag(String tag, Consumer<String> onSuccess) {
        RestClient.recoverablePostRequest(coreServiceUri + String.format("/media/tags/add"), tag, String.class, onSuccess);
    }

    public void removeTag(String tag, Consumer<String> onSuccess) {
        RestClient.recoverablePostRequest(coreServiceUri + String.format("/media/tags/remove"), tag, String.class, onSuccess);
    }

    public void listTags(Consumer<String[]> onSuccess) {
        RestClient.recoverableRequest(coreServiceUri + String.format("/media/tags/list"), String[].class, onSuccess);
    }

    long msgNum = 0;
    public void sendLiveMessage(String message) {
        msgNum++;
        RestClient.recoverablePostRequest(liveServiceUri + String.format("/live/message/%s", "__slot" + msgNum), message.concat(" "), String.class, r -> {
        });
    }

    public void requestVolumeFix(Long id) {
        RestClient.recoverableRequest(coreServiceUri + "/media/fixVolume/" + id, String.class, r -> {
        });
    }

}
