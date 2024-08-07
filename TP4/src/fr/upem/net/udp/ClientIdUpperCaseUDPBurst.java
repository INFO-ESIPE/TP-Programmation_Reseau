package fr.upem.net.udp;


import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.DatagramChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.logging.Logger;
import java.util.stream.IntStream;

public class ClientIdUpperCaseUDPBurst {
  private static final Logger logger = Logger.getLogger(ClientIdUpperCaseUDPBurst.class.getName());
  private static final Charset UTF8 = StandardCharsets.UTF_8;
  private static final int BUFFER_SIZE = 1024;
  private final List<String> lines;
  private final int nbLines;
  private final String[] upperCaseLines; //
  private final int timeout;
  private final String outFilename;
  private final InetSocketAddress serverAddress;
  private final DatagramChannel dc;
  private final AnswersLog answersLog;         // Thread-safe structure keeping track of missing responses

  public static void usage() {
    System.out.println("Usage : ClientIdUpperCaseUDPBurst in-filename out-filename timeout host port ");
  }

  public ClientIdUpperCaseUDPBurst(List<String> lines, int timeout, InetSocketAddress serverAddress, String outFilename) throws IOException {
    this.lines = lines;
    this.nbLines = lines.size();
    this.timeout = timeout;
    this.outFilename = outFilename;
    this.serverAddress = serverAddress;
    this.dc = DatagramChannel.open();
    dc.bind(null);
    this.upperCaseLines = new String[nbLines];
    this.answersLog = new AnswersLog(nbLines);
  }

  private void senderThreadRun() {
    var linesBuffers = IntStream.range(0, nbLines).mapToObj(i -> {
      var bb = ByteBuffer.allocate(BUFFER_SIZE);
      return bb.putLong(i)
              .put(UTF8.encode(lines.get(i)));
    }).toArray(ByteBuffer[]::new);

    for (;;) {
      try {
        for (var index : answersLog.remaining()) {
          System.out.println("Sending line " + index + " to " + serverAddress + "...");
          dc.send(linesBuffers[index].flip(), serverAddress);
        }
        Thread.sleep(timeout);
      } catch (InterruptedException | AsynchronousCloseException e) {
        logger.info("Sender thread interrupted.");
        return;
      } catch (IOException e) {
        logger.severe("IOException in senderThreadRun: " + e.getMessage());
      }
    }
  }

  public void launch() throws IOException {
    var senderThread = Thread.ofPlatform().start(this::senderThreadRun);
    var bb = ByteBuffer.allocate(BUFFER_SIZE);

    while (!answersLog.isComplete()) {
      try {
        bb.clear();
        dc.receive(bb);
        bb.flip();
        if (bb.remaining() < Long.BYTES) {
          logger.info("Received a message with an invalid format. Skipping...");
          continue;
        }

        var id = bb.getLong();
        var msg = UTF8.decode(bb).toString();
        System.out.println("Received response for line " + id + ": " + "\"" + msg + "\".");

        answersLog.validate((int) id); // mmmmhh
        upperCaseLines[(int) id] = msg;
      } catch (IOException e) {
        logger.severe("IOException in launch: " + e.getMessage());
      }
    }

    Files.write(Paths.get(outFilename), Arrays.asList(upperCaseLines), UTF8, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

    senderThread.interrupt();
    dc.close();
  }

  public static void main(String[] args) throws IOException {
    if (args.length != 5) {
      usage();
      return;
    }

    String inFilename = args[0];
    String outFilename = args[1];
    int timeout = Integer.parseInt(args[2]);
    String host = args[3];
    int port = Integer.parseInt(args[4]);
    InetSocketAddress serverAddress = new InetSocketAddress(host, port);

    //Read all lines of inFilename opened in UTF-8
    List<String> lines = Files.readAllLines(Paths.get(inFilename), UTF8);
    var ee = new ArrayBlockingQueue<HashMap<String, String>>(lines.size());

    //Create client with the parameters and launch it
    ClientIdUpperCaseUDPBurst client = new ClientIdUpperCaseUDPBurst(lines, timeout, serverAddress, outFilename);
    client.launch();
  }

  private static class AnswersLog {
    private final BitSet bitSet;

    public AnswersLog(int size) {
      if (size < 1) { throw new IllegalArgumentException(); }
      this.bitSet = new BitSet(size);
      this.bitSet.flip(0, size);
    }

    public void validate(int id) {
      synchronized (bitSet) {
        bitSet.set(id, false);
      }
    }

    public int[] remaining() {
      synchronized (bitSet) {
        return bitSet.stream().toArray();
      }
    }

    public boolean isComplete() {
      synchronized (bitSet) {
        return bitSet.cardinality() == 0;
      }
    }
  }
}


