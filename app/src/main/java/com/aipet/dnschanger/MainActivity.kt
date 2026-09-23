package com.aipet.dnschanger

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.bumptech.glide.Glide
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DNS = "extra_dns"
        private const val VPN_REQUEST = 1001
        private const val PREFS_NAME = "dns_prefs"
        private const val KEY_SELECTED_DNS = "selected_dns"
        private const val KEY_AUTO_BOOT = "auto_connect_boot"
        private const val KEY_EXCLUDED_APPS = "excluded_apps"
    }

    enum class MascotState {
        CONNECTING, CONNECTED, DISCONNECTED
    }

    private var pendingDns: String = "1.1.1.1"
    private lateinit var tvStatus: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var statusDot: View
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var dnsGroup: RadioGroup
    private lateinit var tilCustomDns: TextInputLayout
    private lateinit var etCustomDns: TextInputEditText
    private lateinit var swAutoConnect: SwitchMaterial
    private lateinit var btnExcludeApps: Button
    private lateinit var mascot: ImageView
    private lateinit var prefs: SharedPreferences

    private val excludedApps = HashSet<String>()

    private fun pingHost(ip: String): Long {
        var best = Long.MAX_VALUE
        repeat(2) {
            try {
                val s = Socket()
                val t = System.currentTimeMillis()
                s.connect(InetSocketAddress(ip, 443), 2000)
                best = minOf(best, System.currentTimeMillis() - t)
                s.close()
            } catch (_: Exception) {}
        }
        return best
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        dnsGroup = findViewById(R.id.dnsGroup)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        val btnTest = findViewById<Button>(R.id.btnTest)
        tvStatus = findViewById(R.id.tvStatus)
        tvSpeed = findViewById(R.id.tvSpeed)
        statusDot = findViewById(R.id.statusDot)
        tilCustomDns = findViewById(R.id.tilCustomDns)
        etCustomDns = findViewById(R.id.etCustomDns)
        swAutoConnect = findViewById(R.id.swAutoConnect)
        btnExcludeApps = findViewById(R.id.btnExcludeApps)
        mascot = findViewById(R.id.mascot)

        val savedExcluded = prefs.getStringSet(KEY_EXCLUDED_APPS, emptySet()) ?: emptySet()
        excludedApps.addAll(savedExcluded)

        swAutoConnect.isChecked = prefs.getBoolean(KEY_AUTO_BOOT, false)
        swAutoConnect.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_BOOT, isChecked).apply()
        }

        dnsGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbCustom) {
                tilCustomDns.visibility = View.VISIBLE
            } else {
                tilCustomDns.visibility = View.GONE
            }
        }

        updateMascotState(MascotState.DISCONNECTED)

        btnTest.setOnClickListener {
            btnTest.isEnabled = false
            tvSpeed.text = "Testing connection speeds..."
            updateMascotState(MascotState.CONNECTING)

            thread(isDaemon = true) {
                val cf = pingHost("1.1.1.1")
                val gg = pingHost("8.8.8.8")
                val q9 = pingHost("9.9.9.9")
                val ag = pingHost("94.140.14.14")
                val cd = pingHost("76.76.2.0")
                val od = pingHost("208.67.222.222")

                fun fmt(v: Long) = if (v == Long.MAX_VALUE) "timeout" else "${v}ms"
                val fastest = listOf(
                    "1.1.1.1" to cf,
                    "8.8.8.8" to gg,
                    "9.9.9.9" to q9,
                    "94.140.14.14" to ag,
                    "76.76.2.0" to cd,
                    "208.67.222.222" to od
                ).filter { it.second != Long.MAX_VALUE }.minByOrNull { it.second }

                runOnUiThread {
                    tvSpeed.text = "1.1.1.1: ${fmt(cf)} • 8.8.8.8: ${fmt(gg)} • 9.9.9.9: ${fmt(q9)}\nAdGuard: ${fmt(ag)} • Control D: ${fmt(cd)} • OpenDNS: ${fmt(od)}" +
                        (fastest?.let { "\nFastest Server: ${it.first} (${it.second}ms)" } ?: "\nAll servers timed out")

                    if (fastest != null) {
                        val id = when (fastest.first) {
                            "8.8.8.8" -> R.id.rbGoogle
                            "9.9.9.9" -> R.id.rbQuad9
                            "94.140.14.14" -> R.id.rbAdGuard
                            "76.76.2.0" -> R.id.rbControlD
                            "208.67.222.222" -> R.id.rbOpenDns
                            else -> R.id.rbCloudflare
                        }
                        dnsGroup.check(id)
                        pendingDns = fastest.first
                    }
                    btnTest.isEnabled = true
                    updateMascotState(if (DnsVpnService.isRunning) MascotState.CONNECTED else MascotState.DISCONNECTED)
                }
            }
        }

        btnConnect.setOnClickListener {
            pendingDns = getSelectedDnsAddress()
            prefs.edit().putString(KEY_SELECTED_DNS, pendingDns).apply()

            updateMascotState(MascotState.CONNECTING)
            val prepare = VpnService.prepare(this)
            if (prepare != null) {
                startActivityForResult(prepare, VPN_REQUEST)
            } else {
                startVpn(pendingDns)
            }
        }

        btnDisconnect.setOnClickListener {
            startService(DnsVpnService.stopIntent(this))
            updateConnectionUI(false, "")
        }

        btnExcludeApps.setOnClickListener {
            showAppExclusionDialog()
        }

        if (DnsVpnService.isRunning) {
            updateConnectionUI(true, DnsVpnService.currentDns)
        }
    }

    private fun getSelectedDnsAddress(): String {
        return when (dnsGroup.checkedRadioButtonId) {
            R.id.rbGoogle -> "8.8.8.8"
            R.id.rbQuad9 -> "9.9.9.9"
            R.id.rbAdGuard -> "94.140.14.14"
            R.id.rbControlD -> "76.76.2.0"
            R.id.rbOpenDns -> "208.67.222.222"
            R.id.rbCustom -> {
                val input = etCustomDns.text.toString().trim()
                if (input.isNotEmpty()) input else "1.1.1.1"
            }
            else -> "1.1.1.1"
        }
    }

    private fun showAppExclusionDialog() {
        val pm = packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || it.packageName == "com.facebook.katana" }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

        val appNames = installedApps.map { pm.getApplicationLabel(it).toString() }.toTypedArray()
        val checkedBooleans = BooleanArray(installedApps.size) { excludedApps.contains(installedApps[it].packageName) }

        AlertDialog.Builder(this)
            .setTitle("Exclude Apps from DNS VPN")
            .setMultiChoiceItems(appNames, checkedBooleans) { _, which, isChecked ->
                val pkg = installedApps[which].packageName
                if (isChecked) {
                    excludedApps.add(pkg)
                } else {
                    excludedApps.remove(pkg)
                }
            }
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putStringSet(KEY_EXCLUDED_APPS, excludedApps).apply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Deprecated("Use Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST && resultCode == RESULT_OK) {
            startVpn(pendingDns)
        } else {
            updateMascotState(MascotState.DISCONNECTED)
        }
    }

    private fun startVpn(dns: String) {
        val intent = DnsVpnService.startIntent(this, dns, ArrayList(excludedApps))
        startService(intent)
        updateConnectionUI(true, dns)
    }

    private fun updateConnectionUI(isConnected: Boolean, dns: String) {
        if (isConnected) {
            statusDot.setBackgroundResource(R.drawable.bg_status_dot_connected)
            tvStatus.text = "Status: Connected ($dns)"
            btnConnect.visibility = View.GONE
            btnDisconnect.visibility = View.VISIBLE
            updateMascotState(MascotState.CONNECTED)
        } else {
            statusDot.setBackgroundResource(R.drawable.bg_status_dot_disconnected)
            tvStatus.text = "Status: Disconnected"
            btnConnect.visibility = View.VISIBLE
            btnDisconnect.visibility = View.GONE
            updateMascotState(MascotState.DISCONNECTED)
        }
    }

    private fun updateMascotState(state: MascotState) {
        val resName = when (state) {
            MascotState.CONNECTING -> "connecting"
            MascotState.CONNECTED -> "connected"
            MascotState.DISCONNECTED -> "disconnected"
        }
        val resId = resources.getIdentifier(resName, "drawable", packageName)
        val targetRes = if (resId != 0) resId else R.drawable.mascot
        try {
            Glide.with(this).asGif().load(targetRes).into(mascot)
        } catch (_: Exception) {}
    }
}
