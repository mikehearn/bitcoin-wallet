package de.schildbach.wallet.integration.sample;

import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * A simple class that reads/writes length-prefixed byte buffers (protocol buffers) over a TCP socket.
 * It can be used to talk to the example payment server in the bitcoinj distribution.
 */
public abstract class SampleProtobufConn {
    private static final String TAG = "SampleProtobufConn";
    private final DataOutputStream outputStream;
    private volatile boolean vClosing;

    public SampleProtobufConn(String host, int port, int connectTimeoutMsec) throws IOException {
        this(new InetSocketAddress(host, port), connectTimeoutMsec);
    }

    public SampleProtobufConn(InetSocketAddress address, int connectTimeoutMsec) throws IOException {
        Socket socket = new Socket();
        socket.connect(address, connectTimeoutMsec);

        final DataInputStream inputStream = new DataInputStream(socket.getInputStream());
        outputStream = new DataOutputStream(socket.getOutputStream());

        Thread readThread = new Thread(new Runnable() {
            public void run() {
                try {
                    while (true) {
                        int len = inputStream.readInt();
                        if (len < 0 || len > Short.MAX_VALUE)
                            throw new IOException("Message length too large: " + len);
                        byte[] message = new byte[len];
                        if (inputStream.read(message) != len)
                            throw new EOFException();
                        onMessageReceived(message);
                    }
                } catch (Exception e) {
                    if (!vClosing)
                        onSocketException(e);
                    else
                        Log.i(TAG, "Protobuf socket read thread terminating");
                }
            }
        });
        readThread.setName("Protobuf socket read thread");
        readThread.setDaemon(true);
        readThread.start();
    }

    public synchronized void sendMessage(byte[] message) throws IOException {
        if (message.length > Short.MAX_VALUE)
            throw new IllegalArgumentException("message too large");
        outputStream.writeInt(message.length);
        outputStream.write(message);
    }

    public synchronized void close() {
        vClosing = true;
        try {
            outputStream.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected abstract void onMessageReceived(byte[] message);

    protected void onSocketException(Exception e) {
        Log.e(TAG, "Got exception during socket read", e);
    }
}
