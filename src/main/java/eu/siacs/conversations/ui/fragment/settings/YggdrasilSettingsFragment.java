package eu.siacs.conversations.ui.fragment.settings;

import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.R;
import eu.siacs.conversations.services.YggdrasilService;

public class YggdrasilSettingsFragment extends XmppPreferenceFragment {

    // Modern Activity Result API — must be registered before onCreatePreferences
    private final ActivityResultLauncher<Intent> vpnPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == android.app.Activity.RESULT_OK) {
                            // User granted VPN permission — start service and update switch
                            YggdrasilService.start(requireContext());
                            final SwitchPreferenceCompat enablePref =
                                    findPreference(AppSettings.YGGDRASIL_ENABLED);
                            if (enablePref != null) {
                                enablePref.setChecked(true);
                            }
                            updateAddressLabel(findPreference("yggdrasil_address"));
                        } else {
                            // User denied — keep switch off
                            final SwitchPreferenceCompat enablePref =
                                    findPreference(AppSettings.YGGDRASIL_ENABLED);
                            if (enablePref != null) {
                                enablePref.setChecked(false);
                            }
                        }
                    });

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState,
                                    @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences_yggdrasil, rootKey);

        final SwitchPreferenceCompat enablePref =
                findPreference(AppSettings.YGGDRASIL_ENABLED);
        final Preference addressPref = findPreference("yggdrasil_address");
        final Preference resetPref   = findPreference("yggdrasil_reset_config");

        updateAddressLabel(addressPref);

        if (enablePref != null) {
            enablePref.setOnPreferenceChangeListener((pref, newValue) -> {
                final boolean enable = (Boolean) newValue;
                if (enable) {
                    // Check if VPN permission is needed
                    final Intent vpnIntent = VpnService.prepare(requireContext());
                    if (vpnIntent != null) {
                        // Launch system VPN permission dialog
                        vpnPermissionLauncher.launch(vpnIntent);
                        return false; // don't flip switch yet — wait for result
                    }
                    // Permission already granted
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
                        R.string.pref_yggdrasil_reset_done,
                        Toast.LENGTH_SHORT).show();
                return true;
            });
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
