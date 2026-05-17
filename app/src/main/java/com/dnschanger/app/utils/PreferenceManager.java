package com.dnschanger.app.utils;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * PreferenceManager
 * Menyimpan dan membaca pengaturan DNS pengguna menggunakan SharedPreferences.
 */
public class PreferenceManager {

    private static final String PREF_NAME    = "dns_changer_prefs";
    private static final String KEY_DNS1     = "dns1";
    private static final String KEY_DNS2     = "dns2";
    private static final String KEY_AUTOSTART = "autostart";
    private static final String KEY_PRESET   = "last_preset";

    // Default: Google DNS
    private static final String DEFAULT_DNS1 = "8.8.8.8";
    private static final String DEFAULT_DNS2 = "8.8.4.4";

    private final SharedPreferences prefs;

    public PreferenceManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public String getDns1() {
        return prefs.getString(KEY_DNS1, DEFAULT_DNS1);
    }

    public String getDns2() {
        return prefs.getString(KEY_DNS2, DEFAULT_DNS2);
    }

    public void setDns1(String dns) {
        prefs.edit().putString(KEY_DNS1, dns).apply();
    }

    public void setDns2(String dns) {
        prefs.edit().putString(KEY_DNS2, dns).apply();
    }

    public boolean isAutoStart() {
        return prefs.getBoolean(KEY_AUTOSTART, false);
    }

    public void setAutoStart(boolean value) {
        prefs.edit().putBoolean(KEY_AUTOSTART, value).apply();
    }

    public String getLastPreset() {
        return prefs.getString(KEY_PRESET, "Google");
    }

    public void setLastPreset(String preset) {
        prefs.edit().putString(KEY_PRESET, preset).apply();
    }

    public void saveDns(String dns1, String dns2) {
        prefs.edit()
                .putString(KEY_DNS1, dns1)
                .putString(KEY_DNS2, dns2)
                .apply();
    }
}
