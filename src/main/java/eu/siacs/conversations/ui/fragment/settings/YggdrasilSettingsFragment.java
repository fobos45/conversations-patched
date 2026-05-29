package eu.siacs.conversations.ui.fragment.settings;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.R;
import eu.siacs.conversations.services.YggdrasilService;

public class YggdrasilSettingsFragment extends XmppPreferenceFragment {

    private static final int REQUEST_VPN_PERMISSION = 1001;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences_yggdrasil, rootKey);

        final SwitchPreferenceCompat enablePref =
                findPreference(AppSettings.YGGDRASIL_ENABLED);
        final Preference addressPref = findPreference("yggdrasil_address");
        final Preference resetPref   = findPreference("yggdrasil_reset_config");

        // Show current address if running
        updateAddressLabel(addressPref);

        if (enablePref != null) {
            enablePref.setOnPreferenceChangeListener((pref, newValue) -> {
                final boolean enable = (Boolean) newValue;
                if (enable) {
                    // Check VPN permission
                    final Intent vpnIntent = VpnService.prepare(requireContext());
                    if (vpnIntent != null) {
                        startActivityForResult(vpnIntent, REQUEST_VPN_PERMISSION);
                        return false; // wait for result
                    }
                    YggdrasilService.start(requireContext());
                    updateAddressLabel(addressPref);
                } else {
                    YggdrasilService.stop(requireContext());
                    if (addressPref != null) {
                        addressPref.setSummary(R.string.pref_yggdrasil_not_running);
                    }
                }
                return true;
            });
        }

        if (resetPref != null) {
            resetPref.setOnPreferenceClickListener(pref -> {
                new AppSettings(requireContext()).setYggdrasilConfig("");
                YggdrasilService.stop(requireContext());
                if (enablePref != null && enablePref.isChecked()) {
                    YggdrasilService.start(requireContext());
                }
                Toast.makeText(requireContext(),
                        R.string.pref_yggdrasil_reset_done, Toast.LENGTH_SHORT).show();
                return true;
            });
        }
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        if (requestCode == REQUEST_VPN_PERMISSION && resultCode == Activity.RESULT_OK) {
            YggdrasilService.start(requireContext());
            final SwitchPreferenceCompat enablePref =
                    findPreference(AppSettings.YGGDRASIL_ENABLED);
            if (enablePref != null) enablePref.setChecked(true);
            updateAddressLabel(findPreference("yggdrasil_address"));
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateAddressLabel(findPreference("yggdrasil_address"));
    }

    private void updateAddressLabel(final Preference addressPref) {
        if (addressPref == null) return;
        final String addr = YggdrasilService.getAddress();
        if (addr != null && !addr.isEmpty()) {
            addressPref.setSummary(addr);
        } else {
            addressPref.setSummary(R.string.pref_yggdrasil_not_running);
        }
    }
}
