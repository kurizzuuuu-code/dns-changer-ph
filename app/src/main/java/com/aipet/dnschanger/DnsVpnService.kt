package com.aipet.dnschanger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * DNS-only VPN with DNS-over-HTTPS.
 * - Captures UDP/53 from TUN, forwards as DoH POST to port 443
 * - Carrier sees only TLS to cloudflare/google/quad9/adguard, can't inspect or tamper
 */
class DnsVpnService : VpnService() {

    companion object {
        private const val TAG = "DnsVpn"
        private const val CHANNEL_ID = "dns_vpn_channel"
        private const val NOTIF_ID = 1001

        const val ACTION_START = "com.aipet.dnschanger.action.START"
        const val ACTION_STOP = "com.aipet.dnschanger.action.STOP"
        const val EXTRA_EXCLUDED_APPS = "extra_excluded_apps"

        fun startIntent(ctx: Context, dns: String, excludedApps: ArrayList<String> = arrayListOf()): Intent {
            return Intent(ctx, DnsVpnService::class.java).apply {
                action = ACTION_START
                putExtra(MainActivity.EXTRA_DNS, dns)
                putStringArrayListExtra(EXTRA_EXCLUDED_APPS, excludedApps)
            }
        }

        fun stopIntent(ctx: Context): Intent {
            return Intent(ctx, DnsVpnService::class.java).apply { action = ACTION_STOP }
        }

        var isRunning = false
            private set
        var currentDns = "1.1.1.1"
            private set
    }

