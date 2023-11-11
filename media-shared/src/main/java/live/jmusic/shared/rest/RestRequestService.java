package live.jmusic.shared.rest;

import live.jmusic.shared.model.RotationItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

@Service
public class RestRequestService {

    @Value("${media.stream.service.url:http://localhost:8088}")
    private String streamServiceUrl;

    @Value("${media.core.service.uri:http://localhost:8081}")
    public String coreServiceUri;

    public void requestRestart() {
        RestClient.recoverableRequest(streamServiceUrl + "/media/restart", String.class, s -> {} );
    }

    public void requestNow(Consumer<RotationItem> onSuccess) {
        RestClient.recoverableRequest(coreServiceUri + "/media/now", RotationItem.class, onSuccess);
    }

}
