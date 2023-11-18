package live.jmusic.liveservice.service;

import live.jmusic.liveservice.Util;
import live.jmusic.liveservice.model.LiveMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class LiveService {

    private static int DEFAULT_TIMEOUT = 15;
    List<LiveMessage> messages = new ArrayList<>();


    @Value("${media.live.file}")
    String mediaLiveFile;

    public void putMessage(String message) {
        putMessage(message, message);
    }

    public void putMessage(String slot, String message) {
        Optional<LiveMessage> slotMessage =
                messages.stream()
                        .filter(m -> m.getSlot().equals(slot))
                        .findFirst();

        if (slotMessage.isPresent()) {
            slotMessage.get().setTimeout(DEFAULT_TIMEOUT);
            slotMessage.get().setText(message);
        } else {
            messages.add(new LiveMessage(slot, message, DEFAULT_TIMEOUT));
        }
    }


    public void run() {
        try {
            String lastWrittenText = "";
            while (true) {
                Thread.sleep(1000);
                String newLive = "";
                int size = messages.size();

                for (int i = 0; i < size; i++) {
                    LiveMessage m = messages.get(i);
                    m.setTimeout(m.getTimeout() - 1);
                    newLive += m.getText().replace("%", "\\%") + "\n";
                }

                for (int i = 0; i < messages.size(); i++) {
                    LiveMessage m = messages.get(i);
                    if (m.getTimeout() < 0) {
                        messages.remove(i);
                        i--;
                    }
                }

                if (newLive.isEmpty()) {
                    newLive = " ";
                } else if (newLive.endsWith("\n")) {
                    newLive = newLive.substring(0, newLive.length() - 1);
                }


                if (!lastWrittenText.equals(newLive)) {
                    Util.atomicWrite(mediaLiveFile, newLive);
                }
                lastWrittenText = newLive;

            }
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }
}
