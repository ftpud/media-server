package live.jmusic.liveservice;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;

public class Util {
    public static void atomicWrite(String f, String input) throws IOException {
        File file = new File(f);
        String dir = file.getParent();

        // Generate a random string of size 32 for the tmp file.
        SecureRandom random = new SecureRandom();
        String tmp_name = "temp_live";//new BigInteger(130, random).toString(32);
        String tmp_path = dir + "/" + tmp_name;

        PrintWriter pw = new PrintWriter(tmp_path);
        pw.write(input);
        pw.close();
        Files.move(Paths.get(tmp_path), Paths.get(f), StandardCopyOption.ATOMIC_MOVE);
    }

}
