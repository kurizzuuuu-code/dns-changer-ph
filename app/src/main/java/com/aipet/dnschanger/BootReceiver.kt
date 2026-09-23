package com.aipet.dnschanger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("dns_prefs", Context.MODE_PRIVATE)
            val autoConnect = prefs.getBoolean("auto_connect_boot", false)
            if (autoConnect) {
                val prepare = VpnService.prepare(context)
                if (prepare == null) {
                    val dns = prefs.getString("selected_dns", "1.1.1.1") ?: "1.1.1.1"
                    context.startService(DnsVpnService.startIntent(context, dns))
                }
            }
        }
    }
}
