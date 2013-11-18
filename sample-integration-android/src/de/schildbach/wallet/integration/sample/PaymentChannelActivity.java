package de.schildbach.wallet.integration.sample;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import android.widget.Toast;
import de.schildbach.wallet.integration.android.PaymentChannels;
import org.bitcoin.PaymentException;

import java.io.IOException;
import java.math.BigInteger;

/**
 * An activity that lets you build and play with payment channels.
 */
public class PaymentChannelActivity extends Activity {
    private static final int START_PAYMENT_ACTIVITY = 0;
    private static final int REQUEST_AUTH_ACTIVITY = 1;

    private static final long PAYMENT_SIZE = 60000;  // 15,000 satoshis
    private static final long MIN_REQUESTED_VALUE = PAYMENT_SIZE * 10;  // Enough for 10 micropayments.
    private static final String TAG = "PaymentChannelActivity";

    private Button openChannelButton;
    private Button payChannelButton;
    private Button settleChannelButton;
    private EditText hostText;

    private volatile PaymentChannels channels;
    private Handler handler;
    private String serverAddress;
    private PaymentConn serverConnection;
    private PaymentChannels.Channel channel;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.payment_channels);

        handler = new Handler();

        hostText = (EditText) findViewById(R.id.channel_host_text);
        openChannelButton = (Button) findViewById(R.id.open_channel_button);
        payChannelButton = (Button) findViewById(R.id.pay_channel_button);
        settleChannelButton = (Button) findViewById(R.id.settle_channel_button);

        openChannelButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                serverAddress = hostText.getText().toString();
                setButtons(ButtonState.WORKING);
                if (!serverAddress.isEmpty())
                    startPayments();
            }
        });

        payChannelButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                try {
                    Log.i(TAG, "Sending " + PAYMENT_SIZE);
                    // This will briefly block the UI thread for signing, but that's OK for an example app.
                    channel.sendMoney(PAYMENT_SIZE);
                } catch (PaymentException e) {
                    Log.e(TAG, "Payment failed", e);
                }
            }
        });
        payChannelButton.setText("Pay " + PAYMENT_SIZE + " satoshis");

        settleChannelButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                Log.i(TAG, "Settling channel");
                // This will cause some network traffic, so "channel" doesn't become nulled out until later.
                // Buttons will be toggled at the same time later.
                channel.settle();
                setButtons(ButtonState.WORKING);
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        setButtons(ButtonState.WAITING_FOR_OPEN);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (channel != null)
            channel.suspend();
        if (channels != null)
            channels.unbind();
        if (serverConnection != null)
            serverConnection.close();
        setButtons(ButtonState.WAITING_FOR_OPEN);
    }

    enum ButtonState {
        WAITING_FOR_OPEN,
        WORKING,
        READY
    }

    private void setButtons(ButtonState state) {
        payChannelButton.setEnabled(false);
        openChannelButton.setEnabled(false);
        settleChannelButton.setEnabled(false);
        switch (state) {
            case WAITING_FOR_OPEN:
                openChannelButton.setEnabled(true);
                break;
            case WORKING:
                break;   // All disabled.
            case READY:
                payChannelButton.setEnabled(true);
                settleChannelButton.setEnabled(true);
                break;
        }
    }

    private void startPayments() {
        // Bind to the wallet app, if it's installed ....
        PaymentChannels.create(this, true, new PaymentChannels.CreateCallback() {
            public void success(final PaymentChannels result) {
                // Now we're bound, request authorization to make payments up to the given amount ...
                // We're on a background/binder thread at this point.
                channels = result;
                authorize();
            }

            public void startActivity(Intent intent) {
                // We're still on the main thread here.
                startActivityForResult(intent, START_PAYMENT_ACTIVITY);
            }
        });
    }

    private void authorize() {
        channels.authorizeInChunks(MIN_REQUESTED_VALUE, PAYMENT_SIZE, new PaymentChannels.AuthorizeCallback() {
            public void success(final long balanceRemaining) {
                // Might be on a background or UI thread at this point.
                Log.i(TAG, "Got authorized: balance remaining is " + balanceRemaining);
                handler.post(new Runnable() {
                    public void run() {
                        beginPayments(balanceRemaining);
                    }
                });
            }

            public void startActivity(final Intent intent) {
                // Need to show the authorization activity from the main thread.
                handler.post(new Runnable() {
                    public void run() {
                        startActivityForResult(intent, REQUEST_AUTH_ACTIVITY);
                    }
                });
            }
        });
    }

    // Callbacks that the wallet app will invoke when it needs us to send a message or tell us about
    // events happening on the payment channel.
    private final PaymentChannels.ChannelEvents channelEvents = new PaymentChannels.ChannelEvents() {
        public void channelOpen(byte[] contractHash, boolean wasInitiated) throws RemoteException {
            String strHash = new BigInteger(1, contractHash).toString(16);
            Log.i(TAG, "channel open: contract hash is " + strHash);
            handler.post(new Runnable() {
                public void run() {
                    onPaymentChannelNegotiated(true);
                }
            });
        }

        public void channelOpenFailed() throws RemoteException {
            Log.i(TAG, "channel open failed");
            handler.post(new Runnable() {
                public void run() {
                    onPaymentChannelNegotiated(false);
                }
            });
        }

        public void sendProtobuf(byte[] protobuf) throws RemoteException {
            try {
                // Got some bytes from the wallet, so send it to the server for processing.
                Log.d(TAG, "Sending message, size=" + protobuf.length);
                serverConnection.sendMessage(protobuf);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        public void closeConnection(final PaymentChannels.CloseReason reason) throws RemoteException {
            Log.i(TAG, "Channel closing with reason: " + reason);
            handler.post(new Runnable() {
                public void run() {
                    channel = null;
                    setButtons(ButtonState.WAITING_FOR_OPEN);

                    if (reason == PaymentChannels.CloseReason.CLIENT_REQUESTED_CLOSE ||
                        reason == PaymentChannels.CloseReason.SERVER_REQUESTED_CLOSE) {
                        Toast.makeText(PaymentChannelActivity.this,
                                "Channel exhausted, need more money!", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
    };

    private class PaymentConn extends SampleProtobufConn {
        public PaymentConn(String host) throws IOException {
            super(host, 4242, 5000 /* msec connect timeout */);
        }

        @Override
        protected void onMessageReceived(byte[] message) {
            // Got some bytes from the server, so give it to the wallet app for procesing.
            channel.messageReceived(message);
        }
    }

    private void beginPayments(long balanceRemaining) {
        // We now have the ability to spend a part of the users money!
        Toast.makeText(this, "Got authed: balance is " + balanceRemaining + ". Please wait for connection ...",
                Toast.LENGTH_SHORT).show();
        // Time to bring up a TCP connection to the server we'll be paying. Do it off the UI thread to avoid
        // jank/stuttering.
        new Thread("start payment channel") {
            @Override public void run() {
                try {
                    Log.i(TAG, "Connecting to " + serverAddress);
                    serverConnection = new PaymentConn(serverAddress);
                    // The serverAddress below is NOT actually a network address, rather it's more like the HTTP Host
                    // header: just some arbitrary string that the server can use to differentiate amongst different
                    // logical entities that might wish to receive payments on the same network connection.
                    Log.i(TAG, "Connected, now initiating payment channel protocol");
                    channel = channels.open(serverAddress, channelEvents);
                    channel.start();
                    // We should pop out in onPaymentChannelNegotiated on the UI thread, after some server communication
                    // goes back and forth.
                } catch (IOException e) {
                    e.printStackTrace();
                    handler.post(new Runnable() {
                        public void run() {
                            onPaymentChannelNegotiated(false);
                        }
                    });
                }
            }
        }.start();
    }

    private void onPaymentChannelNegotiated(boolean success) {
        if (success) {
            Toast.makeText(this, "Payment channel negotiated, making an initial payment", Toast.LENGTH_LONG).show();
            try {
                channel.sendMoney(PAYMENT_SIZE);
                setButtons(ButtonState.READY);
            } catch (PaymentException e) {
                throw new RuntimeException(e);   // Crash the app.
            }
        } else {
            Toast.makeText(this, "Failed to negotiate payment channel, see adb logs for details", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK) {
            if (requestCode == START_PAYMENT_ACTIVITY)
                Toast.makeText(this, "Failed to find wallet or get payment authorization", Toast.LENGTH_LONG).show();
            else if (requestCode == REQUEST_AUTH_ACTIVITY)
                Toast.makeText(this, "You rejected our authorization request", Toast.LENGTH_LONG).show();
        } else {
            startPayments();  // Try again.
        }
    }
}
