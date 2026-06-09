package eu.siacs.conversations.ui.fragment.settings;

import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.SwitchPreferenceCompat;
import com.google.common.base.Strings;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.QuickConversationsService;
import eu.siacs.conversations.utils.Resolver;
import eu.siacs.conversations.utils.YggdrasilManager;
import java.util.Arrays;

public class ConnectionSettingsFragment extends XmppPreferenceFragment {

    private static final String GROUPS_AND_CONFERENCES = "groups_and_conferences";

    public static boolean hideChannelDiscovery() {
        return QuickConversationsService.isQuicksy()
                || QuickConversationsService.isPlayStoreFlavor()
                || Strings.isNullOrEmpty(Config.CHANNEL_DISCOVERY);
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences_connection, rootKey);
        final var connectionOptions = findPreference(AppSettings.SHOW_CONNECTION_OPTIONS);
        final var channelDiscovery = findPreference(AppSettings.CHANNEL_DISCOVERY_METHOD);
        final var groupsAndConferences = findPreference(GROUPS_AND_CONFERENCES);
        if (connectionOptions == null || channelDiscovery == null || groupsAndConferences == null) {
            throw new IllegalStateException();
        }
        if (QuickConversationsService.isQuicksy()) {
            connectionOptions.setVisible(false);
        }
        if (hideChannelDiscovery()) {
            groupsAndConferences.setVisible(false);
            channelDiscovery.setVisible(false);
        }
        updateYggdrasilSummary();
    }

    private void updateYggdrasilSummary() {
        final SwitchPreferenceCompat yggPref = findPreference(AppSettings.USE_YGGDRASIL);
        if (yggPref == null) return;
        final YggdrasilManager mgr = YggdrasilManager.getInstance();
        if (mgr.isRunning()) {
            final String addr = yggmobile.Yggmobile.getAddress();
            if (addr != null && !addr.isEmpty()) {
                yggPref.setSummary(getString(R.string.pref_use_yggdrasil_summary) + "\n" + addr);
            } else {
                yggPref.setSummary(R.string.pref_use_yggdrasil_summary);
            }
        } else {
            yggPref.setSummary(R.string.pref_use_yggdrasil_summary);
        }
    }

    @Override
    protected void onSharedPreferenceChanged(@NonNull String key) {
        super.onSharedPreferenceChanged(key);
        switch (key) {
            case AppSettings.USE_TOR -> {
                final var appSettings = new AppSettings(requireContext());
                if (appSettings.isUseTor()) {
                    runOnUiThread(
                            () ->
                                    Toast.makeText(
                                                    requireActivity(),
                                                    R.string.audio_video_disabled_tor,
                                                    Toast.LENGTH_LONG)
                                            .show());
                }
                reconnectAccounts();
                requireService().reinitializeMuclumbusService();
            }
            case AppSettings.USE_YGGDRASIL -> {
                final var appSettings = new AppSettings(requireContext());
                if (appSettings.isUseYggdrasil()) {
                    YggdrasilManager.getInstance().start(requireContext());
                    requireView().postDelayed(() -> {                        final String err = YggdrasilManager.getInstance().getLastError();
                        android.util.Log.e("YggdrasilManager", "FULL ERROR: " + err);
                        final String errMsg = err.isEmpty() ? "No error but node not running" : err;
                        {
                            android.widget.ScrollView sv = new android.widget.ScrollView(requireContext());
                            android.widget.TextView tv = new android.widget.TextView(requireContext());
                            tv.setText(errMsg);
                            tv.setTextIsSelectable(true);
                            tv.setPadding(32, 16, 32, 16);
                            sv.addView(tv);
                            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                    .setTitle("Yggdrasil Error")
                                    .setView(sv)
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show();
                        }
                        updateYggdrasilSummary();
                    }, 5000);
                } else {
                    YggdrasilManager.getInstance().stop();
                    updateYggdrasilSummary();
                }
                reconnectAccounts();
            }
            case AppSettings.SHOW_CONNECTION_OPTIONS -> reconnectAccounts();
        }
        if (Arrays.asList(AppSettings.USE_TOR, AppSettings.SHOW_CONNECTION_OPTIONS).contains(key)) {
            final var appSettings = new AppSettings(requireContext());
            if (appSettings.isUseTor() || appSettings.isExtendedConnectionOptions()) {
                return;
            }
            resetUserDefinedHostname();
        }
    }

    private void resetUserDefinedHostname() {
        final var service = requireService();
        for (final Account account : service.getAccounts()) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid() + ": resetting hostname and port to defaults");
            account.setHostname(null);
            account.setPort(Resolver.XMPP_PORT_STARTTLS);
            service.databaseBackend.updateAccount(account);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        requireActivity().setTitle(R.string.pref_connection_options);
        updateYggdrasilSummary();
    }
}

