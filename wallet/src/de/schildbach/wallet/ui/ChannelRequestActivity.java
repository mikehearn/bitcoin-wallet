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

package de.schildbach.wallet.ui;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.view.View;
import android.widget.Button;
import android.widget.PopupWindow;
import android.widget.TextView;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.wallet.AllowUnconfirmedCoinSelector;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.ExchangeRatesProvider;
import de.schildbach.wallet.service.ChannelService;
import de.schildbach.wallet.util.GenericUtils;
import de.schildbach.wallet.util.WalletUtils;
import de.schildbach.wallet_test.R;

import java.math.BigInteger;

/**
 * Activity called by third-party apps when {@link ChannelService} informs them that they need to make an explicit
 * request for access to funds. By making the third-party app make the call to this Activity instead of
 * {@link ChannelService} doing it directly, we make sure the back stack remains usable.
 */
public class ChannelRequestActivity extends AbstractWalletActivity {
	private Wallet wallet;

	private BalanceValidationPopup balanceValidationPopup;

	private Button acceptButton;

	private CharSequence requestingApp;
	private String requestedValueStr;

	private CurrencyCalculatorLink currencyCalculatorLink;
	private CurrencyAmountView btcAmountView;
	private LoaderManager loaderManager;

	private final LoaderManager.LoaderCallbacks<Cursor> rateLoaderCallbacks = new LoaderManager.LoaderCallbacks<Cursor>()
	{
		public Loader<Cursor> onCreateLoader(final int id, final Bundle args)
		{
			return new ExchangeRateLoader(ChannelRequestActivity.this);
		}

		public void onLoadFinished(final Loader<Cursor> loader, final Cursor data)
		{
			if (data != null)
			{
				data.moveToFirst();
				final ExchangeRatesProvider.ExchangeRate exchangeRate = ExchangeRatesProvider.getExchangeRate(data);

				currencyCalculatorLink.setExchangeRate(exchangeRate);

				String localValue = exchangeRate.currencyCode + " " +
						GenericUtils.formatValue(WalletUtils.localValue(BigInteger.valueOf(appSpecifiedMinValue), exchangeRate.rate), Constants.LOCAL_PRECISION);
				String intro = getString(R.string.channel_request_intro, requestingApp, requestedValueStr, "(" + localValue + ")");
				TextView label = (TextView) findViewById(R.id.channel_request_intro_text);
				label.setText(intro);
			}
		}

		public void onLoaderReset(final Loader<Cursor> loader)
		{
		}
	};

	private final CurrencyAmountView.Listener amountsListener = new CurrencyAmountView.Listener()
	{
		public void changed()
		{
			balanceValidationPopup.dismissPopup();
			validateAmounts(false);
		}

		public void done()
		{
			validateAmounts(true);
			acceptButton.requestFocusFromTouch();
		}

		public void focusChanged(final boolean hasFocus)
		{
			if (!hasFocus)
			{
				validateAmounts(true);
			}
		}
	};

	private BigInteger userSpecifiedMaxValue; // set to non-null when acceptButton is tapped
	private long appSpecifiedMinValue;

