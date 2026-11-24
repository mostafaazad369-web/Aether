package studio.cluvex.aether.custom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.net.URL
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The three ways a config gets into the app - link, file, QR code - all ending
 * in [ConfigParser]. Everything that needs Android (assets, content URIs,
 * bitmaps, the network) lives here; the parsing itself does not.
 */
object ConfigImporter {

    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val TIMEOUT_MS = 20_000
    private const val MAX_REDIRECTS = 5
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    @Volatile
    private var cachedTables: NpvtCodec.Tables? = null

    private val gen2Lock = Any()
    @Volatile private var cachedGen2: NpvtCodec.Tables? = null
    fun gen2Tables(context: Context): NpvtCodec.Tables = cachedGen2 ?: synchronized(gen2Lock) {
        cachedGen2 ?: context.assets.open("npvs/gen2-wb.bin").use {
            NpvtCodec.Tables.parse(it.readBytes())
        }.also { cachedGen2 = it }
    }

    /** The NapsternetV white-box tables, read once from `assets/npvt/wb.bin`. */
    fun tables(context: Context): NpvtCodec.Tables =
        cachedTables ?: synchronized(this) {
            cachedTables ?: context.assets.open("npvt/wb.bin").use { NpvtCodec.Tables.parse(it.readBytes()) }
                .also { cachedTables = it }
        }

    /**
     * Pasted or scanned text. An `https://` address is a subscription or a hosted
     * config file and is downloaded first; everything else is parsed as it is.
     */
    suspend fun fromText(
        context: Context,
        text: String,
        source: ConfigSource,
        password: String? = null,
    ): List<CustomConfig> = withContext(Dispatchers.IO) {
        val t = ConfigParser.cleanInput(text)
        if (ConfigParser.looksLikeMtProto(t)) throw ConfigImportException(ConfigParser.MTPROTO_UNSUPPORTED)
        if (t.startsWith("http://", ignoreCase = true)) {
            throw ConfigImportException("subscription links must use HTTPS to protect server credentials")
        }
        val body = if (t.startsWith("https://", ignoreCase = true)) {
            if (t.lines().size > 1) t else return@withContext ConfigParser.parseBytes(
                download(t), source, { tables(context) }, { gen2Tables(context) }, password)
        } else {
            t
        }
        ConfigParser.parse(body, source, { tables(context) }, password)
    }

    /** A file picked with the system file picker: a text/JSON/npvt config, or a QR code image. */
    suspend fun fromFile(context: Context, uri: Uri, password: String? = null): List<CustomConfig> =
        withContext(Dispatchers.IO) {
            val mime = context.contentResolver.getType(uri).orEmpty()
            if (mime.startsWith("image/")) return@withContext fromImage(context, uri, password)
            val bytes = context.contentResolver.openInputStream(uri)?.use { readLimited(it) }
                ?: throw ConfigImportException("the file could not be opened")
            // An image with a generic MIME type (some file managers): try it as a QR code.
            if (isImage(bytes)) {
                val text = decodeQr(bytes) ?: throw ConfigImportException("no QR code found in the image")
                return@withContext ConfigParser.parse(text, ConfigSource.QR, { tables(context) }, password)
            }
            ConfigParser.parseBytes(bytes, ConfigSource.FILE, { tables(context) }, { gen2Tables(context) }, password)
        }

