package fr.upem.net.tcp.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;

public class HTTPReader {

    private final Charset ASCII_CHARSET = StandardCharsets.US_ASCII;
    private final SocketChannel sc;
    private final ByteBuffer buffer;

    public HTTPReader(SocketChannel sc, ByteBuffer buffer) {
        this.sc = sc;
        this.buffer = buffer;
    }

    /**
     * @return The ASCII string terminated by CRLF without the CRLF
     * <p>
     * The method assume that buffer is in write mode and leaves it in
     * write mode The method process the data from the buffer and if necessary
     * will read more data from the socket.
     * @throws IOException HTTPException if the connection is closed before a line
     *                     could be read
     */
    public String readLineCRLF() throws IOException {
        buffer.flip();
        var stringBuilder = new StringBuilder();
        try {
            // Awful complexity
            // while (stringBuilder.toString().endsWith("\r\n")) {
            while (stringBuilder.length() < 2 || stringBuilder.charAt(stringBuilder.length() - 2) != '\r' || stringBuilder.charAt(stringBuilder.length() - 1) != '\n') {
                if (!buffer.hasRemaining()) {
                    buffer.clear();
                    if (sc.read(buffer) == -1) {
                        throw new HTTPException();
                    }
                    buffer.flip();
                }
                var s = (char) buffer.get();
                stringBuilder.append(s);
            }
        } finally {
            buffer.compact();
        }

        stringBuilder.setLength(stringBuilder.length() - 2);
        return stringBuilder.toString();
    }

    /**
     * @return The HTTPHeader object corresponding to the header read
     * @throws IOException HTTPException if the connection is closed before a header
     *                     could be read or if the header is ill-formed
     */
    public HTTPHeader readHeader() throws IOException {
        var header = new ArrayList<String>();
        var map = new HashMap<String, String>();

        String tmp;
        // while ((tmp = readLineCRLF()).length() > 1) {
        while (!(tmp = readLineCRLF()).isEmpty()) {
            header.add(tmp);
        }

        var response = header.removeFirst();
        header.removeLast();

        for (var str : header) {
            var split = str.split(": ", 2);
            // Not very elegant
            // map.compute(split[0], (k, v) -> v == null ? split[1] : v + "; " + split[1]);
            map.merge(split[0], split[1], (v1, v2) -> v1 + "; " + v2);
        }

        return HTTPHeader.create(response, map);
    }

    /**
     * @param size
     * @return a ByteBuffer in write mode containing size bytes read on the socket
     * <p>
     * The method assume that buffer is in write mode and leaves it in
     * write mode The method process the data from the buffer and if necessary
     * will read more data from the socket.
     * @throws IOException HTTPException is the connection is closed before all
     *                     bytes could be read
     */
    public ByteBuffer readBytes(int size) throws IOException {
        buffer.flip();

        var readBuffer = ByteBuffer.allocate(size);
        while (readBuffer.hasRemaining()) {
            if (!buffer.hasRemaining()) {
                buffer.clear();
                if (sc.read(buffer) == -1) {
                    throw new HTTPException();
                }
                buffer.flip();
            }

            readBuffer.put(buffer.get());
        }

        buffer.compact();
        return readBuffer;
    }

    /**
     * @return a ByteBuffer in write-mode containing a content read in chunks mode
     * @throws IOException HTTPException if the connection is closed before the end
     *                     of the chunks if chunks are ill-formed
     */
    public ByteBuffer readChunks() throws IOException {
        var bytes = ByteBuffer.allocate(0);
        var size = -1;

        try {
            while (size != 0) {
                var elt = readLineCRLF();
                size = Integer.parseInt(elt, 16);
                var line = readBytes(size);
                readBytes(2);
                var newBuffer = ByteBuffer.allocate(bytes.flip().remaining() + line.flip().remaining());
                newBuffer.put(bytes);
                newBuffer.put(line);
                bytes = newBuffer;
            }
        } catch (NumberFormatException e) {
            throw new HTTPException("Chunked body is ill-formed");
        }

        return bytes;
    }

    public static void main(String[] args) throws IOException {
        var charsetASCII = Charset.forName("ASCII");
        var request = "GET / HTTP/1.1\r\n" + "Host: www.w3.org\r\n" + "\r\n";
        var sc = SocketChannel.open();
        sc.connect(new InetSocketAddress("www.w3.org", 80));
        sc.write(charsetASCII.encode(request));
        var buffer = ByteBuffer.allocate(50);
        var reader = new HTTPReader(sc, buffer);
        System.out.println(reader.readLineCRLF());
        System.out.println(reader.readLineCRLF());
        System.out.println(reader.readLineCRLF());
        sc.close();

        buffer = ByteBuffer.allocate(50);
        sc = SocketChannel.open();
        sc.connect(new InetSocketAddress("www.w3.org", 80));
        reader = new HTTPReader(sc, buffer);
        sc.write(charsetASCII.encode(request));
        System.out.println(reader.readHeader());
        sc.close();

        buffer = ByteBuffer.allocate(50);
        sc = SocketChannel.open();
        sc.connect(new InetSocketAddress("igm.univ-mlv.fr", 80));
        request = "GET /coursprogreseau/ HTTP/1.1\r\n" + "Host: igm.univ-mlv.fr\r\n" + "\r\n";
        reader = new HTTPReader(sc, buffer);
        sc.write(charsetASCII.encode(request));
        var header = reader.readHeader();
        System.out.println(header);
        var content = reader.readBytes(header.getContentLength());
        content.flip();
        System.out.println(header.getCharset().orElse(Charset.forName("UTF8")).decode(content));
        sc.close();

        buffer = ByteBuffer.allocate(50);
        request = "GET / HTTP/1.1\r\n" + "Host: www.u-pem.fr\r\n" + "\r\n";
        sc = SocketChannel.open();
        sc.connect(new InetSocketAddress("www.u-pem.fr", 80));
        reader = new HTTPReader(sc, buffer);
        sc.write(charsetASCII.encode(request));
        header = reader.readHeader();
        System.out.println(header);
        content = reader.readChunks();
        content.flip();
        System.out.println(header.getCharset().orElse(Charset.forName("UTF8")).decode(content));
        sc.close();
    }
}
