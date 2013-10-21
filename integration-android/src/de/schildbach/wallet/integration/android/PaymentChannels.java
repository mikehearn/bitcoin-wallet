/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.integration.android;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import org.bitcoin.IChannelCallback;
import org.bitcoin.IChannelRemoteService;
import org.omg.CORBA.UNKNOWN;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/**
 * <p>An instance of this class allows you to make the following requests of a wallet app:</p>
 *
 * <ul>
 *     <li>Authorize the application to spend some of the users money. This may involve showing the user a permissions
 *     request activity.</li>
 *     <li>Query how much money you have left, in case the user has changed the amount available to you after you
 *     requested authorization.</li>
 *     <li>Begin a payment channel session with a remote server, such that the wallet app will accept and generate
 *     binary messages that you have to get to the server somehow.</li>
 * </ul>
 *
 * <p>To obtain an instance, use the create method. All methods on this class are blocking and might hit disk or other
 * things that aren't allowed in strict mode, so if you need to, you might want to use an AsyncTask. Some methods have
 * to be run on the main thread, those are marked in the javadocs.</p>
 */
public class PaymentChannels {
    private static final String TAG = "PaymentChannels";

    public interface CreateCallback {
        public void success(PaymentChannels result);
        public void startActivity(Intent intent);
    }

