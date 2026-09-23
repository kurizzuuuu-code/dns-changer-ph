package com.aipet.dnschanger

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DNS = "extra_dns"
        private const val VPN_REQUEST = 1001
    }

    private var pendingDns: String = "1.1.1.1"
    private lateinit var tvStatus: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var statusDot: View
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var dnsGroup: RadioGroup

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

        dnsGroup = findViewById(R.id.dnsGroup)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        val btnTest = findViewById<Button>(R.id.btnTest)
        tvStatus = findViewById(R.id.tvStatus)
        tvSpeed = findViewById(R.id.tvSpeed)
        statusDot = findViewById(R.id.statusDot)
        val mascot = findViewById<ImageView>(R.id.mascot)

        try {
            Glide.with(this).asGif().load(R.drawable.mascot).into(mascot)
        } catch (_: Exception) {}

        btnTest.setOnClickListener {
            btnTest.isEnabled = false
            tvSpeed.text = "Testing connection speeds..."
            thread(isDaemon = true) {
                val cf = pingHost("1.1.1.1")
                val gg = pingHost("8.8.8.8")
                val q9 = pingHost("9.9.9.9")
                fun fmt(v: Long) = if (v == Long.MAX_VALUE) "timeout" else "${v}ms"
                val fastest = listOf("1.1.1.1" to cf, "8.8.8.8" to gg, "9.9.9.9" to q9)
                    .filter { it.second != Long.MAX_VALUE }
                    .minByOrNull { it.second }

                runOnUiThread {
                    tvSpeed.text = "1.1.1.1: ${fmt(cf)}   •   8.8.8.8: ${fmt(gg)}   •   9.9.9.9: ${fmt(q9)}" +
                        (fastest?.let { "\nFastest Server: ${it.first} (${it.second}ms)" } ?: "\nAll servers timed out")
                    if (fastest != null) {
                        val id = when (fastest.first) {
                            "8.8.8.8" -> R.id.rbGoogle
                            "9.9.9.9" -> R.id.rbQuad9
                            else -> R.id.rbCloudflare
                        }
                        dnsGroup.check(id)
                        pendingDns = fastest.first
                    }
                    btnTest.isEnabled = true
                }
            }
        }

        btnConnect.setOnClickListener {
            pendingDns = when (dnsGroup.checkedRadioButtonId) {
                R.id.rbGoogle -> "8.8.8.8"
                R.id.rbQuad9 -> "9.9.9.9"
                else -> "1.1.1.1"
            }
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
    }

    @Deprecated("Use Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST && resultCode == RESULT_OK) {
            startVpn(pendingDns)
        }
    }

    private fun startVpn(dns: String) {
        val intent = DnsVpnService.startIntent(this, dns)
        startService(intent)
        updateConnectionUI(true, dns)
    }

    private fun updateConnectionUI(isConnected: Boolean, dns: String) {
        if (isConnected) {
            statusDot.setBackgroundResource(R.drawable.bg_status_dot_connected)
            tvStatus.text = "Status: Connected ($dns)"
            btnConnect.visibility = View.GONE
            btnDisconnect.visibility = View.VISIBLE
        } else {
            statusDot.setBackgroundResource(R.drawable.bg_status_dot_disconnected)
            tvStatus.text = "Status: Disconnected"
            btnConnect.visibility = View.VISIBLE
            btnDisconnect.visibility = View.GONE
        }
    }
}
