/*
 * Copyright 2013 Google Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.Sha256Hash;
import com.google.bitcoin.protocols.channels.PaymentChannelClient;
import com.google.bitcoin.protocols.channels.PaymentChannelCloseException;
import com.google.bitcoin.protocols.channels.ValueOutOfRangeException;
import com.google.bitcoin.utils.Threading;
import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.InvalidProtocolBufferException;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.ui.ChannelRequestActivity;
import de.schildbach.wallet.util.WalletUtils;
import net.jcip.annotations.GuardedBy;
import org.bitcoin.ChannelConstants;
import org.bitcoin.IChannelCallback;
import org.bitcoin.IChannelRemoteService;
import org.bitcoin.paymentchannel.Protos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * A service that keeps a set of channels and handles application access to them.
 */
public class ChannelService extends Service {
	@VisibleForTesting final ReentrantLock lock = Threading.lock("bitcoin-wallet-channelservice");

	private static final Logger log = LoggerFactory.getLogger(ChannelService.class);

	// Used to keep track of mappings from channel UUIDs to in-memory information about the channel
	private static class ChannelAndMetadata {
		PaymentChannelClient client;
		IChannelCallback listener;
		String appId;
		String appName;

		String hostId;

		public ChannelAndMetadata(IChannelCallback listener, String hostId) {
			this.listener = listener;
			this.hostId = hostId;
		}
	}

	// Maps unique IDs to LocalBinders which hold the app connection
	@GuardedBy("lock") private Map<String, ChannelAndMetadata> cookieToChannelMap = new HashMap<String, ChannelAndMetadata>();

	// This is also accessed from the app permissions activity. It's just a map of app id to long (amount of credit
	// remaining).
	public static final String PREFS_NAME = ChannelService.class.getName() + ".APP_TO_VALUE_REMAINING_PREFS";

	// Maps app package name to its value remaining
	@GuardedBy("lock") private SharedPreferences appToValueRemaining;
	@GuardedBy("lock") @VisibleForTesting long incrementAndGet(String appId, long value) {
		checkState(lock.isHeldByCurrentThread());
		long initialValue = appToValueRemaining.getLong(appId, 0);
		appToValueRemaining.edit().putLong(appId, initialValue + value).commit();
		return initialValue + value;
	}

