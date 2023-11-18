package live.jmusic.balanceservice;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

@SpringBootApplication
@Slf4j
public class BalanceServiceApplication implements ApplicationRunner {

    public static void main(String[] args) {
        SpringApplication.run(BalanceServiceApplication.class, args);
    }


    @Value("${media.pipe.input}")
    private String INPUT_PIPE;

    @Value("${media.pipe.output_pipe1}")
    private String OUTPUT_PIPE1;

    @Value("${media.pipe.output_pipe2}")
    private String OUTPUT_PIPE2;
    private boolean useFirstPipe = true;

    @Override
    public void run(ApplicationArguments args) throws Exception {

        int f = 1;
        while (true) {
            processFLV(INPUT_PIPE, (f % 2 != 0 ? OUTPUT_PIPE1 : OUTPUT_PIPE2));
            f++;
        }

    }

    ////////////////

    public static void processFLV(String inputPath, String outputPath) {
        try (
                FileInputStream inputFileStream = new FileInputStream(inputPath);
                DataInputStream dataInputStream = new DataInputStream(inputFileStream);
                FileOutputStream outputFileStream = new FileOutputStream(outputPath);
                DataOutputStream dataOutputStream = new DataOutputStream(outputFileStream);
        ) {
            // Read and process FLV file header
            byte[] headerBuffer = new byte[9];
            dataInputStream.readFully(headerBuffer);
            processFLVHeader(headerBuffer);

            // Write FLV file header to the output stream
            dataOutputStream.write(headerBuffer);

            // Process FLV tags
            processFLVTags(dataInputStream, dataOutputStream);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void processFLVHeader(byte[] headerBuffer) {
        // Implement FLV file header processing logic
        // For simplicity, just print the header information
        System.out.println("FLV File Header:");
        System.out.println("Signature: " + new String(headerBuffer, 0, 3));
        System.out.println("Version: " + headerBuffer[3]);
        System.out.println("Flags: " + headerBuffer[4]);
        System.out.println("DataOffset: " + ((headerBuffer[5] & 0xFF) << 24 | (headerBuffer[6] & 0xFF) << 16 | (headerBuffer[7] & 0xFF) << 8 | (headerBuffer[8] & 0xFF)));
        System.out.println("------------------------------");
    }

    private static void processFLVTags(DataInputStream dataInputStream, DataOutputStream dataOutputStream) throws IOException {
        while (true) {
            try {
                int previousTagSize = dataInputStream.readInt();
                int tagType = dataInputStream.readUnsignedByte();
                int dataSize = (dataInputStream.readUnsignedByte() << 16) | (dataInputStream.readUnsignedByte() << 8) | dataInputStream.readUnsignedByte();
                int timeStamp = dataInputStream.readUnsignedByte() | (dataInputStream.readUnsignedByte() << 8) | (dataInputStream.readUnsignedByte() << 16) | (dataInputStream.readUnsignedByte() << 24);
                int streamID = dataInputStream.readUnsignedByte() | (dataInputStream.readUnsignedByte() << 8) | (dataInputStream.readUnsignedByte() << 16);

                byte[] tagData = new byte[dataSize];
                dataInputStream.readFully(tagData);

                // Process FLV tag
                // processFLVTag(tagType, timeStamp, streamID, tagData);

                // Write processed data to output file
                dataOutputStream.writeInt(previousTagSize);
                dataOutputStream.writeByte(tagType);
                dataOutputStream.writeByte(dataSize >> 16);
                dataOutputStream.writeByte(dataSize >> 8);
                dataOutputStream.writeByte(dataSize);
                dataOutputStream.writeByte(timeStamp);
                dataOutputStream.writeByte(timeStamp >> 8);
                dataOutputStream.writeByte(timeStamp >> 16);
                dataOutputStream.writeByte(timeStamp >> 24);
                dataOutputStream.writeByte(streamID);
                dataOutputStream.writeByte(streamID >> 8);
                dataOutputStream.writeByte(streamID >> 16);
                dataOutputStream.write(tagData);
                dataOutputStream.flush();

            } catch (IOException e) {
                // Input stream is closed, break the loop
                break;
            }
        }
    }

    private static void processFLVTag(int tagType, int timeStamp, int streamID, byte[] tagData) {
        // Implement FLV tag processing logic
        // For simplicity, just print the tag information
        System.out.println("FLV Tag:");
        System.out.println("Tag Type: " + tagType);
        System.out.println("Data Size: " + tagData.length);
        System.out.println("Timestamp: " + timeStamp);
        System.out.println("Stream ID: " + streamID);
        System.out.println("------------------------------");
    }
}
