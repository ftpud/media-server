package live.jmusic.mediaservice.service;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Service
public class ChronoService {
    private static LocalDateTime startTime = LocalDateTime.now();

    private static long offset = 0;

    public static void pushOffset(long num) {
        offset += num;
    }

    public static Long getTimePointer() {
        return offset + ChronoUnit.MILLIS.between(startTime, LocalDateTime.now());
    }

    /** Re-anchor the rotation position (used to sync with the external player). */
    public static synchronized void setPointer(long ms) {
        startTime = LocalDateTime.now();
        offset = ms;
    }

    public static void resetTime() {
        startTime = LocalDateTime.now();
        offset = 0;
    }
}