	public long getAppValueRemaining(String appId) {
		lock.lock();
		try {
			return appToValueRemaining.getLong(appId, 0);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void onCreate() {
		lock.lock();
		try {
			appToValueRemaining = getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
		} finally {
			lock.unlock();
		}
	}

	// Opens a connection (possibly resuming it with the server) and sets up listeners for it.
	@GuardedBy("lock")
	private void buildClientConnection(final String cookie, final ChannelAndMetadata metadata, long maxValue) {
		checkState(lock.isHeldByCurrentThread());

		final WalletApplication walletApplication = ((WalletApplication) getApplication());
		// TODO: Using a constant key for everything is very broken in many sense, at a minimum a large keypool can be
		// created once that is used forever, but, realistically, an HD wallet should be used.
		ECKey key = WalletUtils.pickOldestKey(walletApplication.getWallet());
        metadata.client = new PaymentChannelClient(walletApplication.getWallet(), key, BigInteger.valueOf(maxValue),
                Sha256Hash.create(metadata.hostId.getBytes()), new PaymentChannelClient.ClientConnection() {
            @Override
            public void sendToServer(Protos.TwoWayChannelMessage msg) {
                try {
                    metadata.listener.sendProtobuf(msg.toByteArray());
                } catch (RemoteException e) {
                    closeConnection(cookie, false);
                }
            }

            @Override
            public void destroyConnection(PaymentChannelCloseException.CloseReason reason) {
                try {
                    metadata.listener.closeConnection(reason.ordinal());
                } catch (RemoteException e) {
                    closeConnection(cookie, false);
                }
            }

            @Override
            public void channelOpen() {
                log.info("Successfully opened payment channel");
                walletApplication.getContractHashToCreatorMap().setCreatorApp(metadata.client.state().getMultisigContract().getHash(),
                        metadata.appName);
                try {
                    metadata.listener.channelOpen(metadata.client.state().getMultisigContract().getHash().getBytes());
                } catch (RemoteException e) {
                    closeConnection(cookie, false);
                }
            }
        });
        metadata.client.connectionOpen();
	}

	// Closes the given connection and removes it from the pool
	@GuardedBy("lock")
	private void closeConnection(String id, boolean generateServerClose) {
		checkState(lock.isHeldByCurrentThread());

		ChannelAndMetadata channel = cookieToChannelMap.remove(id);
		if (channel == null || channel.client == null)
			return;
		try {
			if (generateServerClose)
				channel.client.close();
		} catch (IllegalStateException e) {
			// Already closed...oh well
		}
		channel.client.connectionClosed();
	}

	/**
	 * Called by {@link de.schildbach.wallet.ui.ChannelRequestActivity} to notify us that a given connection
	 * is to be allowed for the given amount of value.
	 */
	public void allowConnection(String appId, long maxValue) {
		lock.lock();
		try {
			incrementAndGet(appId, maxValue);
		} finally {
			lock.unlock();
		}
	}

	// There are two potential clients to this service - the local confirmation activity which uses getService() to call
	// allowConnection and external apps which call IChannelRemoteService methods.
	// The calls made to this class (except for getService()) are run in an android thread pool, so should all be
	// thread-safe.
	public class LocalBinder extends IChannelRemoteService.Stub {
		public ChannelService getService() {
			// This may only be called by the local process
			return ChannelService.this;
		}

		@Override
		public Intent prepare(long minValue) throws RemoteException {
			if ((minValue <= 0) || (minValue > NetworkParameters.MAX_MONEY.longValue())) {
				log.error("Got prepare request with invalid arguments");
				return null;
			}
			PackageManager packageManager = getApplicationContext().getPackageManager();
			String appId = packageManager.getNameForUid(Binder.getCallingUid());
			long valueRemaining = getAppValueRemaining(appId);
			if (valueRemaining >= minValue) {
				log.info("Prepare request, wants {} and has {}: cleared!", minValue, valueRemaining);
				return null;   // Access OK.
			}
			// Bind directly to this app - other wallets that want to provide this API can be selected at bind time
			// by the integration library code.
			final Intent intent = new Intent(getApplicationContext(), ChannelRequestActivity.class);
			intent.putExtra("minValue", minValue);
			return intent;
		}

		@Override
		public String openConnection(IChannelCallback listener, String hostId) {
			if (listener == null || hostId == null) {
				log.error("Got invalid openConnection request: " +
						(listener == null ? "listener = null " : "") +
						(hostId == null ? "hostId = null " : ""));
				return null; // Invalid request
			}

			PackageManager packageManager = getApplicationContext().getPackageManager();
			final String appId = checkNotNull(packageManager.getNameForUid(Binder.getCallingUid()));
			String appName = null;
			try {
				ApplicationInfo info = packageManager.getApplicationInfo(appId, 0);
				appName = packageManager.getApplicationLabel(info).toString();
			} catch (PackageManager.NameNotFoundException e) {
				// Expected that this can sometimes happen.
			}
			if (appName == null) appName = appId;

            final ChannelAndMetadata channel = new ChannelAndMetadata(listener, hostId);
            channel.appId = appId;
            channel.appName = appName;

            watchForDeath(listener, channel);

            lock.lock();
			try {
				long valueRemaining = getAppValueRemaining(appId);
				String cookie = UUID.randomUUID().toString();
				cookieToChannelMap.put(cookie, channel);
				log.info("Opening new channel of {} satoshis for app {}", valueRemaining, appId);
				buildClientConnection(cookie, channel, valueRemaining);
				return cookie;
			} finally {
				lock.unlock();
			}
		}

        private void watchForDeath(IChannelCallback listener, final ChannelAndMetadata channel) {
            // We need to find out if the connected app goes away so we can mark the channel as inactive.
            // Arguably, the payment channels framework should not attempt to prevent concurrent use of
            // channels by apps because mutual exclusion is better done at higher levels, but it does and
            // revisiting that decision must come in a later version.
            try {
                listener.asBinder().linkToDeath(new DeathRecipient() {
                    @Override
                    public void binderDied() {
                        log.info("Connected app '{}' died, marking channel {} as inactive", channel.appName, channel.hostId);
                        channel.client.connectionClosed();
                    }
                }, 0);
            } catch (RemoteException e) {
                // Race: calling process died whilst we're in the middle of processing its RPC :( Doesn't make sense
                // to proceed at this point.
                throw new RuntimeException(e);
            }
        }

        @Override
		public long payServer(String id, long amount) {
			if (id == null || amount < 0)
				return ChannelConstants.INVALID_REQUEST;

			lock.lock();
			try {
				ChannelAndMetadata channel = cookieToChannelMap.get(id);
				if (channel == null || channel.client == null) {
					log.error("App requested payment increase for an unknown channel");
					return ChannelConstants.NO_SUCH_CHANNEL;
				}

				if (!getApplicationContext().getPackageManager().getNameForUid(Binder.getCallingUid()).equals(channel.appId)) {
					log.error("App requested payment increase for a channel it didn't initiate");
					return ChannelConstants.NO_SUCH_CHANNEL;
				}

				long valueRemaining = getAppValueRemaining(channel.appId);
				if (valueRemaining < amount) {
					log.error("App requested {} but remaining user-allowed value is {}", amount, valueRemaining);
                    return ChannelConstants.INSUFFICIENT_VALUE;
				}

				try {
                    long actualAmount = channel.client.incrementPayment(BigInteger.valueOf(amount)).longValue();
                    // Subtract the amount spent from the apps quota. Note that it may be a different amount
                    // to what was requested, e.g. it might be higher if the remaining amount on the channel
                    // would have been unsettleable.
					incrementAndGet(channel.appId, -actualAmount);
					log.info("Successfully made payment for app {} of {} satoshis", channel.appId, actualAmount);
                    if (actualAmount != amount)
                        log.info("  vs {} satoshis which was requested", amount);
					return actualAmount;
				} catch (ValueOutOfRangeException e) {
                    // The user may have allowed us more than was actually put into the channel.
					log.error("Attempt to increment payment got ValueOutOfRangeException for app " + channel.appId, e);
					return ChannelConstants.INSUFFICIENT_VALUE;
				} catch (IllegalStateException e) {
					log.error("Attempt to increment payment got IllegalStateException for app " + channel.appId, e);
					closeConnection(id);
					return ChannelConstants.CHANNEL_NOT_IN_SPENDABLE_STATE;
				}
			} finally {
				lock.unlock();
			}
		}

		@Override
		public void closeConnection(String cookie) {
			if (cookie == null)
				return;
			log.info("App requested channel close");
			lock.lock();
			try {
				ChannelService.this.closeConnection(cookie, true);
			} finally {
				lock.unlock();
			}
		}

		@Override
		public void disconnectFromWallet(String cookie) throws RemoteException {
			// Should be called before an unbind, marks the given channel as inactive.
			if (cookie == null)
				return;
			log.info("App is disconnecting from channel");
			lock.lock();
			try {
				ChannelService.this.closeConnection(cookie, false);
			} finally {
				lock.unlock();
			}
		}

        public void messageReceived(String cookie, byte[] protobuf) {
			if (cookie == null)
				return;
			log.info("App provided message from server");
			lock.lock();
			try {
				ChannelAndMetadata metadata = cookieToChannelMap.get(cookie);
				if (metadata == null || metadata.client == null)
					return;
				metadata.client.receiveMessage(Protos.TwoWayChannelMessage.parseFrom(protobuf));
			} catch (InvalidProtocolBufferException e) {
				log.error("Got an invalid protobuf from client", e);
            } catch (ValueOutOfRangeException e) {
                // TODO: This shouldn't happen, but plumb it through anyway.
                log.error("Got value out of range during initiate", e);
			} finally {
				lock.unlock();
			}
		}
	}
	@VisibleForTesting final IBinder binder = new LocalBinder();

	@Override
	public IBinder onBind(final Intent intent)
	{
		return binder;
	}
}
