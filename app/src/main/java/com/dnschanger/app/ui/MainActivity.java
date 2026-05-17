package com.dnschanger.app.ui;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.dnschanger.app.R;
import com.dnschanger.app.service.DNSVpnService;
import com.dnschanger.app.utils.PreferenceManager;

import java.util.regex.Pattern;

/**
 * MainActivity
 *
 * Tampilan utama aplikasi DNS Changer.
 * Fitur:
 * - Pilih preset DNS populer (Google, Cloudflare, OpenDNS, dll.)
 * - Input DNS kustom
 * - Tombol Start/Stop VPN
 * - Indikator status aktif/tidak aktif
 * - Opsi Auto Start setelah reboot
 */
public class MainActivity extends Activity {

    public static final String ACTION_VPN_STATUS = "com.dnschanger.VPN_STATUS";
    private static final int VPN_REQUEST_CODE = 100;

    // Preset DNS: {nama, dns1, dns2}
    private static final String[][] DNS_PRESETS = {
            {"Custom",         "",              ""},
            {"Google DNS",     "8.8.8.8",       "8.8.4.4"},
            {"Cloudflare",     "1.1.1.1",       "1.0.0.1"},
            {"OpenDNS",        "208.67.222.222","208.67.220.220"},
            {"Quad9",          "9.9.9.9",       "149.112.112.112"},
            {"AdGuard",        "94.140.14.14",  "94.140.15.15"},
            {"Comodo Secure",  "8.26.56.26",    "8.20.247.20"},
    };

    private static final Pattern IP_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$");

    // Views
    private Spinner   spinnerPreset;
    private EditText  etDns1, etDns2;
    private Button    btnToggle;
    private TextView  tvStatus, tvDnsInfo;
    private CheckBox  cbAutoStart;
    private View      statusIndicator;

    private PreferenceManager prefs;
    private boolean vpnActive = false;

    // ------------------------------------------------------------------ //
    //  Lifecycle
    // ------------------------------------------------------------------ //

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new PreferenceManager(this);
        initViews();
        setupPresetSpinner();
        loadSavedSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(statusReceiver, new IntentFilter(ACTION_VPN_STATUS));
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(statusReceiver);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            startVpnService();
        } else if (requestCode == VPN_REQUEST_CODE) {
            Toast.makeText(this, "Izin VPN ditolak", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ //
    //  UI Setup
    // ------------------------------------------------------------------ //

    private void initViews() {
        spinnerPreset   = (Spinner)  findViewById(R.id.spinnerPreset);
        etDns1          = (EditText) findViewById(R.id.etDns1);
        etDns2          = (EditText) findViewById(R.id.etDns2);
        btnToggle       = (Button)   findViewById(R.id.btnToggle);
        tvStatus        = (TextView) findViewById(R.id.tvStatus);
        tvDnsInfo       = (TextView) findViewById(R.id.tvDnsInfo);
        cbAutoStart     = (CheckBox) findViewById(R.id.cbAutoStart);
        statusIndicator = findViewById(R.id.statusIndicator);

        btnToggle.setOnClickListener(v -> onToggleClicked());
        cbAutoStart.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.setAutoStart(isChecked));
    }

    private void setupPresetSpinner() {
        String[] names = new String[DNS_PRESETS.length];
        for (int i = 0; i < DNS_PRESETS.length; i++) {
            names[i] = DNS_PRESETS[i][0];
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerPreset.setAdapter(adapter);

        spinnerPreset.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos == 0) return;  // Custom – biarkan user ketik sendiri
                etDns1.setText(DNS_PRESETS[pos][1]);
                etDns2.setText(DNS_PRESETS[pos][2]);
                prefs.setLastPreset(DNS_PRESETS[pos][0]);
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Pilih preset yang terakhir digunakan
        String lastPreset = prefs.getLastPreset();
        for (int i = 0; i < DNS_PRESETS.length; i++) {
            if (DNS_PRESETS[i][0].equals(lastPreset)) {
                spinnerPreset.setSelection(i);
                break;
            }
        }
    }

    private void loadSavedSettings() {
        etDns1.setText(prefs.getDns1());
        etDns2.setText(prefs.getDns2());
        cbAutoStart.setChecked(prefs.isAutoStart());
        updateStatusUI(false, "VPN tidak aktif");
    }

    // ------------------------------------------------------------------ //
    //  Button Logic
    // ------------------------------------------------------------------ //

    private void onToggleClicked() {
        if (vpnActive) {
            stopVpnService();
        } else {
            if (!validateInputs()) return;
            saveDnsSettings();
            requestVpnPermission();
        }
    }

    private boolean validateInputs() {
        String d1 = etDns1.getText().toString().trim();
        String d2 = etDns2.getText().toString().trim();

        if (d1.isEmpty()) {
            etDns1.setError("DNS Utama wajib diisi");
            return false;
        }
        if (!IP_PATTERN.matcher(d1).matches()) {
            etDns1.setError("Format IP tidak valid");
            return false;
        }
        if (!d2.isEmpty() && !IP_PATTERN.matcher(d2).matches()) {
            etDns2.setError("Format IP tidak valid");
            return false;
        }
        return true;
    }

    private void saveDnsSettings() {
        String d1 = etDns1.getText().toString().trim();
        String d2 = etDns2.getText().toString().trim();
        prefs.saveDns(d1, d2);
    }

    private void requestVpnPermission() {
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            // Butuh izin dari user
            startActivityForResult(intent, VPN_REQUEST_CODE);
        } else {
            // Sudah ada izin
            startVpnService();
        }
    }

    private void startVpnService() {
        Intent intent = new Intent(this, DNSVpnService.class);
        intent.setAction(DNSVpnService.ACTION_START);
        startService(intent);
        Toast.makeText(this, "Memulai VPN DNS...", Toast.LENGTH_SHORT).show();
    }

    private void stopVpnService() {
        Intent intent = new Intent(this, DNSVpnService.class);
        intent.setAction(DNSVpnService.ACTION_STOP);
        startService(intent);
    }

    // ------------------------------------------------------------------ //
    //  Status UI
    // ------------------------------------------------------------------ //

    private void updateStatusUI(boolean active, String message) {
        vpnActive = active;

        tvStatus.setText(message);
        tvDnsInfo.setText(active
                ? "DNS: " + prefs.getDns1() + " / " + prefs.getDns2()
                : "");

        if (active) {
            statusIndicator.setBackgroundColor(0xFF4CAF50);  // Hijau
            btnToggle.setText("STOP VPN");
            btnToggle.setBackgroundColor(0xFFE53935);        // Merah
        } else {
            statusIndicator.setBackgroundColor(0xFF9E9E9E);  // Abu-abu
            btnToggle.setText("START VPN");
            btnToggle.setBackgroundColor(0xFF1E88E5);        // Biru
        }

        // Nonaktifkan edit saat VPN aktif
        etDns1.setEnabled(!active);
        etDns2.setEnabled(!active);
        spinnerPreset.setEnabled(!active);
    }

    // ------------------------------------------------------------------ //
    //  Broadcast Receiver
    // ------------------------------------------------------------------ //

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean active  = intent.getBooleanExtra("active", false);
            String  message = intent.getStringExtra("message");
            updateStatusUI(active, message != null ? message : "");
        }
    };
}
