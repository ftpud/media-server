package live.jmusic.shared.model;

import lombok.Data;

import javax.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Data
@Entity
public class MediaItem {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    public Long id;

    public String fullpath;
    public String title;

    @ElementCollection
    public List<String> tags = new ArrayList<>();

    public String volume;
    public Long length;
    public boolean isProcessed;
}
