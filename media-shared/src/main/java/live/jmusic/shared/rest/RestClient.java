package live.jmusic.shared.rest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Slf4j
public class RestClient {

    private static RestTemplate recoverableRestTemplate = new RestTemplate();

    public static <T> void recoverablePostRequest(String url, String postBody, Class<T> clazz, Consumer<T> onSuccess) {
        log.info("Requesting POST " + url);
        recoverableRequest(onSuccess, () -> recoverableRestTemplate.postForEntity(url, postBody, clazz));
    }

    public static <T> void recoverableRequest(String url, Class<T> clazz, Consumer<T> onSuccess) {
        log.info("Requesting GET " + url);
        recoverableRequest(onSuccess, () -> recoverableRestTemplate.getForEntity(url, clazz));
    }

    /**
    private static <T> void recoverableRequest(String url, Class<T> clazz, Consumer<T> onSuccess, Supplier<ResponseEntity<T>> request) {
        for (int i = 1; i < 10; i++) {
            ResponseEntity<T> response = null;
            try {
                //response = recoverableRestTemplate.getForEntity(url, clazz);
                response = request.get();
            } catch (HttpClientErrorException | ResourceAccessException e) {
                // log.info("Can't access rest resource: {}", url);
            }
            if (response != null) {
                if (response.getStatusCode() == HttpStatus.OK) {
                    onSuccess.accept(response.getBody());
                    return;
                }
            }

            try {
                log.info("Failed response: {}", response);
                TimeUnit.SECONDS.sleep(5);
                log.info("Retry {}...", i);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

        }

        throw new RuntimeException("Can not request resource " + url);
    }
     **/

    private static <T> void recoverableRequest(Consumer<T> onSuccess, Supplier<ResponseEntity<T>> request) {
        for (int i = 1; i < 10; i++) {
            ResponseEntity<T> response = null;
            try {
                //response = recoverableRestTemplate.getForEntity(url, clazz);
                response = request.get();
            } catch (HttpClientErrorException | ResourceAccessException e) {
                log.info("Can't access rest resource");
            }
            if (response != null) {
                if (response.getStatusCode() == HttpStatus.OK) {
                    onSuccess.accept(response.getBody());
                    return;
                }
            }

            try {
                log.info("Failed response: {}", response);
                TimeUnit.SECONDS.sleep(5);
                log.info("Retry {}...", i);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

        }

        throw new RuntimeException("Can not request resource");
    }

}