    private var tun: ParcelFileDescriptor? = null
    private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val dns = intent.getStringExtra(MainActivity.EXTRA_DNS) ?: "1.1.1.1"
                val excludedApps = intent.getStringArrayListExtra(EXTRA_EXCLUDED_APPS) ?: arrayListOf()
                startVpn(dns, excludedApps)
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startVpn(upstreamDns: String, excludedApps: ArrayList<String>) {
        if (running) stopVpn()
        try {
            val builder = Builder()
                .setSession("DNS Changer PH")
                .addAddress("10.0.0.2", 32)
                .addDnsServer(if (upstreamDns.startsWith("http")) "1.1.1.1" else upstreamDns)
                .addRoute(if (upstreamDns.startsWith("http")) "1.1.1.1" else upstreamDns, 32)
                .addRoute("10.0.0.0", 8)

            // Split tunneling - Disallowed applications
            for (pkg in excludedApps) {
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to disallow package: $pkg", e)
                }
            }

            tun = builder.establish()
            running = true
            isRunning = true
            currentDns = upstreamDns

            startForegroundNotification(upstreamDns)
            Log.i(TAG, "VPN started with DNS $upstreamDns")

            thread(name = "dns-forward", isDaemon = true) {
                forwardLoop(upstreamDns)
            }
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            stopVpn()
        }
    }

    private fun dohUrlFor(ipOrUrl: String): String {
        if (ipOrUrl.startsWith("http://") || ipOrUrl.startsWith("https://")) {
            return ipOrUrl
        }
        return when (ipOrUrl) {
            "8.8.8.8", "8.8.4.4" -> "https://dns.google/dns-query"
            "9.9.9.9" -> "https://dns.quad9.net:5053/dns-query"
            "94.140.14.14", "94.140.15.15" -> "https://dns.adguard-dns.com/dns-query"
            "76.76.2.0", "76.76.10.0" -> "https://freedns.controld.com/p0"
            "208.67.222.222", "208.67.220.220" -> "https://doh.opendns.com/dns-query"
            "185.228.168.9" -> "https://doh.cleanbrowsing.org/doh/family-filter/"
            else -> "https://cloudflare-dns.com/dns-query"
        }
    }

    private inner class ProtectiveFactory(
        private val delegate: SSLSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory() as SSLSocketFactory
    ) : SSLSocketFactory() {
        private fun guard(s: Socket): Socket { try { protect(s) } catch (_: Exception) {} ; return s }
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
        override fun createSocket(): Socket = guard(delegate.createSocket())
        override fun createSocket(s: Socket?, h: String?, p: Int, a: Boolean): Socket =
            guard(delegate.createSocket(s, h, p, a))
        override fun createSocket(h: String?, p: Int): Socket = guard(delegate.createSocket(h, p))
        override fun createSocket(h: InetAddress?, p: Int): Socket =
            guard(delegate.createSocket(h, p))
        override fun createSocket(h: String?, p: Int, l: InetAddress?, lp: Int): Socket =
            guard(delegate.createSocket(h, p, l, lp))
        override fun createSocket(a: InetAddress?, p: Int, l: InetAddress?, lp: Int): Socket =
            guard(delegate.createSocket(a, p, l, lp))
    }

    private val protectiveFactory: SSLSocketFactory by lazy { ProtectiveFactory() }

    private fun dohQuery(dohUrl: String, query: ByteArray): ByteArray {
        val conn = (URL(dohUrl).openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = protectiveFactory
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/dns-message")
            setRequestProperty("Accept", "application/dns-message")
            connectTimeout = 6000
            readTimeout = 6000
            doOutput = true
            doInput = true
        }
        conn.outputStream.use { it.write(query) }
        val code = conn.responseCode
        if (code != 200) throw IOException("DoH $code")
        conn.inputStream.use { inp ->
            val bos = ByteArrayOutputStream()
            inp.copyTo(bos)
            return bos.toByteArray()
        }
    }

    private fun forwardLoop(upstreamDns: String) {
        val fd = tun ?: return
        val inp = FileInputStream(fd.fileDescriptor)
        val out = FileOutputStream(fd.fileDescriptor)
        val dohUrl = dohUrlFor(upstreamDns)
        Log.i(TAG, "DoH forwarding to $dohUrl")
        val buf = ByteArray(32767)

        while (running) {
            val len = try { inp.read(buf) } catch (_: Exception) { break }
            if (len <= 0 || len < 20) continue
            val ihl = (buf[0].toInt() and 0x0F) * 4
            if (buf[9] != 17.toByte()) continue
            if (len < ihl + 8) continue
            val dstPort = ((buf[ihl + 2].toInt() and 0xFF) shl 8) or (buf[ihl + 3].toInt() and 0xFF)
            if (dstPort != 53) continue

            val dnsPayloadLen = len - ihl - 8
            val dnsPayload = buf.copyOfRange(ihl + 8, ihl + 8 + dnsPayloadLen)
            val srcIp = buf.copyOfRange(12, 16)
            val srcPort = ((buf[ihl].toInt() and 0xFF) shl 8) or (buf[ihl + 1].toInt() and 0xFF)

            try {
                val dnsResp = dohQuery(dohUrl, dnsPayload)

                val respLen = dnsResp.size
                val totalLen = 20 + 8 + respLen
                val outPkt = ByteBuffer.allocate(totalLen)
                outPkt.put(0x45.toByte())
                outPkt.put(0)
                outPkt.putShort(totalLen.toShort())
                outPkt.putShort(0)
                outPkt.putShort(0)
                outPkt.put(64.toByte())
                outPkt.put(17.toByte())
                outPkt.putShort(0)
                outPkt.put(buf.copyOfRange(16, 20))
                outPkt.put(srcIp)
                outPkt.putShort(53)
                outPkt.putShort(srcPort.toShort())
                outPkt.putShort((8 + respLen).toShort())
                outPkt.putShort(0)
                outPkt.put(dnsResp, 0, respLen)

                val arr = outPkt.array()
                var sum = 0
                for (i in 0 until 20 step 2) {
                    sum += ((arr[i].toInt() and 0xFF) shl 8) or (arr[i + 1].toInt() and 0xFF)
                }
                while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
                val csum = sum.inv() and 0xFFFF
                arr[10] = (csum shr 8).toByte()
                arr[11] = (csum and 0xFF).toByte()

                out.write(arr)
            } catch (_: Exception) {
            }
        }
    }

    private fun startForegroundNotification(dns: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID,
                "DNS VPN Active",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(chan)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DNS Changer PH Active")
            .setContentText("Connected to $dns")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopPendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIF_ID, notif)
    }

    private fun stopVpn() {
        running = false
        isRunning = false
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
