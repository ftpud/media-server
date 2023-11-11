package live.jmusic.streamcoreservice.model;

import lombok.Data;

import java.util.ArrayList;

@Data
public class MediaItem {

    public Long id;

    public String fullpath;
    public String title;
    public ArrayList<String> tags;
    public String volume;
    public Long length;
    public boolean isProcessed;
}
