package live.jmusic.streamservice.util.videofilter;

import live.jmusic.streamservice.util.SubtitlesHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class VideoFilterBuilder {

    List<String> filterList = new ArrayList<>();

    public static VideoFilterBuilder create() {
        return new VideoFilterBuilder();
    }

    public VideoFilterBuilder withScale(int width, int height) {
        filterList.add(
                String.format("scale=(iw*sar)*min(%s/(iw*sar)\\,%s/ih):ih*min(%s/(iw*sar)\\,%s/ih)", width, height, width, height));
        return this;
    }

    public VideoFilterBuilder withPad(int width, int height) {
        filterList.add(
                String.format("pad=%s:%s:(%s-iw*min(%s/iw\\,%s/ih))/2:(%s-ih*min(%s/iw\\,%s/ih))/2", width, height, width, width, height, height, width, height));
        return this;
    }


    public VideoFilterDrawTextBuilder withDrawText() {
        return new VideoFilterDrawTextBuilder(this);
    }

    public VideoFilterBuilder withSubtitles(String filename) {
        Optional<String> subtitleFilter = SubtitlesHelper.getSubtitlesVideoFilter(filename);
        if (subtitleFilter.isPresent()) {
            filterList.add(subtitleFilter.get());
        }
        return this;
    }

    void putCustomString(String string) {
        filterList.add(string);
    }

    public String build() {
        return filterList.stream().collect(Collectors.joining(","));
    }

}
