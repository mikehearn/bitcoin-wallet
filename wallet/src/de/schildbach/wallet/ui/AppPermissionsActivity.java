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

import android.app.ListActivity;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.SimpleAdapter;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import de.schildbach.wallet.service.ChannelService;
import de.schildbach.wallet_test.R;

import java.util.List;
import java.util.Map;

/**
 * An activity that lets users see which apps they have granted payment channel rights to, and edit those rights.
 * It presents a list of apps, their icons and how much they have and have spent.
 */
public class AppPermissionsActivity extends ListActivity {
	private SharedPreferences permissions;
	private PackageManager packageManager;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		permissions = getSharedPreferences(ChannelService.PREFS_NAME, MODE_PRIVATE);
		packageManager = getPackageManager();
	}

	@Override
	protected void onResume() {
		super.onResume();

		List<Map<String, Object>> data = Lists.newArrayList();

		populateList(data);

		SimpleAdapter adapter = new SimpleAdapter(this, data,
				R.layout.image_and_two_lines_row,
				new String[] {"name", "amounts", "icon"},
				new int[] {android.R.id.text1, android.R.id.text2, R.id.icon});

		adapter.setViewBinder(new SimpleAdapter.ViewBinder() {
			@Override
			public boolean setViewValue(View view, Object o, String s) {
				if (o instanceof Drawable && view instanceof ImageView) {
					((ImageView) view).setImageDrawable((Drawable) o);
					return true;
				}
				return false;
			}
		});

		setListAdapter(adapter);
	}

	private void populateList(List<Map<String, Object>> data) {
		Map<String, ?> typedData = permissions.getAll();
		for (Map.Entry<String, ?> entry : typedData.entrySet()) {
			Long creditAvailable = (Long) entry.getValue();
			String appId = entry.getKey();
			String name = appId;
			Drawable icon = null;
			try {
				ApplicationInfo appInfo = packageManager.getApplicationInfo(appId, 0);
				name = packageManager.getApplicationLabel(appInfo).toString();
				icon = packageManager.getApplicationIcon(appInfo);
			} catch (PackageManager.NameNotFoundException ignored) {
				// Just use the package name instead.
			}
			data.add(makeListRow(icon, name, "has " + creditAvailable + " satoshis remaining"));
		}
	}

	private Map<String, Object> makeListRow(Drawable icon, String name, String amounts) {
		Map<String, Object> entry = Maps.newHashMap();
		entry.put("name", name);
		entry.put("amounts", amounts);
		entry.put("icon", icon);
		return entry;
	}
}
