package live.jmusic.chatservice.utils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ExceptionUtil {

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    public static void recoverable(ThrowingRunnable throwing) {
        try {
            throwing.run();
        } catch (Exception ex) {
            log.debug("Recoverable.", ex);
        }
    }
}
