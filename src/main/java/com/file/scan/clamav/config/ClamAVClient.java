package com.file.scan.clamav.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import com.file.scan.clamav.dto.*;
import com.file.scan.clamav.exceptions.*;
import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

public class ClamAVClient {

    private static Logger LOGGER = LoggerFactory.getLogger(ClamAVClient.class);

    private String hostName;
    private int port;
    private int timeout;

    // "do not exceed StreamMaxLength as defined in clamd.conf, otherwise clamd will reply with INSTREAM size limit exceeded and close the connection."
    private static final int CHUNK_SIZE = 2048;
    private static final int DEFAULT_TIMEOUT = 2000;
    private static final int PONG_REPLY_LEN = 4;

    /**
     * @param hostName The hostname of the server running clamav-daemon
     * @param port The port that clamav-daemon listens to(By default it might not listen to a port. Check your clamav configuration).
     * @param timeout zero means infinite timeout. Not a good idea, but will be accepted.
     */
    public ClamAVClient(String hostName, int port, int timeout)  {
        if (timeout < 0) {
            throw new IllegalArgumentException("Negative timeout value does not make sense.");
        }
        this.hostName = hostName;
        this.port = port;
        this.timeout = timeout;
    }

    public ClamAVClient(String hostName, int port) {
        this(hostName, port, DEFAULT_TIMEOUT);
    }

    /**
     * Run PING command to clamd to test it is responding.
     *
     * @return true if the server responded with proper ping reply.
     */
    public boolean ping() throws IOException {
        Socket socket = null;
        OutputStream outStream = null;
        InputStream inStream = null;
        try {
            socket = new Socket(hostName,port);
            outStream = socket.getOutputStream();
            socket.setSoTimeout(timeout);
            outStream.write("zPING\0".getBytes(StandardCharsets.US_ASCII));
            outStream.flush();
            byte[] b = new byte[PONG_REPLY_LEN];
            inStream = socket.getInputStream();
            int copyIndex = 0;
            int readResult;
            do {
                readResult = inStream.read(b, copyIndex, Math.max(b.length - copyIndex, 0));
                copyIndex += readResult;
            } while (readResult > 0);
            return Arrays.equals(b, "PONG".getBytes(StandardCharsets.US_ASCII));
        }finally {
            closeInputStream(inStream);
            closeOutputStream(outStream);
            closeSocket(socket);
        }
    }

    private void closeInputStream(InputStream inStream) {
        try {
            if(inStream != null)
                inStream.close();
        } catch(IOException e) {
            LOGGER.error("Exception occurred while closing input streams = {} ", e.getMessage());
        }
    }

    private void closeOutputStream(OutputStream outStream) {
        try {
            if(outStream != null)
                outStream.close();
        } catch(IOException e) {
            LOGGER.error("Exception occurred while closing output streams = {} ", e.getMessage());
        }
    }

    /**
     * Streams the given data to the server in chunks. The whole data is not kept in memory.
     * This method is preferred if you don't want to keep the data in memory, for instance by scanning a file on disk.
     * Since the parameter InputStream is not reset, you can not use the stream afterwards, as it will be left in a EOF-state.
     * <p>
     * Opens a socket and reads the reply. Parameter input stream is NOT closed.
     *
     * @param is data to scan. Not closed by this method!
     * @param fileScanResponseDto
     * @return server reply
     */
    public byte[] scan(InputStream is, FileScanResponseDto fileScanResponseDto) throws IOException, NoSuchAlgorithmException {
        MessageDigest md5Digest = MessageDigest.getInstance("MD5");
        Socket socket = null;
        OutputStream outStream = null;
        InputStream inStream = null;
        try {
            socket = new Socket(hostName,port);
            outStream = new BufferedOutputStream(socket.getOutputStream());
            LOGGER.info("1Socket information = {} connected = {} ", socket, socket.isConnected());
            socket.setSoTimeout(timeout);

            // handshake
            outStream.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            outStream.flush();
            LOGGER.info("2Socket information = {} connected = {} ", socket, socket.isConnected());
            byte[] chunk = new byte[CHUNK_SIZE];

            try {
                inStream = socket.getInputStream();
                // send data
                int read = is.read(chunk);
                while (read >= 0) {
                    // The format of the chunk is: '<length><data>' where <length> is the size of the following data in bytes expressed as a 4 byte unsigned
                    // integer in network byte order and <data> is the actual chunk. Streaming is terminated by sending a zero-length chunk.
                    byte[] chunkSize = ByteBuffer.allocate(4).putInt(read).array();

                    outStream.write(chunkSize);
                    outStream.write(chunk, 0, read);
                    md5Digest.update(chunk, 0, read);
                    if (inStream.available() > 0) {
                        // reply from server before scan command has been terminated.
                        byte[] reply = assertSizeLimit(readAll(inStream));
                        throw new IOException("Scan aborted. Reply from server: " + new String(reply, StandardCharsets.US_ASCII));
                    }
                    read = is.read(chunk);
                }

                // terminate scan
                outStream.write(new byte[]{0,0,0,0});
                outStream.flush();

                //Get the hash's bytes
                byte[] bytes = md5Digest.digest();
                StringBuilder sb = new StringBuilder();
                for(int i=0; i< bytes.length ;i++)
                {
                    sb.append(Integer.toString((bytes[i] & 0xff) + 0x100, 16).substring(1));
                }
                if(!StringUtils.isEmpty(sb.toString())) {
                    fileScanResponseDto.setHash(sb.toString());
                }
                // read reply
                return assertSizeLimit(readAll(inStream));
            } finally {

            }
        }finally {
            closeInputStream(inStream);
            closeOutputStream(outStream);
            closeSocket(socket);
        }
    }

    private void closeSocket(Socket socket) {
        try {
            if(socket != null)
                socket.close();
        } catch (IOException e) {
            LOGGER.error("Exception occurred while closing socket = {} ", e.getMessage());
        }
    }

    /**
     * Interpret the result from a  ClamAV scan, and determine if the result means the data is clean
     *
     * @param reply The reply from the server after scanning
     * @return true if no virus was found according to the clamd reply message
     */
    public static boolean isCleanReply(byte[] reply) {
        String result = new String(reply, StandardCharsets.US_ASCII);
        LOGGER.info("Clam AV Response = {} ", result);
        return (result.contains("OK") && !result.contains("FOUND"));
    }


    private byte[] assertSizeLimit(byte[] reply) {
        String r = new String(reply, StandardCharsets.US_ASCII);
        if (r.startsWith("INSTREAM size limit exceeded."))
            throw new ClamAVSizeLimitException("Clamd size limit exceeded. Full reply from server: " + r);
        return reply;
    }

    // reads all available bytes from the stream
    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream tmp = new ByteArrayOutputStream();

        byte[] buf = new byte[2000];
        int read = 0;
        do {
            read = is.read(buf);
            tmp.write(buf, 0, read);
        } while ((read > 0) && (is.available() > 0));
        return tmp.toByteArray();
    }
}
