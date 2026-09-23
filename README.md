# DNS Changer PH (Android)
Made by kurizu.

One-tap DNS changer to bypass carrier DNS blocks (Globe / GOMO / TNT / DITO).
No root. Uses Android local VPN for DNS-only (other traffic goes direct).

## Download (no build needed)
1. Go to Releases on the right side of this page
2. Download `app-debug.apk` from the latest release
3. Open it on your phone > Install > allow Install unknown apps
4. Open DNS Changer PH > Test fastest DNS > Connect > Allow VPN

## Build APK from source

## How it works
- Creates a TUN with `addDnsServer(1.1.1.1 or 8.8.8.8 or 9.9.9.9)`
- Only routes the upstream DNS IP through VPN, forwards UDP/53 to it
- No full traffic proxy, so speed stays normal

## Build APK (2 min, on this PC)
1. Install Android Studio (includes JDK 17 + SDK)
2. Open this folder `dns-changer-android` in Android Studio
3. Let Gradle sync (downloads dependencies)
4. Build > Generate App Bundles/APKs > Build APK(s) > Debug
5. Copy `app/build/outputs/apk/debug/app-debug.apk` to your phone via USB and install
   (enable Install unknown apps when asked)

## Use
1. Open app > pick Cloudflare 1.1.1.1 > Connect > Allow VPN
2. Open Discord and test
3. If Discord chat loads but voice stuck on RTC Connecting, Globe is doing IP/SNI block —
   DNS alone can't fix that, use Cloudflare WARP or a VPN.

## Files
- `app/src/main/java/com/aipet/dnschanger/MainActivity.kt` — UI + VPN permission
- `app/src/main/java/com/aipet/dnschanger/DnsVpnService.kt` — DNS-only forwarder
- `app/src/main/AndroidManifest.xml` — BIND_VPN_SERVICE