	ChannelService service;
	private ServiceConnection connection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			ChannelRequestActivity.this.service = ((ChannelService.LocalBinder)service).getService();
			// bindService can be called in acceptButton.onClickListener or handleIntent, so we have to figure out which
			// call we should make to continue the process.
			if (userSpecifiedMaxValue == null)
				initWithBinder();
			else
				allowAndFinish();
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			service = null;
		}
	};

	@Override
	protected void onCreate(final Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

		setContentView(R.layout.channel_request_view);

		wallet = getWalletApplication().getWallet();

		acceptButton = (Button) findViewById(R.id.send_coins_go);
		acceptButton.setText(R.string.button_allow);
		acceptButton.setEnabled(false);
		acceptButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				if (validateAmounts(true)) {
					userSpecifiedMaxValue = currencyCalculatorLink.getAmount();
					if (service == null)
						bindService(new Intent(ChannelRequestActivity.this, ChannelService.class), connection, Context.BIND_AUTO_CREATE);
					else
						allowAndFinish();
					disconnectAndFinish(Activity.RESULT_OK);
				}
			}
		});

		Button rejectButton = (Button) findViewById(R.id.send_coins_cancel);
		rejectButton.setText(R.string.button_reject);
		rejectButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				balanceValidationPopup.dismissPopup();
				disconnectAndFinish(Activity.RESULT_CANCELED);
			}
		});

		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		btcAmountView = (CurrencyAmountView)findViewById(R.id.channel_value_view_btc);
		btcAmountView.setCurrencySymbol(Constants.CURRENCY_CODE_BITCOIN);
		btcAmountView.setHintPrecision(Integer.parseInt(prefs.getString(Constants.PREFS_KEY_BTC_PRECISION, Constants.PREFS_DEFAULT_BTC_PRECISION)));

		CurrencyAmountView localAmountView = (CurrencyAmountView) findViewById(R.id.channel_value_view_local);
		localAmountView.setHintPrecision(Constants.LOCAL_PRECISION);

		balanceValidationPopup = new BalanceValidationPopup(getLayoutInflater(), null, this);

		currencyCalculatorLink = new CurrencyCalculatorLink(btcAmountView, localAmountView);
		currencyCalculatorLink.setListener(amountsListener);
		currencyCalculatorLink.setEnabled(true);

		loaderManager = getSupportLoaderManager();

		handleIntent(getIntent());
	}

	@Override
	protected void onNewIntent(final Intent intent)
	{
		handleIntent(intent);
	}

	@Override
	public boolean onCreateOptionsMenu(final Menu menu)
	{
		getSupportMenuInflater().inflate(R.menu.send_coins_activity_options, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public void onBackPressed() {
		disconnectAndFinish(Activity.RESULT_CANCELED);
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem item)
	{
		switch (item.getItemId())
		{
			case android.R.id.home:
				disconnectAndFinish(Activity.RESULT_CANCELED);
				return true;

			case R.id.send_coins_options_help:
				HelpDialogFragment.page(getSupportFragmentManager(), "help_open_channel");
				return true;
		}

		return super.onOptionsItemSelected(item);
	}

	@Override
	public void onResume()
	{
		loaderManager.initLoader(0, null, rateLoaderCallbacks);
		super.onResume();
	}

	@Override
	public void onPause()
	{
		loaderManager.destroyLoader(0);
		super.onPause();
	}

	private boolean validateAmounts(final boolean popups)
	{
		final BigInteger amount = btcAmountView.getAmount();
		if (amount == null)
			return false;
		final boolean insufficientAuth = amount.longValue() < appSpecifiedMinValue;

		final BigInteger unconfirmedBalance = wallet.getBalance(AllowUnconfirmedCoinSelector.get());
		final boolean insufficientBalance = amount.longValue() > unconfirmedBalance.longValue();

		if (popups)
		{
			if (insufficientAuth)
			{
				// App wants more value than user is allowing, show an error to the user so they know why they can't accept.
				balanceValidationPopup.popupMessageAvailable(btcAmountView, getText(R.string.send_coins_fragment_channel_label).toString(),
						BigInteger.valueOf(appSpecifiedMinValue));
			}
			else if (insufficientBalance)
			{
				balanceValidationPopup.popupAvailable(btcAmountView, unconfirmedBalance, BigInteger.ZERO);
			}
		}

		return !(insufficientAuth || insufficientBalance);
	}

	private void initWithBinder() {
		// Figure out the caller of this activity.
		PackageManager packageManager = getApplicationContext().getPackageManager();
		final String callingPackage = getCallingPackage();
		try {
			ApplicationInfo appInfo = packageManager.getApplicationInfo(callingPackage, 0);
			requestingApp = packageManager.getApplicationLabel(appInfo);
		} catch (PackageManager.NameNotFoundException e) {
			// Fall through.
		}
		if (requestingApp == null)
			requestingApp = "unknown";
		// TODO: Show the user how much the app can still spend, maybe even how much it has spent
		long amountLeft =  service.getAppValueRemaining(callingPackage);
		appSpecifiedMinValue = appSpecifiedMinValue - amountLeft;
		requestedValueStr = GenericUtils.formatValue(BigInteger.valueOf(appSpecifiedMinValue), Constants.BTC_MAX_PRECISION) + " " + Constants.CURRENCY_CODE_BITCOIN;
		final String intro = getString(R.string.channel_request_intro, requestingApp, requestedValueStr, "");
		final TextView label = (TextView) findViewById(R.id.channel_request_intro_text);
		label.setText(intro);
		btcAmountView.setAmount(BigInteger.valueOf(appSpecifiedMinValue), true);
		acceptButton.setEnabled(true);
	}

	private void allowAndFinish() {
		service.allowConnection(getCallingPackage(), userSpecifiedMaxValue.longValue());
		disconnectAndFinish(Activity.RESULT_OK);
	}

	private void disconnectAndFinish(int result) {
		setResult(result);
		if (service != null) {
			unbindService(connection);
			service = null;
		}
		finish();
	}

	private void handleIntent(Intent intent)
	{
		appSpecifiedMinValue = intent.getLongExtra("minValue", 0);
		userSpecifiedMaxValue = null;
		if (service == null)
			bindService(new Intent(ChannelRequestActivity.this, ChannelService.class), connection, Context.BIND_AUTO_CREATE);
		else
			initWithBinder();
	}
}
