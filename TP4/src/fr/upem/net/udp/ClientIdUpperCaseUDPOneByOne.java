package fr.upem.net.udp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.DatagramChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static java.nio.file.StandardOpenOption.*;

public class ClientIdUpperCaseUDPOneByOne {

	private static final Logger logger = Logger.getLogger(ClientIdUpperCaseUDPOneByOne.class.getName());
	private static final Charset UTF8 = StandardCharsets.UTF_8;
	private static final int BUFFER_SIZE = 1024;

	private record Response(long id, String message) { }

	private final String inFilename;
	private final String outFilename;
	private final long timeout;
	private final InetSocketAddress server;
	private final DatagramChannel dc;
	private final SynchronousQueue<Response> queue = new SynchronousQueue<>();

	public static void usage() {
		System.out.println("Usage : ClientIdUpperCaseUDPOneByOne in-filename out-filename timeout host port ");
	}

	public ClientIdUpperCaseUDPOneByOne(String inFilename, String outFilename, long timeout, InetSocketAddress server)
			throws IOException {
		this.inFilename = Objects.requireNonNull(inFilename);
		this.outFilename = Objects.requireNonNull(outFilename);
		this.timeout = timeout;
		this.server = server;
		this.dc = DatagramChannel.open();
		dc.bind(null);
	}

	private void listenerThreadRun() {
		var bb = ByteBuffer.allocate(BUFFER_SIZE);
		for (;;) {
			bb.clear();
			try {
				dc.receive(bb);
				bb.flip();
				if (bb.remaining() < Long.BYTES) {
					continue;
				}

				var exchangeId = bb.getLong();
				var msg = UTF8.decode(bb).toString();
				queue.put(new Response(exchangeId, msg));
			} catch (InterruptedException | AsynchronousCloseException e) {
				logger.info("Listener thread interrupted.");
				return;
			} catch (IOException e) {
				logger.severe("IOException occured on listener.");
				throw new AssertionError(e);
			}
		}
	}

	private Optional<Response> retrieveResponse() throws InterruptedException {
		var begin = System.currentTimeMillis();
		var current = begin;
		Response response = null;

		while (current - begin < timeout) {
			if (response == null) {
				response = queue.poll(timeout - (current - begin), TimeUnit.MILLISECONDS);
			}
			current = System.currentTimeMillis();
		}

		return Optional.ofNullable(response);
	}

	public void launch() throws IOException, InterruptedException {
		try {
			var listenerThread = Thread.ofPlatform().start(this::listenerThreadRun);
			
			// Read all lines of inFilename opened in UTF-8
			var lines = Files.readAllLines(Path.of(inFilename), UTF8);

			var upperCaseLines = new ArrayList<String>();

			var exchangeId = 0L;
			var bb = ByteBuffer.allocate(BUFFER_SIZE);
			for (var line : lines) {
				var encode = UTF8.encode(line);
				bb.clear();

				bb.putLong(exchangeId).put(encode);
				bb.flip();
				dc.send(bb, server);

				for (;;) {
					var response = retrieveResponse().orElse(null);
					if (response == null) {
						logger.info("Timeout expired. Sending " + line + " again (id: " + exchangeId + ")");
						bb.flip();
						dc.send(bb, server);
						continue;
					}
					if (response.id() != exchangeId) {
						logger.info("Invalid id, dropping packet.");
						continue;
					}

					upperCaseLines.add(response.message());
					exchangeId++;
					break;
				}
			}

			listenerThread.interrupt();
			Files.write(Paths.get(outFilename), upperCaseLines, UTF8, CREATE, WRITE, TRUNCATE_EXISTING);
		} finally {
			dc.close();
		}
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		if (args.length != 5) {
			usage();
			return;
		}

		var inFilename = args[0];
		var outFilename = args[1];
		var timeout = Long.parseLong(args[2]);
		var server = new InetSocketAddress(args[3], Integer.parseInt(args[4]));

		// Create client with the parameters and launch it
		new ClientIdUpperCaseUDPOneByOne(inFilename, outFilename, timeout, server).launch();
	}
}
