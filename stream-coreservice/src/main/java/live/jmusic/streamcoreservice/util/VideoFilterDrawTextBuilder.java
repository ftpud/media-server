package live.jmusic.streamcoreservice.util;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class VideoFilterDrawTextBuilder {
    List<String> filterList = new ArrayList<>();

    VideoFilterBuilder videoFilterBuilder;

    public VideoFilterDrawTextBuilder(VideoFilterBuilder videoFilterBuilder) {
        this.videoFilterBuilder = videoFilterBuilder;
    }

    public VideoFilterBuilder buildDrawText() {
        videoFilterBuilder.putCustomString("drawtext=" + filterList.stream().collect(Collectors.joining(":")));
        return videoFilterBuilder;
    }

    public VideoFilterDrawTextBuilder withFontColor(String color) {
        filterList.add("fontcolor=" + color);
        return this;
    }

    public VideoFilterDrawTextBuilder withFontFile(String file) {
        filterList.add("fontfile=" + file);
        return this;
    }

    public VideoFilterDrawTextBuilder withFontSize(int size) {
        filterList.add("fontsize=" + size);
        return this;
    }

    public VideoFilterDrawTextBuilder withPosition(String x, String y) {
        filterList.add("x=" + x);
        filterList.add("y=" + y);
        return this;
    }

    public VideoFilterDrawTextBuilder withBox(String boxColor) {
        filterList.add("box=1");
        filterList.add("boxcolor=" + boxColor);
        return this;
    }

    public VideoFilterDrawTextBuilder withBoxBorder(int w) {
        filterList.add("boxborderw=" + w);
        return this;
    }

    public VideoFilterDrawTextBuilder withReload(int reloadTime) {
        filterList.add("reload="+reloadTime);
        return this;
    }

    public VideoFilterDrawTextBuilder withText(String text) {
        filterList.add(String.format("text='%s'", text.replaceAll("'","")));
        return this;
    }

    public VideoFilterDrawTextBuilder withTextFile(String text) {
        filterList.add(String.format("textfile=%s", text));
        return this;
    }
}
