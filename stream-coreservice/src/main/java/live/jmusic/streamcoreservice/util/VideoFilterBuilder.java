package live.jmusic.streamcoreservice.util;

import java.util.ArrayList;
import java.util.List;
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


    //public VideoFilterBuilder withDrawText(String text, int fontsize, int x, int y) {
    //    filterList.add(
    //            String.format("drawtext=text='%s':fontcolor=white@0.8:fontfile=/usr/share/fonts/truetype/wqy/wqy-microhei.ttc:fontsize=%s:x=%s:y=%s:box=1:boxcolor=black@0.4:boxborderw=4", text, fontsize, x, y));
    //    return this;
    //}

    public VideoFilterDrawTextBuilder withDrawText() {
        return new VideoFilterDrawTextBuilder(this);
    }


    void putCustomString(String string) {
        filterList.add(string);
    }

    public String build() {
        return filterList.stream().collect(Collectors.joining(","));
    }

}
