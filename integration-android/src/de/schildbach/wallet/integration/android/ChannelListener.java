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

/**
 * Describes a listener which receives events from a {@link BitcoinPaymentChannelManager}
 */
public interface ChannelListener {
	/**
	 * <p>Called to indicate the channel has successfully been opened and payments/messages can be submitted.</p>
	 *
	 * <p>This method may be called more than once if the channel is interrupted and later reconnected to, however,
	 * because reconnection will always use the same channel, contractHash will not change for any one given
	 * {@link BitcoinPaymentChannelManager}.</p>
	 *
	 * @param contractHash The bitcoin hash of the contract transaction which locks in the channel
	 */
	public void channelOpen(byte[] contractHash);

	/**
	 * <p>Called when the connection with the wallet apphas been interrupted. Reconnection may happen automatically, or
	 * it may happen on the next request to the {@link BitcoinPaymentChannelManager} (though that request will fail).
	 * When reconnection either succeeds or fails, channelOpen or channelClosedOrNotOpened will be called.</p>
	 *
	 * <p>Note that when the reconnection attempt occurs, a new handshake with the server will be attempted, which means
	 * the connection to the server must be closed as well.</p>
	 */
	public void channelInterrupted();

	/**
	 * Called to indicate the channel has been closed or opening the channel failed for some reason (rejected, wallet
	 * app not installed, unable to make a connection, etc). The associated {@link BitcoinPaymentChannelManager} is no
	 * longer valid. The connection to the server should be closed.
	 */
	public void channelClosedOrNotOpened();

	/** Called when the given protobuf (in the form of an encoded byte array) should be sent to the server (in order) */
	void sendProtobuf(byte[] protobuf);
}