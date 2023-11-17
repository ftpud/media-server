package live.jmusic.liveservice;

import java.io.*;
import java.math.BigInteger;
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
        String tmp_name = new BigInteger(130, random).toString(32);
        String tmp_path = dir + "/" + tmp_name;


        writeUnicode(tmp_path, input);

        Files.move(Paths.get(tmp_path), Paths.get(f), StandardCopyOption.ATOMIC_MOVE);
    }

    public static void writeUnicode(String fileName, String lines) {
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter(fileName));
            writer.write(lines);
            writer.close();
        } catch (Exception e) {
            System.out.println(e);
        }
    }
}
