package live.jmusic.streamservice.util;

import live.jmusic.shared.model.RotationItem;
import live.jmusic.streamservice.util.videofilter.VideoFilterBuilder;

public class FfmpegHelper {

    public static String buildVideoFilter(RotationItem currentItem) {
        return VideoFilterBuilder.create()
                .withScale(1920, 1080)
                .withPad(1920, 1080)
                .withDrawText()
                .withText(currentItem.getMediaItem().getTitle())
                .withFontFile("/usr/share/fonts/truetype/wqy/wqy-microhei.ttc")
                .withPosition("2", "2")
                .withFontSize(24)
                .withFontColor("white")
                .withBox("black")
                .withBoxBorder(4)
                .buildDrawText()

                .withDrawText()
                .withTextFile("live.txt")
                .withFontFile("/usr/share/fonts/truetype/wqy/wqy-microhei.ttc")
                .withPosition("2", "H-th")
                .withFontSize(24)
                .withFontColor("white")
                .withBox("black@0.4")
                .withBoxBorder(4)
                .withReload(1)
                .buildDrawText()

                .withSubtitles(currentItem.getMediaItem().fullpath)

                .build();
    }

    public static String[] getFfmpegPreProdCommand(String ffmpeg, Long startTime, String inputFile,
                                                   String videoFilter, String audioFilter) {
        return new String[]{
                ffmpeg,
                "-ss",
                String.format("%sms", startTime),
                "-re",
                "-i", inputFile,
                "-b:a", "256k",
                "-c:a", "aac",
                "-ar", "44100",
                "-ac", "2",
                "-vsync", "1",
                "-async", "1",
                "-flags", "low_delay",
                "-strict", "strict",
                "-avioflags", "direct",
                "-fflags", "+discardcorrupt",
                "-probesize", "32",
                "-analyzeduration", "0",
                "-movflags",
                "+faststart",
                "-bsf:v", "h264_mp4toannexb",
                "-c:v", "h264",
                "-vf", videoFilter,
                "-r", "30",
                "-af", audioFilter,
                "-g", "60",
                "-b:v", "3500k",
                "-maxrate:v", "3500k",
                "-minrate:v", "3500k",
                "-f", "flv",
                "pipe:1"
        };
    }


    public static String[] getFfmpegProdCommand(String ffmpeg, Long startTime, String inputFile,
                                                String videoFilter, String audioFilter) {
        return new String[]{
                ffmpeg,
                "-init_hw_device", "qsv=hw",
                "-filter_hw_device", "hw",
                "-hwaccel", "qsv",
                "-hwaccel_output_format", "qsv",
                "-ss", String.format("%sms", startTime),
                "-re",
                "-i", inputFile,
                "-flags", "low_delay",
                "-strict", "experimental",
                "-avioflags", "direct",
                "-fflags", "+discardcorrupt",
                "-probesize", "32",
                "-analyzeduration", "0",
                "-vsync", "1",
                "-async", "1",
                "-c:v", "h264_qsv",
                "-bf", "2",
                "-b:v", "4300k",
                "-maxrate:v", "5300k",
                "-bufsize:v", "4300k",
                "-preset", "veryslow",
                "-vf", videoFilter + ",format=nv12,hwupload=extra_hw_frames=64,deinterlace_qsv",
                "-r", "30",
                "-b:a", "320k",
                "-c:a", "aac",
                "-ar", "44100",
                "-ac", "2",
                "-af", audioFilter,
                "-level:v", "4.2",
                "-profile:v", "high",
                "-g", "60",
                "-strict", "-2",
                "-movflags", "+faststart",
                "-bsf:v", "h264_mp4toannexb",
                "-copytb", "1",
                "-f", "flv",
                "pipe:1"
        };
    }
}
