package fr.upem.net.tcp.nonblocking;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ServerSumBetter {
	static private class Context {
		private final SelectionKey key;
		private final SocketChannel sc;
		private final ByteBuffer bufferIn = ByteBuffer.allocate(BUFFER_SIZE);
		private final ByteBuffer bufferOut = ByteBuffer.allocate(BUFFER_SIZE);
		private boolean closed = false;

		private Context(SelectionKey key) {
			this.key = key;
			this.sc = (SocketChannel) key.channel();
		}

		/**
		 * Process the content of bufferIn into bufferOut
		 *
		 * The convention is that both buffers are in write-mode before the call to
		 * process and after the call
		 *
		 */
		private void process() {
			var size = Integer.BYTES * 2;
			while (bufferIn.position() >= size && bufferOut.remaining() >= size) {
				bufferIn.flip();
				var res = bufferIn.getInt() + bufferIn.getInt();
				bufferOut.putInt(res);
				bufferIn.compact();
			}
		}

		/**
		 * Update the interestOps of the key looking only at values of the boolean
		 * closed and of both ByteBuffers.
		 *
		 * The convention is that both buffers are in write-mode before the call to
		 * updateInterestOps and after the call. Also it is assumed that process has
		 * been be called just before updateInterestOps.
		 */
		private void updateInterestOps() {
			var interestOps = 0x00;
			if (!closed && bufferIn.remaining() > Integer.BYTES * 2) {
				interestOps |= SelectionKey.OP_READ;
			}

			if (bufferOut.position() > Integer.BYTES) {
				interestOps |= SelectionKey.OP_WRITE;
			}

			if (interestOps == 0) {
				silentlyClose();
				return;
			}

			key.interestOps(interestOps);
		}

		private void silentlyClose() {
			try {
				sc.close();
			} catch (IOException e) {
				// ignore exception
			}
		}

		/**
		 * Performs the read action on sc
		 *
		 * The convention is that both buffers are in write-mode before the call to
		 * doRead and after the call
		 *
		 * @throws IOException
		 */
		private void doRead() throws IOException {
			if (sc.read(bufferIn) == -1) {
				closed = true;
			}

			process();
			updateInterestOps();
		}

		/**
		 * Performs the write action on sc
		 *
		 * The convention is that both buffers are in write-mode before the call to
		 * doWrite and after the call
		 *
		 * @throws IOException
		 */
		private void doWrite() throws IOException {
			bufferOut.flip();
			sc.write(bufferOut);
			if (closed) {
				silentlyClose();
				return;
			}

			bufferOut.compact();
			updateInterestOps();
		}
	}

	private static final int BUFFER_SIZE = 1024;
	private static final Logger logger = Logger.getLogger(ServerSumBetter.class.getName());

	private final ServerSocketChannel serverSocketChannel;
	private final Selector selector;

	public ServerSumBetter(int port) throws IOException {
		serverSocketChannel = ServerSocketChannel.open();
		serverSocketChannel.bind(new InetSocketAddress(port));
		selector = Selector.open();
	}

	public void launch() throws IOException {
		serverSocketChannel.configureBlocking(false);
		serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
		while (!Thread.interrupted()) {
			// Helpers.printKeys(selector); // for debug
			System.out.println("Starting select");
			try {
				selector.select(this::treatKey);
			} catch (UncheckedIOException tunneled) {
				throw tunneled.getCause();
			}
			System.out.println("Select finished");
		}
	}

	private void treatKey(SelectionKey key) {
		// Helpers.printSelectedKey(key); // for debug
		try {
			if (key.isValid() && key.isAcceptable()) {
				doAccept(key);
			}
		} catch (IOException ioe) {
			// lambda call in select requires to tunnel IOException
			throw new UncheckedIOException(ioe);
		}
		try {
			if (key.isValid() && key.isWritable()) {
				((Context) key.attachment()).doWrite();
			}
			if (key.isValid() && key.isReadable()) {
				((Context) key.attachment()).doRead();
			}
		} catch (IOException e) {
			logger.log(Level.INFO, "Connection closed with client due to IOException", e);
			silentlyClose(key);
		}
	}

	private void doAccept(SelectionKey key) throws IOException {
		var sc = serverSocketChannel.accept();
		if (sc == null) {
			return;
		}
		
		sc.configureBlocking(false);
		var clientKey = sc.register(selector, SelectionKey.OP_READ);
		clientKey.attach(new Context(clientKey));
	}

	private void silentlyClose(SelectionKey key) {
		Channel sc = (Channel) key.channel();
		try {
			sc.close();
		} catch (IOException e) {
			// ignore exception
		}
	}

	public static void main(String[] args) throws NumberFormatException, IOException {
		if (args.length != 1) {
			usage();
			return;
		}
		new ServerSumBetter(Integer.parseInt(args[0])).launch();
	}

	private static void usage() {
		System.out.println("Usage : ServerSumBetter port");
	}
}