    /**
     * <p>Call this to obtain an instance of PaymentChanel. If the wallet is installed, you will receive a callback
     * asynchronously on the CreateCallback.success() method, once the user has chosen which app to use if there
     * are multiple wallet apps installed, once the app has started up, etc. If there is no wallet installed then
     * you will be given an intent to invoke instead, that will take the user to the Play Store. Once they return
     * to the app, you can try again and see if this time you succeed.</p>
     *
     * <p>When you get the object back, remember to call {@link #unbind()} in order to avoid resource leaks!</p>
     */
    public static void create(final Context context, boolean testNet, final CreateCallback callback) {
        final ServiceConnection conn = new ServiceConnection() {
            public PaymentChannels result;

            public void onServiceConnected(ComponentName componentName, IBinder binder) {
                IChannelRemoteService service = IChannelRemoteService.Stub.asInterface(binder);
                result = new PaymentChannels(context, this, service);
                callback.success(result);
            }

            public void onServiceDisconnected(ComponentName componentName) {
                result.onServiceDisconnected();
            }
        };
        final Intent serviceIntent = new Intent("org.bitcoin.PAYMENT_CHANNEL" + (testNet ? "_TEST" : ""));
        if (!context.bindService(serviceIntent, conn, Context.BIND_AUTO_CREATE)) {
            final Intent marketIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=de.schildbach.wallet"));
            final Intent binaryIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://code.google.com/p/bitcoin-wallet/downloads/list"));

            final PackageManager pm = context.getPackageManager();
            if (pm.resolveActivity(marketIntent, 0) != null)
                callback.startActivity(marketIntent);
            else if (pm.resolveActivity(binaryIntent, 0) != null)
                callback.startActivity(binaryIntent);
        }
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private IChannelRemoteService rpc;
    private Context context;
    private ServiceConnection conn;

    private PaymentChannels(Context context, ServiceConnection conn, IChannelRemoteService rpc) {
        this.rpc = rpc;
        this.conn = conn;
        this.context = context;
    }

    private void onServiceDisconnected() {
        Log.i(TAG, "Wallet disconnected.");
    }

    /**
     * Unbinds from the wallet and nulls out internal references. Make sure to call this when done, otherwise you will
     * leak resources!
     */
    public void unbind() {
        rpc = null;
        context.unbindService(conn);
        conn = null;
        context = null;
    }

    /**
     * Returns how many satoshis the app still has on its balance. If this drops to zero, you will have to re-authorize.
     */
    public long getAppBalance() {
        try {
            return rpc.getBalanceRemaining();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public interface AuthorizeCallback {
        public void success(long balanceRemaining);
        public void startActivity(Intent intent);
    }

    /**
     * Requests permission to build channels of at least minValue in size. If the amount we're requesting is less than
     * or equal to the available balance, callback.success() will be invoked, otherwise callback.startActivity will
     * be given an intent that will open up a permissions-granting activity.
     */
    public void authorizeAtLeast(long minValue, AuthorizeCallback callback) {
        try {
            Intent intent = rpc.prepare(minValue);
            if (intent != null)
                callback.startActivity(intent);
            else
                callback.success(rpc.getBalanceRemaining());
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Requests permission to build channels. This method differs from {@link #authorizeAtLeast(long, de.schildbach.wallet.integration.android.PaymentChannels.AuthorizeCallback)}
     * in how it calculates the amount to request. Firstly, the available app balance is examined. If it is lower than
     * smallestAmount, then it requests authorization for the difference between the balance and chunkSize, thus
     * ensuring that the user sees a request on screen for precisely the given chunk size regardless of how much
     * has actually been spent. Effectively, this method will "top up" the apps balance once it falls too low.
     */
    public void authorizeInChunks(long chunkSize, long smallestPayment, AuthorizeCallback callback) {
        try {
            if (smallestPayment <= 0 || chunkSize <= 0)
                throw new IllegalArgumentException("Args may not be negative");
            if (smallestPayment >= chunkSize)
                throw new IllegalArgumentException("smallestPayment must be smaller than chunkSize");
            long balance = rpc.getBalanceRemaining();
            if (balance >= smallestPayment) {
                callback.success(balance);
                return;
            }
            Intent intent = rpc.prepare(chunkSize - balance);
            if (intent != null)
                callback.startActivity(intent);
            else
                callback.success(balance);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public static enum CloseReason {
        /** We could not find a version which was mutually acceptable with the client/server */
        NO_ACCEPTABLE_VERSION,
        /** Generated by a client when the server attempted to lock in our funds for an unacceptably long time */
        TIME_WINDOW_TOO_LARGE,
        /** Generated by a client when the server requested we lock up an unacceptably high value */
        SERVER_REQUESTED_TOO_MUCH_VALUE,
        /** Generated by the server when the client has used up all the value in the channel. */
        CHANNEL_EXHAUSTED,

        /** The settle() method was called. */
        CLIENT_REQUESTED_CLOSE,

        /**
         * The server closed the channel: probably because all the money in it was used up, or it decided to terminate
         * its relationship with you for some other reason.
         */
        SERVER_REQUESTED_CLOSE,

        /** Remote side sent an ERROR message */
        REMOTE_SENT_ERROR,
        /** Remote side sent a message we did not understand */
        REMOTE_SENT_INVALID_MESSAGE,

        /** The connection was closed without an ERROR/CLOSE message */
        CONNECTION_CLOSED,

        UNKNOWN,
    }

    /**
     * <p>A Channel represents a payment relationship. It isn't usable until start() has been called. Once that has
     * been done, calling sendMoney will result in a protobuf being delivered to {@link ChannelEvents#sendProtobuf(byte[])}
     * with an appropriately signed message. You should deliver any messages from the server to
     * {@link #messageReceived(byte[])}, which may in turn invoke methods upon {@link ChannelEvents}.</p>
     *
     * <p>A Channel is a handle to persistent state that exists in both the wallet, and the server. Even if your
     * network connectivity is interrupted or the server/client dies entirely, the underlying channel construct will
     * be resumed next time you open/start a channel with the same hostID as before (and of course, assuming the
     * messages are being routed to the same server).</p>
     *
     * <p>Therefore, the size of the actual channel may be different to the apps remaining balance if, for example,
     * you requested authorization for a larger amount of money after a channel was opened.</p>
     */
    public class Channel {
        private final String hostID;
        private final ChannelEvents events;
        private FutureTask<String> cookie;   // Will be filled out later.
        private volatile boolean settling;

        private Channel(String hostID, ChannelEvents events) {
            this.hostID = hostID;
            this.events = events;
        }

        private void checkStarted() {
            if (cookie == null)
                throw new IllegalStateException("This channel was not started, or suspended/settled already.");
        }

        private void checkNotSettling() {
            if (settling)
                throw new IllegalStateException("Settle was requested");
        }

        public void start() {
            if (cookie != null)
                throw new IllegalStateException("Already started");
            // We have to do this in a FutureTask because rpc.openConnection() will cause network activity to occur,
            // and that in turn might cause messageReceived() to be invoked on a different thread before we actually
            // get control back from openConnection(). Therefore, to resolve this race, we ensure that whatever thread
            // is receiving network traffic will block until the RPC to allocate a cookie is complete.
            cookie = new FutureTask<String>(new Callable<String>() {
                public String call() throws Exception {
                    return rpc.openConnection(new IChannelCallback.Stub() {
                        public void channelOpen(byte[] contractHash) throws RemoteException {
                            events.channelOpen(contractHash);
                        }

                        public void channelOpenFailed() throws RemoteException {
                            events.channelOpenFailed();
                        }

                        public void sendProtobuf(byte[] protobuf) throws RemoteException {
                            events.sendProtobuf(protobuf);
                        }

                        public void closeConnection(int reason) throws RemoteException {
                            for (CloseReason r : CloseReason.values()) {
                                if (r.ordinal() == reason) {
                                    events.closeConnection(r);
                                    return;
                                }
                            }
                            events.closeConnection(CloseReason.UNKNOWN);
                        }
                    }, hostID);
                }
            });
            new Thread(cookie).start();
        }

        /**
         * Will attempt to send the given amount of money. The actual amount sent may be zero, if there wasn't enough
         * money left on this channel. Returns the amount actually sent.
         */
        public long sendMoney(long amount) {
            try {
                checkStarted();
                checkNotSettling();
                return rpc.payServer(cookie.get(), amount);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public void messageReceived(byte[] protobuf) {
            try {
                checkStarted();
                rpc.messageReceived(cookie.get(), protobuf);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * Call this when you are done talking to the server and wish to disconnect, but not settle the payment
         * channel and release funds back to the user (it would still be usable later). This will generate a
         * closeConnection callback asynchronously.
         */
        public void suspend() {
            try {
                checkStarted();
                checkNotSettling();
                rpc.disconnectFromWallet(cookie.get());
                cookie = null;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * Does the same thing as suspend(), but asks the server to settle the channel and thus release funds back
         * to the user. The closeConnection callback will come asynchronously some time later.
         */
        public void settle() {
            try {
                checkStarted();
                checkNotSettling();
                settling = true;
                rpc.closeConnection(cookie.get());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public interface ChannelEvents {
        public void channelOpen(byte[] contractHash) throws RemoteException;

        public void channelOpenFailed() throws RemoteException;

        public void sendProtobuf(byte[] protobuf) throws RemoteException;

        public void closeConnection(CloseReason reason) throws RemoteException;
    }

    /**
     * <p>Returns a new Channel object that allows you to make payments. The hostID string acts a little bit like
     * the HTTP Host header does - it allows a single server endpoint to separate out multiple different entities that
     * can receive payments. It's any arbitrary string. If in doubt, just use the DNS name of the server you're
     * connecting to.</p>
     *
     * <p>The returned object is not usable until its start method is called. You have to do this explicitly, so
     * you have a chance to assign the Channel instance to a variable, as once start is called, network traffic
     * will begin and you will get callbacks.</p>
     */
    public Channel open(String hostID, final ChannelEvents events) {
        return new Channel(hostID, events);
    }
}
