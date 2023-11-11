package live.jmusic.shared.model;

import lombok.Data;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import java.util.ArrayList;

@Data
@Entity
public class MediaItem {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    public Long id;

    public String fullpath;
    public String title;
    public ArrayList<String> tags;
    public String volume;
    public Long length;
    public boolean isProcessed;
}
