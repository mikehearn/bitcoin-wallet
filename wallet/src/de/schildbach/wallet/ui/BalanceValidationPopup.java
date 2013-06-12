package de.schildbach.wallet.ui;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.TextView;
import de.schildbach.wallet_test.R;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.util.GenericUtils;

import javax.annotation.Nonnull;
import java.math.BigInteger;

import static android.view.View.*;

public class BalanceValidationPopup
{
	private final Activity activity;
	private TextView popupMessageView;
	private View popupAvailableView;
	private PopupWindow popupWindow;

	public BalanceValidationPopup(LayoutInflater inflater, ViewGroup container, Activity activity)
	{
		this.activity = activity;
		popupMessageView = (TextView) inflater.inflate(R.layout.send_coins_popup_message, container);
		popupAvailableView = inflater.inflate(R.layout.send_coins_popup_available, container);
	}

	void popupMessage(@Nonnull final View anchor, @Nonnull final String message, int maxWidth)
	{
		dismissPopup();

		popupMessageView.setText(message);
		popupMessageView.setMaxWidth(maxWidth);

		popup(anchor, popupMessageView);
	}

	void popupMessageAvailable(@Nonnull final View anchor, @Nonnull final String message, BigInteger available)
	{
		dismissPopup();

		final CurrencyTextView viewAvailable = (CurrencyTextView) popupAvailableView.findViewById(R.id.send_coins_popup_available_amount);
		viewAvailable.setPrefix(Constants.CURRENCY_CODE_BITCOIN);
		viewAvailable.setAmount(available);

		final TextView viewPending = (TextView) popupAvailableView.findViewById(R.id.send_coins_popup_available_pending);
		viewPending.setVisibility(View.GONE);

		final TextView textLabel = (TextView) popupAvailableView.findViewById(R.id.send_coins_popup_available_label);
		textLabel.setText(message);

		popup(anchor, popupAvailableView);
	}

	void popupAvailable(@Nonnull final View anchor, @Nonnull final BigInteger available, @Nonnull final BigInteger pending)
	{
		dismissPopup();

		final CurrencyTextView viewAvailable = (CurrencyTextView) popupAvailableView.findViewById(R.id.send_coins_popup_available_amount);
		viewAvailable.setPrefix(Constants.CURRENCY_CODE_BITCOIN);
		viewAvailable.setAmount(available);

		final TextView viewPending = (TextView) popupAvailableView.findViewById(R.id.send_coins_popup_available_pending);
		viewPending.setVisibility(pending.signum() > 0 ? VISIBLE : GONE);
		viewPending.setText(activity.getString(R.string.send_coins_fragment_pending,
				GenericUtils.formatValue(pending, Constants.BTC_MAX_PRECISION)));

		popup(anchor, popupAvailableView);
	}

	void popup(@Nonnull final View anchor, @Nonnull final View contentView)
	{
		contentView.measure(MeasureSpec.makeMeasureSpec(MeasureSpec.UNSPECIFIED, 0), MeasureSpec.makeMeasureSpec(MeasureSpec.UNSPECIFIED, 0));

		popupWindow = new PopupWindow(contentView, contentView.getMeasuredWidth(), contentView.getMeasuredHeight(), false);
		popupWindow.showAsDropDown(anchor);

		// hack
		contentView.setBackgroundResource(popupWindow.isAboveAnchor() ? R.drawable.popup_frame_above : R.drawable.popup_frame_below);
	}

	void dismissPopup()
	{
		if (popupWindow != null)
		{
			popupWindow.dismiss();
			popupWindow = null;
		}
	}
}
