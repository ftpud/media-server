package live.jmusic;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RegexTest {
    @Test
    public void regexTest() {
        String msg = "42[\"/chat/message\",{\"id\":1524079167,\"channel\":\"stream/56592\",\"from\":{\"id\":56592,\"name\":\"bober12\",\"color\":0},\"to\":null,\"text\":\"789\",\"type\":\"message\",\"time\":1699899145,\"store\":{\"bonuses\":[],\"subscriptions\":[],\"icon\":0},\"parentId\":0,\"anonymous\":false}]";
        String regex = "^\\d+\\[\\\"(.+)\\\",(\\{.+\\})\\]$";

        Pattern p = Pattern.compile(regex);
        Matcher m = p.matcher(msg);


        m.find();
        System.out.println(m.group(1));
        System.out.println(m.group(2));
        assertEquals(2, m.groupCount());
    }


    @Test
    public void testRegex2() {
        String msg = "@q 123";
        String regex = "q( (.+))";

        Pattern p = Pattern.compile(regex);
        Matcher m = p.matcher(msg);
        // List<String> l = new ArrayList<>();
        //m.find();
        m.find();
        //var l = m.results().collect(Collectors.toList());
        // if (l.size() > 1 ) {
        //    System.out.println(l.get(0));
        //    System.out.println(l.get(1));
       // }

        System.out.println(m.group(0));
        System.out.println(m.group(1));
        System.out.println(m.group(2));
    }

    @Test
    public void attrTest() throws Exception {
       BasicFileAttributes attr = Files.readAttributes((new File("C:\\Users\\bober\\Downloads\\big_file.mp4")).toPath(), BasicFileAttributes.class);
        System.out.println("");
        FileTime s = attr.lastModifiedTime();
       System.out.println("sdf");
    }
}

