package live.jmusic.dashboard;

import java.util.List;

/** A managed service: how to start it, how to recognise its processes, where its logs and health are. */
public class ServiceDef {
    public final String name;          // id used in the API, e.g. "media-service"
    public final String title;         // human readable
    public final String group;         // "stream", "core", "legacy"
    public final String description;
    public final List<String> start;   // command, run in the app dir
    public final List<String> patterns; // command-line substrings of its processes, stopped in this order
    public final List<String> logs;    // file names in the logs dir
    public final String healthUrl;     // null = no health check
    public final String jar;           // deployable artifact: file name in the deploy dir (null = not deployable)
    public final String target;        // where the artifact is installed, relative to the app dir
    public final String conflictsWith; // service that must not run at the same time
    public final boolean self;         // the dashboard itself: cannot be stopped from here

    public ServiceDef(String name, String title, String group, String description, List<String> start, List<String> patterns,
                      List<String> logs, String healthUrl, String jar, String conflictsWith, boolean self) {
        this.name = name;
        this.title = title;
        this.group = group;
        this.description = description;
        this.start = start;
        this.patterns = patterns;
        this.logs = logs;
        this.healthUrl = healthUrl;
        this.jar = jar;
        this.target = jar == null ? null : (jar.endsWith(".jar") ? jar : "ext/" + jar);
        this.conflictsWith = conflictsWith;
        this.self = self;
    }
}
