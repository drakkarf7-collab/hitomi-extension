package eu.kanade.tachiyomi.extension.all.hitomi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * DNS over HTTPS resolver (Cloudflare, then Google, then the system DNS).
 *
 * Some ISPs answer DNS queries for blocked domains with the address of their own block page,
 * which shows up as a TLS / certificate error in the app. Asking a public DoH resolver instead
 * returns the real address. Only the lookup changes: the connection itself is still direct HTTPS.
 *
 * Uses the JSON API on IP literals, so resolving the resolver needs no DNS at all.
 */
class DohDns(baseClient: OkHttpClient) : Dns {

    private val client = baseClient.newBuilder()
        .dns(Dns.SYSTEM)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val cache = HashMap<String, Pair<Long, List<InetAddress>>>()

    override fun lookup(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[hostname]?.takeIf { it.first > now }?.let { return it.second }
        }
        for (resolver in RESOLVERS) {
            val result = runCatching { query(resolver, hostname) }.getOrNull()
            if (!result.isNullOrEmpty()) {
                synchronized(cache) { cache[hostname] = (now + CACHE_MS) to result }
                return result
            }
        }
        return Dns.SYSTEM.lookup(hostname)
    }

    private fun query(resolver: String, hostname: String): List<InetAddress> {
        val request = Request.Builder()
            .url("$resolver?name=$hostname&type=A")
            .header("Accept", "application/dns-json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw UnknownHostException("DoH HTTP ${response.code}")
            val json = Json.parseToJsonElement(response.body.string()).jsonObject
            return json.arr("Answer")
                .mapNotNull { it.jsonObjectOrNull() }
                .filter { it.str("type") == "1" } // A records (skips CNAMEs)
                .mapNotNull { it.str("data") }
                .map { InetAddress.getByAddress(hostname, ipv4Bytes(it)) }
        }
    }

    private fun ipv4Bytes(ip: String): ByteArray {
        val parts = ip.split('.')
        require(parts.size == 4) { "Not an IPv4 address: $ip" }
        return ByteArray(4) { parts[it].toInt().toByte() }
    }

    companion object {
        private val RESOLVERS = listOf(
            "https://1.1.1.1/dns-query", // Cloudflare
            "https://8.8.8.8/resolve", // Google
            "https://1.0.0.1/dns-query", // Cloudflare (secondary)
        )
        private const val CACHE_MS = 5 * 60 * 1000L
    }
}
