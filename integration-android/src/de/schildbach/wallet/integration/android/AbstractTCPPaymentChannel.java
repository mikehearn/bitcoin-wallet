package de.schildbach.wallet.integration.android;

import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import static com.google.common.base.Preconditions.checkState;

/**
 * A simple TCP channel listener which maintains a TCP connection to a server and implements
 * {@link ChannelListener#sendProtobuf(byte[])} for you.
 */
public abstract class AbstractTCPPaymentChannel implements ChannelListener {
	private static final String TAG = AbstractTCPPaymentChannel.class.getName();

	protected BitcoinPaymentChannelManager channel;
    protected Thread readThread;
    protected Socket socket = null;

    protected SocketAddress remoteAddress;
    protected int connectTimeoutMillis;
    protected List<byte[]> protobufsToSend = new LinkedList<byte[]>();

    protected boolean gaveupConnecting = false;

    private volatile boolean vClosing = false;

	private synchronized void closeSocket() {
		try {
            vClosing = true;
			if (socket != null) {
				socket.close();
                socket = null;
            }

            if (readThread != null) {
                // Wait for the background thread to try reading from a closed socket, fail and quit.
                readThread.join();
            }
        } catch (IOException e) {
            // Ignore exceptions closing, we're creating a new one anyway
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

	private synchronized void openSocket() throws IOException {
		if (gaveupConnecting)
			return;
		closeSocket();

		socket = new Socket();
		socket.connect(remoteAddress, connectTimeoutMillis);
		DataOutputStream stream = new DataOutputStream(socket.getOutputStream());

		Iterator<byte[]> iterator = protobufsToSend.iterator();

		while (iterator.hasNext()) {
			byte[] protobuf = iterator.next();
			try {
				stream.writeInt(protobuf.length);
				stream.write(protobuf);
			} catch (IOException e) {
				// We might have sent a message that told the server to terminate the connection, so it did, or we got
				// cut off, in which case there also isnt much we can do
				return;
			}
			iterator.remove();
		}

		readThread = new Thread(new Runnable() {
			public void run() {
				try {
					DataInputStream in = new DataInputStream(socket.getInputStream());
					while (true) {
						int messageLength = in.readInt();
						if (messageLength < 0 || messageLength > Short.MAX_VALUE)
							throw new IOException("Message length too large");

						byte[] message = new byte[messageLength];
						if (in.read(message) != messageLength)
							throw new EOFException();
						channel.messageReceived(message);
					}
				} catch (final IOException e) {
                    if (!vClosing)
					    Log.e(TAG, "Got IOException reading from socket", e);
                    else
                        Log.i(TAG, "Payment TCP network thread finishing");
					channel.disconnectFromWallet(false);
				}
			}
		});
		readThread.setName("tcp-paymentchannel-read");
        readThread.setDaemon(true);
		readThread.start();
	}

	/**
	 * Sets up a new connection to a payment channel server over a TCP connection to the given remote address.
	 */
	public AbstractTCPPaymentChannel(SocketAddress remoteAddress, int connectTimeoutMillis) {
		this.remoteAddress = remoteAddress;
		this.connectTimeoutMillis = connectTimeoutMillis;
	}

	/**
	 * Blocks until the connection has opened, or throws an IOException if it failed.
	 */
	public synchronized void connect(BitcoinPaymentChannelManager channel) throws IOException {
		this.channel = channel;
		openSocket();
	}

	public synchronized void sendProtobuf(byte[] protobuf) {
		checkState(protobuf.length <= Short.MAX_VALUE && channel != null);
		try {
            if (socket != null) {
                DataOutputStream stream = new DataOutputStream(socket.getOutputStream());
                stream.writeInt(protobuf.length);
                stream.write(protobuf);
                return;
            }
		} catch (final IOException e) {
			Log.e(TAG, "Got IOException writing to socket, queuing and opening new socket", e);
		}
        protobufsToSend.add(protobuf);
        try {
            openSocket();
        } catch (IOException e1) {
            Log.e(TAG, "Got IOException opening new socket, closing channel", e1);
            gaveupConnecting = true;
            channel.closeChannel();
        }
	}

	public void channelClosedOrNotOpened(ChannelListener.CloseReason reason) {
		closeSocket();
	}

	public void channelInterrupted() {
		closeSocket();
	}

	public void channelOpen(byte[] contractHash) {

    }
}
