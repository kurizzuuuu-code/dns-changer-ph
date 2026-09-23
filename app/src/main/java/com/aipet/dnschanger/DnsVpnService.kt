package com.aipet.dnschanger

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Minimal DNS-only VPN.
 * - Adds a TUN interface with addDnsServer(upstream)
 * - Routes only port-53 traffic for that upstream through the TUN
 * - Forwards DNS UDP packets to upstream via a protected socket
 * - All other app traffic goes direct (no speed loss, no full proxy)
 */
class DnsVpnService : VpnService() {

    companion object {
        private const val TAG = "DnsVpn"
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"

        fun startIntent(ctx: Context, dns: String): Intent {
            return Intent(ctx, DnsVpnService::class.java).apply {
                action = ACTION_START
                putExtra(MainActivity.EXTRA_DNS, dns)
            }
        }

        fun stopIntent(ctx: Context): Intent {
            return Intent(ctx, DnsVpnService::class.java).apply { action = ACTION_STOP }
        }
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
                startVpn(dns)
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startVpn(upstreamDns: String) {
        if (running) stopVpn()
        try {
            val builder = Builder()
                .setSession("DNS Changer PH")
                .addAddress("10.0.0.2", 32)
                .addDnsServer(upstreamDns)
                // Only route DNS server IP through VPN, rest bypasses (DNS-only mode)
                .addRoute(upstreamDns, 32)
                .addRoute("10.0.0.0", 8)

            tun = builder.establish()
            running = true
            Log.i(TAG, "VPN started with DNS $upstreamDns")
            thread(name = "dns-forward", isDaemon = true) {
                forwardLoop(upstreamDns)
            }
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            stopVpn()
        }
    }

    private fun forwardLoop(upstreamDns: String) {
        val fd = tun ?: return
        val inp = FileInputStream(fd.fileDescriptor)
        val out = FileOutputStream(fd.fileDescriptor)
        val upstream = InetAddress.getByName(upstreamDns)
        val protectSocket = DatagramSocket()
        if (!protect(protectSocket)) {
            Log.e(TAG, "protect() failed")
        }
        protectSocket.soTimeout = 5000
        val buf = ByteArray(32767)

        try {
            while (running) {
                val len = try {
                    inp.read(buf)
                } catch (e: Exception) {
                    break
                }
                if (len <= 0) continue
                // Parse IPv4 + UDP, dst port 53 only
                if (len < 20) continue
                val ihl = (buf[0].toInt() and 0x0F) * 4
                if (buf[9] != 17.toByte()) continue // not UDP
                if (len < ihl + 8) continue
                val dstPort = ((buf[ihl + 2].toInt() and 0xFF) shl 8) or (buf[ihl + 3].toInt() and 0xFF)
                if (dstPort != 53) continue

                val dnsPayloadLen = len - ihl - 8
                val dnsPayload = buf.copyOfRange(ihl + 8, ihl + 8 + dnsPayloadLen)
                val srcIp = buf.copyOfRange(12, 16)
                val srcPort = ((buf[ihl].toInt() and 0xFF) shl 8) or (buf[ihl + 1].toInt() and 0xFF)

                try {
                    val req = DatagramPacket(dnsPayload, dnsPayload.size, upstream, 53)
                    protectSocket.send(req)
                    val respBuf = ByteArray(4096)
                    val resp = DatagramPacket(respBuf, respBuf.size)
                    protectSocket.receive(resp)

                    // Build IPv4 + UDP reply back into TUN
                    val respLen = resp.length
                    val totalLen = 20 + 8 + respLen
                    val outPkt = ByteBuffer.allocate(totalLen)
                    outPkt.put(0x45.toByte()) // version + IHL
                    outPkt.put(0) // TOS
                    outPkt.putShort(totalLen.toShort())
                    outPkt.putShort(0) // ID
                    outPkt.putShort(0) // flags/frag
                    outPkt.put(64.toByte()) // TTL
                    outPkt.put(17.toByte()) // UDP
                    outPkt.putShort(0) // checksum placeholder
                    outPkt.put(buf.copyOfRange(16, 20)) // src = orig dst (VPN addr)
                    outPkt.put(srcIp) // dst = orig src
                    // UDP header
                    outPkt.putShort(53) // src port
                    outPkt.putShort(srcPort.toShort()) // dst port
                    outPkt.putShort((8 + respLen).toShort())
                    outPkt.putShort(0) // UDP checksum 0 = skip (allowed for IPv4)
                    outPkt.put(respBuf, 0, respLen)

                    val arr = outPkt.array()
                    // IP checksum
                    var sum = 0
                    for (i in 0 until 20 step 2) {
                        sum += ((arr[i].toInt() and 0xFF) shl 8) or (arr[i + 1].toInt() and 0xFF)
                    }
                    while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
                    val csum = sum.inv() and 0xFFFF
                    arr[10] = (csum shr 8).toByte()
                    arr[11] = (csum and 0xFF).toByte()

                    out.write(arr)
                } catch (e: Exception) {
                    // timeout / upstream fail — just skip, next query will retry
                }
            }
        } finally {
            try { protectSocket.close() } catch (_: Exception) {}
        }
    }

    private fun stopVpn() {
        running = false
        try { tun?.close() } catch (_: Exception) {}
        tun = null
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