    /** An image holding a QR code (a screenshot, a photo of a screen, a saved picture). */
    suspend fun fromImage(context: Context, uri: Uri, password: String? = null): List<CustomConfig> =
        withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { readLimited(it, 16 * MAX_BYTES) }
                ?: throw ConfigImportException("the image could not be opened")
            val text = decodeQr(bytes) ?: throw ConfigImportException("no QR code found in the image")
            fromText(context, text, ConfigSource.QR, password)
        }

    /** Re-parses an edited config, keeping its identity, name and source. */
    suspend fun reparse(context: Context, old: CustomConfig, newRaw: String): CustomConfig =
        withContext(Dispatchers.IO) {
            val parsed = ConfigParser.parse(newRaw, old.source, { tables(context) }).first()
            parsed.copy(id = old.id, name = old.name, addedAt = old.addedAt,
                groupId = old.groupId, subscriptionId = old.subscriptionId)
        }

    // ------------------------------------------------------------- helpers

    /** Shared URL policy for the initial subscription URL and every redirect hop. */
    internal fun validateSubscriptionUrl(url: URL) {
        if (!url.protocol.equals("https", ignoreCase = true) || url.userInfo != null) {
            throw ConfigImportException("subscription links and redirects must use HTTPS without embedded credentials")
        }
    }

    /** Enforces the same redirect budget used by the live downloader. */
    internal fun validateSubscriptionRedirect(url: URL, redirectsFollowed: Int) {
        if (redirectsFollowed > MAX_REDIRECTS) {
            throw ConfigImportException("subscription address redirected too many times")
        }
        validateSubscriptionUrl(url)
    }

    /**
     * Direct subscription requests must not reach local, private, reserved, or
     * special-purpose destinations. OkHttp's Dns contract pins the returned
     * addresses into its connection routes, avoiding a second DNS lookup between
     * validation and connect (the classic DNS-rebinding gap).
     *
     * SOCKS requests deliberately do not use this resolver: OkHttp sends the
     * unresolved hostname to the local SOCKS proxy, which resolves it through
     * the tunnel rather than on the handset.
     */
    internal fun validateDirectDnsResults(addresses: List<InetAddress>): List<InetAddress> {
        if (addresses.isEmpty() || addresses.any { !isPublicInternetAddress(it) }) {
            throw ConfigImportException("subscription host must resolve only to public internet addresses")
        }
        return addresses
    }

    internal fun isPublicInternetAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) return false

        val bytes = address.address.map { it.toInt() and 0xff }
        if (address is Inet4Address) return isPublicIpv4(bytes)
        if (address !is Inet6Address) return false

        // IPv4-mapped IPv6 and the well-known NAT64 prefix carry an IPv4 target.
        if (bytes.take(10).all { it == 0 } && bytes[10] == 0xff && bytes[11] == 0xff) {
            return isPublicIpv4(bytes.drop(12))
        }
        if (bytes[0] == 0x00 && bytes[1] == 0x64 && bytes[2] == 0xff &&
            bytes[3] == 0x9b && bytes.subList(4, 12).all { it == 0 }
        ) return isPublicIpv4(bytes.drop(12))

        // Permit global-unicast 2000::/3 only. This excludes ULA, link-local,
        // multicast, discard-only, and other non-global IPv6 address spaces.
        if ((bytes[0] and 0xe0) != 0x20) return false
        // 2001::/23 is reserved for IETF protocol assignments. Its only
        // globally-reachable exceptions are the three exact anycast /128 hosts.
        if (bytes[0] == 0x20 && bytes[1] == 0x01 &&
            bytes[2] == 0x00 && (bytes[3] and 0xfe) == 0x00
        ) {
            val isRegisteredGlobalAnycast =
                bytes[3] == 0x01 &&
                    bytes.subList(4, 15).all { it == 0 } &&
                    bytes[15] in 1..3
            if (!isRegisteredGlobalAnycast) return false
        }
        // Deprecated ORCHID (2001:10::/28), benchmarking (2001:2::/48),
        // documentation, and 6to4 are not public subscription destinations.
        if (bytes[0] == 0x20 && bytes[1] == 0x01 &&
            bytes[2] == 0x00 && (bytes[3] and 0xf0) == 0x10
        ) return false
        if (bytes[0] == 0x20 && bytes[1] == 0x01 &&
            bytes[2] == 0x00 && bytes[3] == 0x02 &&
            bytes[4] == 0x00 && bytes[5] == 0x00
        ) return false
        if (bytes[0] == 0x20 && bytes[1] == 0x01 &&
            bytes[2] == 0x0d && bytes[3] == 0xb8
        ) return false
        if (bytes[0] == 0x20 && bytes[1] == 0x02) return false
        // The 3fff::/20 documentation block is inside 2000::/3.
        if (bytes[0] == 0x3f && bytes[1] == 0xff && (bytes[2] and 0xf0) == 0x00) return false
        // Segment Routing SIDs (5f00::/16) are not globally reachable.
        if (bytes[0] == 0x5f && bytes[1] == 0x00) return false
        return true
    }

    private fun isPublicIpv4(bytes: List<Int>): Boolean {
        if (bytes.size != 4) return false
        val value = bytes.fold(0L) { acc, byte -> (acc shl 8) or byte.toLong() }
        fun inRange(network: Long, prefix: Int): Boolean {
            val mask = (0xffff_ffffL shl (32 - prefix)) and 0xffff_ffffL
            return (value and mask) == (network and mask)
        }
        val blocked = listOf(
            0x00000000L to 8,   // "this" network
            0x0a000000L to 8,   // private
            0x64400000L to 10,  // shared address space / CGNAT
            0x7f000000L to 8,   // loopback
            0xa9fe0000L to 16,  // link-local
            0xac100000L to 12,  // private
            0xc0000000L to 24,  // IETF protocol assignments
            0xc0000200L to 24,  // documentation
            0xc0586300L to 24,  // deprecated 6to4 relay anycast
            0xc0a80000L to 16,  // private
            0xc6120000L to 15,  // benchmarking
            0xc6336400L to 24,  // documentation
            0xcb007100L to 24,  // documentation
            0xe0000000L to 4,   // multicast and reserved
            0xf0000000L to 4,   // reserved for future use and limited broadcast
        )
        return blocked.none { (network, prefix) -> inRange(network, prefix) }
    }

    private fun download(url: String): ByteArray {
        // Subscription URLs can contain credentials. Never send them over cleartext,
        // and never let an HTTPS endpoint downgrade the request through a redirect.
        var target = runCatching { URL(url) }.getOrElse {
            throw ConfigImportException("invalid subscription address")
        }
        val port = studio.cluvex.aether.vpn.AetherVpnService.activeForwardPort
        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)

        if (port in 1..65535) {
            clientBuilder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
        } else {
            clientBuilder.dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    try {
                        validateDirectDnsResults(Dns.SYSTEM.lookup(hostname))
                    } catch (e: ConfigImportException) {
                        throw UnknownHostException(e.message)
                    }
            })
        }

        val client = clientBuilder.build()
        for (redirect in 0..MAX_REDIRECTS) {
            validateSubscriptionRedirect(target, redirect)
            val request = Request.Builder()
                .url(target)
                .header("User-Agent", "Aether")
                .header("Accept", "*/*")
                .get()
                .build()
            val response = try {
                client.newCall(request).execute()
            } catch (e: Exception) {
                throw ConfigImportException("the address could not be downloaded (${e.javaClass.simpleName})")
            }

            try {
                if (response.code in REDIRECT_CODES) {
                    val location = response.header("Location")
                        ?: throw ConfigImportException("subscription redirect has no destination")
                    val nextTarget = runCatching { URL(target, location) }.getOrElse {
                        throw ConfigImportException("subscription redirect address is invalid")
                    }
                    validateSubscriptionRedirect(nextTarget, redirect + 1)
                    target = nextTarget
                    continue
                }
                if (response.code !in 200..299) {
                    throw ConfigImportException("the address answered HTTP ${response.code}")
                }
                val body = response.body
                    ?: throw ConfigImportException("the address returned an empty response")
                if (body.contentLength() > MAX_BYTES) {
                    throw ConfigImportException("the file is too large")
                }
                return body.byteStream().use { readLimited(it) }
            } finally {
                response.close()
            }
        }
        throw ConfigImportException("subscription address could not be downloaded")
    }

    private fun readLimited(input: InputStream, limit: Int = MAX_BYTES): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw ConfigImportException("the file is too large")
        }
        return out.toByteArray()
    }

    private fun isImage(b: ByteArray): Boolean =
        b.size > 8 && (
            (b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()) || // PNG
                (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) || // JPEG
                (b[0] == 'R'.code.toByte() && b[8] == 'W'.code.toByte()) || // WEBP
                (b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte()) // GIF
            )

    /** Decodes the first QR code in an encoded image, or null. */
    fun decodeQr(bytes: ByteArray): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return try {
            decodeQr(bmp)
        } finally {
            bmp.recycle()
        }
    }

    fun decodeQr(bmp: Bitmap): String? {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val source = RGBLuminanceSource(w, h, pixels)
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.CHARACTER_SET to "UTF-8",
        )
        val attempts = listOf(
            { BinaryBitmap(HybridBinarizer(source)) },
            { BinaryBitmap(GlobalHistogramBinarizer(source)) },
            { BinaryBitmap(HybridBinarizer(source.invert())) },
        )
        for (bitmap in attempts) {
            val reader = MultiFormatReader()
            val text = runCatching { reader.decode(bitmap(), hints).text }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }
}