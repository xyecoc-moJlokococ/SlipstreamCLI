package app.vaydns

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import app.slipnet.tunnel.ResolverListConfig
import app.slipnet.tunnel.SlipstreamBridge
import app.slipnet.util.AppLog
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import org.json.JSONArray
import org.json.JSONObject

data class ResolverChoice(
    val hosts: List<String>,
    val port: Int,
    val selectedHost: String,
    val source: String,
    val qnameMtu: Int = 0,
    val testedCount: Int = hosts.size,
    val aliveCount: Int = hosts.size,
    val skippedCount: Int = 0,
    val latencyMs: Long = -1
)

object ResolverSelector {
    private const val TAG = "ResolverSelector"
    private const val CACHE_PREFS = "resolver_cache_v1"
    private const val CONNECT_TIMEOUT_MS = 1200
    private const val CAPTIVE_CHECK_TIMEOUT_MS = 1500
    private const val CACHE_TTL_MS = 14L * 24L * 60L * 60L * 1000L
    private const val CACHE_FULL_REFRESH_MS = 6L * 60L * 60L * 1000L
    private const val PROBE_THREADS = 8
    private const val SPEED_PROBE_BYTES = 5_000
    private const val SPEED_PROBE_MIN_BYTES = 512
    private const val SPEED_PROBE_BATCH_SIZE = 5
    private const val SPEED_PROBE_READY_TIMEOUT_MS = 5000
    private const val SPEED_PROBE_SOCKET_TIMEOUT_MS = 5000
    private const val SPEED_PROBE_HOST = "speed.cloudflare.com"
    private const val SPEED_PROBE_PATH = "/__down?bytes=$SPEED_PROBE_BYTES"
    private val QNAME_MTU_PROBE_ORDER = intArrayOf(0)

    @Volatile var lastProgress: Progress = Progress()

    data class Progress(
        val active: Boolean = false,
        val reason: String = "",
        val phase: String = "",
        val currentHost: String = "",
        val tested: Int = 0,
        val total: Int = 0,
        val alive: Int = 0,
        val speedTested: Int = 0,
        val speedTotal: Int = 0,
        val speedOk: Int = 0,
        val selected: String = ""
    )

    private val operatorResolvers = listOf(
        "176.59.159.161", "176.59.159.157",
        "212.188.4.10", "195.34.32.116", "213.87.0.1", "213.87.1.1",
        "213.87.142.95", "213.87.142.85", "213.87.142.94", "213.87.142.84",
        "213.87.74.21", "213.87.74.5", "213.87.211.20", "213.87.210.20",
        "10.10.22.3", "194.67.2.114", "194.67.1.154",
        "85.249.22.248", "85.249.22.249", "85.249.22.251", "85.249.22.250",
        "176.59.62.125", "176.59.62.126", "176.59.31.182", "176.59.31.183",
        "176.59.223.159", "176.59.95.243", "176.59.63.148", "176.59.63.204",
        "176.59.127.156",
        "80.245.112.23", "195.208.4.1", "194.147.49.16",
        "84.201.166.221", "84.201.166.139", "84.201.166.50", "84.201.166.116",
        "83.169.217.22", "10.112.248.238", "10.112.250.2", "10.112.248.226",
        "10.148.25.144", "10.205.171.77", "10.205.171.69",
        "10.93.233.220", "10.93.233.252"
    )

    private val unshapedResolvers = listOf(
        "185.22.235.137",
        "82.151.127.188",
        "188.0.190.47",
        "46.254.19.23"
    )

    private val blockedAutoResolvers = setOf(
        "1.1.1.1",
        "1.0.0.1",
        "8.8.8.8",
        "8.8.4.4"
    )

    private data class DefaultNetworkResolvers(
        val resolvers: List<String>,
        val isCellular: Boolean,
        val isWifi: Boolean
    )

    private data class AutoProbe(
        val candidates: List<String>,
        val alive: List<String>,
        val local: List<String>,
        val defaultNetwork: DefaultNetworkResolvers
    )

    private data class NetworkProfile(
        val key: String,
        val label: String,
        val whitelistEnabled: Boolean
    )

    private data class CachedResolver(
        val host: String,
        val totalMs: Long,
        val qnameMtu: Int,
        val lastOkAt: Long
    )

    private data class ResolverCacheEntry(
        val profile: NetworkProfile,
        val resolvers: List<CachedResolver>,
        val updatedAt: Long
    ) {
        val isFresh: Boolean
            get() = System.currentTimeMillis() - updatedAt <= CACHE_TTL_MS

        val needsFullRefresh: Boolean
            get() = System.currentTimeMillis() - updatedAt > CACHE_FULL_REFRESH_MS
    }

    fun localMobileResolvers(context: Context): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        return cm.allNetworks
            .filter { network ->
                val caps = cm.getNetworkCapabilities(network)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            }
            .flatMap { network ->
                cm.getLinkProperties(network)?.dnsServers.orEmpty().mapNotNull { it.hostAddress }
            }
            .mapNotNull { normalizeDnsHost(it) }
            .distinct()
    }

    fun localDefaultResolvers(context: Context): List<String> =
        defaultNetworkResolvers(context).resolvers

    fun preferredLocalResolver(context: Context): String? =
        localDefaultResolvers(context).firstOrNull()

    private fun defaultNetworkResolvers(context: Context): DefaultNetworkResolvers {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return DefaultNetworkResolvers(emptyList(), isCellular = false, isWifi = false)
        val network = cm.activeNetwork
            ?: return DefaultNetworkResolvers(emptyList(), isCellular = false, isWifi = false)
        val caps = cm.getNetworkCapabilities(network)
        val dns = cm.getLinkProperties(network)?.dnsServers.orEmpty()
            .mapNotNull { it.hostAddress }
            .mapNotNull { normalizeDnsHost(it) }
            .distinct()
        return DefaultNetworkResolvers(
            resolvers = dns,
            isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
            isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        )
    }

    private fun normalizeDnsHost(host: String): String? {
        val value = host.substringBefore('%').trim()
        return value.takeIf { it.isNotBlank() && !it.startsWith("fe80:", ignoreCase = true) }
    }

    fun choose(context: Context, config: Config, reason: String, skipHosts: Set<String> = emptySet()): ResolverChoice {
        val port = config.resolverPort
        if (config.resolverMode == Config.ResolverMode.MANUAL) {
            val host = config.resolverHost.trim()
            AppLog.i(TAG, "manual resolver selected host=$host:$port reason=$reason")
            lastProgress = Progress(active = false, reason = reason, phase = "done", tested = 1, total = 1, alive = 1, selected = host)
            return ResolverChoice(listOf(host), port, host, "manual", qnameMtu = 0, testedCount = 1, aliveCount = 1)
        }

        val probe = autoProbe(context, port, reason)
        val candidates = probe.candidates
        val alive = probe.alive

        val speedCandidates = (if (alive.isNotEmpty()) alive else candidates)
            .filter { it !in skipHosts }
        if (alive.isEmpty()) {
            AppLog.w(TAG, "auto resolver tcp probe found no alive hosts; trying direct slipstream speed probe candidates=${speedCandidates.take(SPEED_PROBE_BATCH_SIZE).joinToString()} reason=$reason")
        }
        val speedResults = if (speedCandidates.isNotEmpty()) {
            speedProbeAlive(
                domain = config.domain,
                alive = speedCandidates,
                port = port,
                reason = reason,
                preferred = emptyList(),
                username = if (config.authMode == Config.AuthMode.LOGIN_PASSWORD) config.username else null,
                password = if (config.authMode == Config.AuthMode.LOGIN_PASSWORD) config.password else null
            )
        } else {
            emptyList()
        }
        val speedOk = speedResults.filter { it.ok }
        val speedEligible = speedOk.filter { it.host !in skipHosts }
        val speedBest = speedEligible.minByOrNull { it.totalMs }
        val fallback = speedCandidates.firstOrNull { it !in skipHosts }
            ?: speedCandidates.firstOrNull()
            ?: candidates.firstOrNull { it !in skipHosts }
            ?: candidates.firstOrNull()

        val selected = when {
            speedBest != null -> speedBest.host
            fallback != null -> fallback
            alive.any { it !in skipHosts } -> alive.first { it !in skipHosts }
            alive.isNotEmpty() -> alive.first()
            candidates.any { it !in skipHosts } -> candidates.first { it !in skipHosts }
            candidates.isNotEmpty() -> candidates.first()
            else -> ""
        }
        val hosts = speedBest?.hosts ?: when {
            selected.isNotBlank() -> listOf(selected)
            else -> emptyList()
        }
        AppLog.i(
            TAG,
            "auto resolver summary reason=$reason tested=${candidates.size} alive=${alive.size} " +
                "failed=${candidates.size - alive.size} skipped=${skipHosts.size} selected=$selected:$port " +
                "local=${probe.local.joinToString()} aliveList=${alive.joinToString()} " +
                "speedTested=${speedResults.size} speedOk=${speedOk.size} speedBest=${speedBest?.host}:${speedBest?.totalMs}ms " +
                "fallback=${fallback ?: ""}"
        )
        lastProgress = Progress(
            active = false,
            reason = reason,
            phase = "done",
            tested = candidates.size,
            total = candidates.size,
            alive = alive.size,
            speedTested = speedResults.size,
            speedTotal = speedResults.size,
            speedOk = speedOk.size,
            selected = selected
        )
        if (speedResults.isNotEmpty() && speedOk.isEmpty() && selected.isNotBlank()) {
            error("no auto DNS passed slipstream speed probe: tested=${speedResults.size} ok=0")
        }
        if (selected.isBlank()) {
            error(
                if (speedResults.isNotEmpty()) {
                    "no auto DNS passed slipstream speed probe: tested=${speedResults.size} ok=${speedOk.size}"
                } else {
                    "no auto DNS passed tcp probe: tested=${candidates.size} alive=${alive.size}"
                }
            )
        }
        return ResolverChoice(
            hosts = hosts,
            port = port,
            selectedHost = selected,
            source = "auto",
            qnameMtu = speedBest?.qnameMtu ?: 0,
            testedCount = candidates.size,
            aliveCount = alive.size,
            skippedCount = skipHosts.size,
            latencyMs = speedBest?.totalMs ?: -1
        )
    }

    private fun activeNonVpnNetwork(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val active = cm.activeNetwork
        val activeCaps = active?.let { cm.getNetworkCapabilities(it) }
        if (active != null && activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) {
            return active
        }
        return cm.allNetworks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true
        }
    }

    private fun currentNetworkProfile(context: Context, config: Config): NetworkProfile {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = activeNonVpnNetwork(context)
        val caps = network?.let { cm?.getNetworkCapabilities(it) }
        val link = network?.let { cm?.getLinkProperties(it) }
        val dns = link?.dnsServers.orEmpty()
            .mapNotNull { it.hostAddress }
            .mapNotNull { normalizeDnsHost(it) }
            .distinct()
        val transport = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
            else -> "other"
        }
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val wifiInfo = runCatching { wifi?.connectionInfo }.getOrNull()
        val wifiSsid = wifiInfo?.ssid
            ?.trim('"')
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            .orEmpty()
        val wifiBssid = wifiInfo?.bssid
            ?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" }
            .orEmpty()
        val operator = listOfNotNull(
            runCatching { telephony?.networkOperator }.getOrNull(),
            runCatching { telephony?.simOperator }.getOrNull(),
            runCatching { telephony?.networkCountryIso }.getOrNull()
        ).filter { it.isNotBlank() }
            .joinToString("/")
        val whitelistEnabled = isWhitelistLikelyEnabled(context, network)
        val raw = buildString {
            append("domain=").append(config.domain.trim().lowercase()).append('|')
            append("port=").append(config.resolverPort).append('|')
            append("transport=").append(transport).append('|')
            append("iface=").append(link?.interfaceName.orEmpty()).append('|')
            append("dns=").append(dns.joinToString(",")).append('|')
            append("operator=").append(operator).append('|')
            append("ssid=").append(wifiSsid).append('|')
            append("bssid=").append(wifiBssid).append('|')
            append("whitelist=").append(if (whitelistEnabled) "on" else "off")
        }
        val label = buildString {
            append(transport)
            if (operator.isNotBlank()) append("/").append(operator)
            if (wifiSsid.isNotBlank()) append("/").append(wifiSsid)
            append(" dns=").append(dns.joinToString(",").ifBlank { "-" })
            append(" whitelist=").append(if (whitelistEnabled) "on" else "off")
        }
        return NetworkProfile(sha256(raw), label, whitelistEnabled)
    }

    private fun isWhitelistLikelyEnabled(context: Context, network: Network?): Boolean {
        return runCatching {
            val target = network ?: activeNonVpnNetwork(context)
            val url = URL("http://gstatic.com/generate_204")
            val connection = (target?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CAPTIVE_CHECK_TIMEOUT_MS
            connection.readTimeout = CAPTIVE_CHECK_TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "SlipstreamCLI-NetworkProbe")
            try {
                val code = connection.responseCode
                code != 204
            } finally {
                connection.disconnect()
            }
        }.getOrElse {
            AppLog.w(TAG, "network whitelist probe failed; assuming whitelist enabled: ${it.message ?: it::class.java.simpleName}")
            true
        }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun loadResolverCache(context: Context, config: Config): ResolverCacheEntry? {
        val profile = currentNetworkProfile(context, config)
        val raw = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            .getString(profile.key, null)
            ?: run {
                AppLog.i(TAG, "resolver cache miss network=${profile.label}")
                return null
            }
        return runCatching {
            val json = JSONObject(raw)
            val updatedAt = json.optLong("updatedAt", 0L)
            val hosts = json.optJSONArray("resolvers") ?: JSONArray()
            val resolvers = buildList {
                for (i in 0 until hosts.length()) {
                    val item = hosts.optJSONObject(i) ?: continue
                    val host = item.optString("host").trim()
                    if (host.isBlank()) continue
                    add(
                        CachedResolver(
                            host = host,
                            totalMs = item.optLong("totalMs", Long.MAX_VALUE),
                            qnameMtu = item.optInt("qnameMtu", 0),
                            lastOkAt = item.optLong("lastOkAt", updatedAt)
                        )
                    )
                }
            }.distinctBy { it.host }
                .sortedBy { it.totalMs }
            ResolverCacheEntry(profile, resolvers, updatedAt)
        }.onFailure {
            AppLog.w(TAG, "resolver cache parse failed network=${profile.label}: ${it.message}")
        }.getOrNull()
            ?.takeIf { it.isFresh && it.resolvers.isNotEmpty() }
            ?.also {
                AppLog.i(
                    TAG,
                    "resolver cache hit network=${it.profile.label} hosts=${it.resolvers.joinToString { r -> "${r.host}:${r.totalMs}ms" }} " +
                        "ageMs=${System.currentTimeMillis() - it.updatedAt} refresh=${it.needsFullRefresh}"
                )
            }
    }

    private fun saveResolverCache(context: Context, config: Config, results: List<SpeedProbeResult>) {
        val ok = results.filter { it.ok && it.host.isNotBlank() }
            .sortedBy { it.totalMs }
            .distinctBy { it.host }
        if (ok.isEmpty()) return
        val profile = currentNetworkProfile(context, config)
        val now = System.currentTimeMillis()
        val arr = JSONArray()
        ok.forEach { result ->
            arr.put(
                JSONObject()
                    .put("host", result.host)
                    .put("totalMs", result.totalMs)
                    .put("qnameMtu", result.qnameMtu)
                    .put("lastOkAt", now)
            )
        }
        val json = JSONObject()
            .put("updatedAt", now)
            .put("network", profile.label)
            .put("whitelistEnabled", profile.whitelistEnabled)
            .put("resolvers", arr)
        context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE).edit()
            .putString(profile.key, json.toString())
            .apply()
        AppLog.i(
            TAG,
            "resolver cache saved network=${profile.label} hosts=${ok.joinToString { "${it.host}:${it.totalMs}ms" }}"
        )
    }

    fun chooseFast(context: Context, config: Config, reason: String, skipHosts: Set<String> = emptySet()): ResolverChoice {
        val port = config.resolverPort
        if (config.resolverMode == Config.ResolverMode.MANUAL) {
            val host = config.resolverHost.trim()
            AppLog.i(TAG, "manual resolver selected host=$host:$port reason=$reason")
            lastProgress = Progress(active = false, reason = reason, phase = "done", tested = 1, total = 1, alive = 1, selected = host)
            return ResolverChoice(listOf(host), port, host, "manual", qnameMtu = 0, testedCount = 1, aliveCount = 1)
        }
        val cached = loadResolverCache(context, config)
        if (cached != null) {
            val cachedHosts = cached.resolvers.map { it.host }
                .filter { it !in skipHosts }
                .distinct()
            val aliveCached = probeParallel(cachedHosts, port, "$reason:cache")
            val selected = aliveCached.firstOrNull()
            if (selected != null) {
                val cachedResolver = cached.resolvers.firstOrNull { it.host == selected }
                AppLog.i(
                    TAG,
                    "auto resolver cache selected reason=$reason selected=$selected:$port " +
                        "network=${cached.profile.label} cached=${cachedHosts.joinToString()} alive=${aliveCached.joinToString()}"
                )
                lastProgress = Progress(
                    active = false,
                    reason = reason,
                    phase = "done",
                    tested = cachedHosts.size,
                    total = cachedHosts.size,
                    alive = aliveCached.size,
                    selected = selected
                )
                return ResolverChoice(
                    hosts = listOf(selected),
                    port = port,
                    selectedHost = selected,
                    source = "auto-cache",
                    qnameMtu = cachedResolver?.qnameMtu ?: 0,
                    testedCount = cachedHosts.size,
                    aliveCount = aliveCached.size,
                    skippedCount = skipHosts.size,
                    latencyMs = cachedResolver?.totalMs ?: -1
                )
            }
            AppLog.w(
                TAG,
                "auto resolver cache unusable reason=$reason network=${cached.profile.label} " +
                    "cached=${cachedHosts.joinToString()} skipped=${skipHosts.joinToString()}"
            )
        }
        val probe = autoProbe(context, port, reason)
        val selected = probe.alive.firstOrNull { it !in skipHosts }
            ?: probe.alive.firstOrNull()
            ?: probe.candidates.firstOrNull { it !in skipHosts }
            ?: probe.candidates.firstOrNull()
            ?: ""
        if (selected.isBlank()) {
            error("no auto DNS passed tcp probe: tested=${probe.candidates.size} alive=${probe.alive.size}")
        }
        AppLog.i(
            TAG,
            "auto resolver fast summary reason=$reason tested=${probe.candidates.size} alive=${probe.alive.size} " +
                "failed=${probe.candidates.size - probe.alive.size} skipped=${skipHosts.size} selected=$selected:$port " +
                "local=${probe.local.joinToString()} aliveList=${probe.alive.joinToString()}"
        )
        lastProgress = Progress(
            active = false,
            reason = reason,
            phase = "done",
            tested = probe.candidates.size,
            total = probe.candidates.size,
            alive = probe.alive.size,
            selected = selected
        )
        return ResolverChoice(
            hosts = listOf(selected),
            port = port,
            selectedHost = selected,
            source = "auto-fast",
            qnameMtu = 0,
            testedCount = probe.candidates.size,
            aliveCount = probe.alive.size,
            skippedCount = skipHosts.size
        )
    }

    fun chooseFastestByDownload(context: Context, config: Config, reason: String, skipHosts: Set<String> = emptySet()): ResolverChoice? {
        if (config.resolverMode != Config.ResolverMode.AUTO) return null
        val port = config.resolverPort
        val cached = loadResolverCache(context, config)
        if (cached != null && !cached.needsFullRefresh) {
            val cachedCandidates = cached.resolvers.map { it.host }
                .filter { it !in skipHosts }
                .distinct()
            if (cachedCandidates.isNotEmpty()) {
                val speed = speedProbeAlive(
                    domain = config.domain,
                    alive = cachedCandidates,
                    port = port,
                    reason = "$reason:cache",
                    preferred = emptyList(),
                    username = if (config.authMode == Config.AuthMode.LOGIN_PASSWORD) config.username else null,
                    password = if (config.authMode == Config.AuthMode.LOGIN_PASSWORD) config.password else null,
                    batchSize = SPEED_PROBE_BATCH_SIZE,
                    stopAfterFirstOk = false
                )
                val best = speed.filter { it.ok }.minByOrNull { it.totalMs }
                if (best != null) {
                    saveResolverCache(context, config, speed)
                    AppLog.i(
                        TAG,
                        "background resolver cached download summary reason=$reason network=${cached.profile.label} " +
                            "speedTested=${speed.size} selected=${best.host}:$port totalMs=${best.totalMs} bytes=${best.bytes}"
                    )
                    lastProgress = Progress(
                        active = false,
                        reason = reason,
                        phase = "done",
                        tested = cachedCandidates.size,
                        total = cachedCandidates.size,
                        alive = cachedCandidates.size,
                        speedTested = speed.size,
                        speedTotal = cachedCandidates.size,
                        speedOk = speed.count { it.ok },
                        selected = best.host
                    )
                    return ResolverChoice(
                        hosts = listOf(best.host),
                        port = port,
                        selectedHost = best.host,
                        source = "auto-speed-cache",
                        qnameMtu = best.qnameMtu,
                        testedCount = cachedCandidates.size,
                        aliveCount = cachedCandidates.size,
                        skippedCount = skipHosts.size,
                        latencyMs = best.totalMs
                    )
                }
                AppLog.w(TAG, "resolver cache speed validation failed reason=$reason network=${cached.profile.label}; falling back to full probe")
            }
        }
        val probe = autoProbe(context, port, reason)
        val speedCandidates = (if (probe.alive.isNotEmpty()) probe.alive else probe.candidates)
            .filter { it !in skipHosts }
        if (speedCandidates.isEmpty()) return null
        val speed = speedProbeAlive(
            domain = config.domain,
            alive = speedCandidates,
            port = port,
            reason = reason,
            preferred = emptyList(),
            username = if (config.authMode == Config.AuthMode.LOGIN_PASSWORD) config.username else null,
            password = if (config.authMode == Config.AuthMode.LOGIN_PASSWORD) config.password else null,
            batchSize = SPEED_PROBE_BATCH_SIZE,
            stopAfterFirstOk = false
        )
        saveResolverCache(context, config, speed)
        val best = speed.filter { it.ok }.minByOrNull { it.totalMs } ?: return null
        AppLog.i(
            TAG,
            "background resolver download summary reason=$reason tested=${probe.candidates.size} alive=${probe.alive.size} " +
                "speedTested=${speed.size} selected=${best.host}:$port totalMs=${best.totalMs} bytes=${best.bytes}"
        )
        lastProgress = Progress(
            active = false,
            reason = reason,
            phase = "done",
            tested = probe.candidates.size,
            total = probe.candidates.size,
            alive = probe.alive.size,
            speedTested = speed.size,
            speedTotal = speedCandidates.size,
            speedOk = speed.count { it.ok },
            selected = best.host
        )
        return ResolverChoice(
            hosts = listOf(best.host),
            port = port,
            selectedHost = best.host,
            source = "auto-speed",
            qnameMtu = best.qnameMtu,
            testedCount = probe.candidates.size,
            aliveCount = probe.alive.size,
            skippedCount = skipHosts.size,
            latencyMs = best.totalMs
        )
    }

    private fun autoProbe(context: Context, port: Int, reason: String): AutoProbe {
        val defaultNetwork = defaultNetworkResolvers(context)
        val local = defaultNetwork.resolvers
        val candidates = buildList {
            addAll(unshapedResolvers)
            addAll(operatorResolvers)
            addAll(local)
        }.map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { it !in blockedAutoResolvers }
            .distinct()

        AppLog.i(
            TAG,
            "auto resolver probe start reason=$reason port=$port defaultDns=${defaultNetwork.resolvers.joinToString()} " +
                "netCellular=${defaultNetwork.isCellular} netWifi=${defaultNetwork.isWifi} local=${local.joinToString()} total=${candidates.size}"
        )
        lastProgress = Progress(active = true, reason = reason, phase = "tcp", total = candidates.size)

        return AutoProbe(
            candidates = candidates,
            alive = probeParallel(candidates, port, reason),
            local = local,
            defaultNetwork = defaultNetwork
        )
    }

    fun isResolverReachable(host: String, port: Int): Boolean =
        tcpConnect(host, port)

    private fun tcpConnect(host: String, port: Int): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            }
            true
        }.getOrDefault(false)

    private fun probeParallel(candidates: List<String>, port: Int, reason: String): List<String> {
        if (candidates.isEmpty()) return emptyList()
        val executor = Executors.newFixedThreadPool(PROBE_THREADS.coerceAtMost(candidates.size))
        val completion = ExecutorCompletionService<Pair<String, Boolean>>(executor)
        val tested = AtomicInteger(0)
        val aliveCount = AtomicInteger(0)
        return try {
            candidates.forEach { host ->
                completion.submit(Callable {
                    lastProgress = lastProgress.copy(active = true, phase = "tcp", currentHost = host)
                    host to tcpConnect(host, port)
                })
            }
            val results = HashMap<String, Boolean>(candidates.size)
            repeat(candidates.size) {
                val pair = completion.poll((CONNECT_TIMEOUT_MS + 800L), TimeUnit.MILLISECONDS)?.get()
                    ?: return@repeat
                results[pair.first] = pair.second
                val testedNow = tested.incrementAndGet()
                val aliveNow = if (pair.second) aliveCount.incrementAndGet() else aliveCount.get()
                AppLog.i(TAG, "auto resolver probe host=${pair.first}:$port ok=${pair.second} tested=$testedNow/${candidates.size} alive=$aliveNow reason=$reason")
                lastProgress = Progress(
                    active = true,
                    reason = reason,
                    phase = "tcp",
                    currentHost = pair.first,
                    tested = testedNow,
                    total = candidates.size,
                    alive = aliveNow
                )
            }
            candidates.filter { results[it] == true }
        } finally {
            executor.shutdownNow()
        }
    }

    private data class SpeedProbeResult(
        val host: String,
        val hosts: List<String> = listOf(host),
        val ok: Boolean,
        val lookupMs: Long = -1,
        val connectMs: Long = -1,
        val tlsMs: Long = -1,
        val startMs: Long = -1,
        val totalMs: Long = Long.MAX_VALUE,
        val bytes: Long = 0,
        val qnameMtu: Int = 0,
        val error: String = ""
    )

    private fun speedProbeAlive(
        domain: String,
        alive: List<String>,
        port: Int,
        reason: String,
        preferred: List<String>,
        username: String?,
        password: String?,
        qnameMtuOrder: IntArray = QNAME_MTU_PROBE_ORDER,
        batchSize: Int = SPEED_PROBE_BATCH_SIZE,
        stopAfterFirstOk: Boolean = true
    ): List<SpeedProbeResult> {
        val allOrdered = buildList {
            preferred.forEach { if (it in alive && it !in this) add(it) }
            alive.forEach { if (it !in this) add(it) }
        }
        if (allOrdered.isEmpty()) return emptyList()
        AppLog.i(
            TAG,
            "speed probe start reason=$reason total=${allOrdered.size} batchSize=$batchSize " +
                "bytes=$SPEED_PROBE_BYTES minBytes=$SPEED_PROBE_MIN_BYTES candidates=${allOrdered.joinToString()}"
        )
        lastProgress = lastProgress.copy(
            active = true,
            phase = "speed",
            currentHost = "",
            speedTested = 0,
            speedTotal = allOrdered.size,
            speedOk = 0
        )
        val results = ArrayList<SpeedProbeResult>(allOrdered.size)
        val effectiveBatchSize = batchSize.coerceAtLeast(1)
        for ((batchIndex, batch) in allOrdered.chunked(effectiveBatchSize).withIndex()) {
            val batchStart = batchIndex * effectiveBatchSize + 1
            val batchEnd = batchStart + batch.size - 1
            AppLog.i(
                TAG,
                "speed probe batch ${batchIndex + 1} hosts=$batchStart-$batchEnd/${allOrdered.size} " +
                    "candidates=${batch.joinToString()} reason=$reason"
            )
            lastProgress = lastProgress.copy(active = true, phase = "speed", currentHost = batch.joinToString(","))
            val batchResults = speedProbeBatchParallel(domain, batch, port, username, password, qnameMtuOrder)
            results += batchResults
            val ok = batchResults.filter { it.ok }
            val failed = batchResults.filter { !it.ok }
            if (ok.isNotEmpty()) {
                AppLog.i(
                    TAG,
                    "speed probe batch ${batchIndex + 1} ok=${ok.size}/${batch.size} " +
                        "best=${ok.minByOrNull { it.totalMs }?.let { "${it.host}:${it.totalMs}ms" }} " +
                        "resolvers=${ok.joinToString { "${it.host}:${it.totalMs}ms" }} reason=$reason"
                )
            }
            if (failed.isNotEmpty()) {
                AppLog.w(
                    TAG,
                    "speed probe batch ${batchIndex + 1} failed=${failed.size}/${batch.size} " +
                        "resolvers=${failed.joinToString { "${it.host}:${it.error}" }} reason=$reason"
                )
            }
            lastProgress = lastProgress.copy(
                active = true,
                phase = "speed",
                currentHost = batch.lastOrNull().orEmpty(),
                speedTested = batchEnd,
                speedTotal = allOrdered.size,
                speedOk = results.count { it.ok }
            )
            AppLog.i(
                TAG,
                "speed probe batch ${batchIndex + 1} done ok=${ok.size} tested=$batchEnd/${allOrdered.size} reason=$reason"
            )
            if (ok.isNotEmpty() && stopAfterFirstOk) {
                AppLog.i(TAG, "speed probe stop after first data-ok batch=${batchIndex + 1} tested=$batchEnd/${allOrdered.size} reason=$reason")
                break
            }
        }
        val best = results.filter { it.ok }.minByOrNull { it.totalMs }
        AppLog.i(
            TAG,
            "speed probe summary reason=$reason tested=${results.size} ok=${results.count { it.ok }} " +
                "best=${best?.host}:${best?.totalMs}ms"
        )
        return results
    }

    private fun speedProbeBatchParallel(
        domain: String,
        resolverHosts: List<String>,
        resolverPort: Int,
        username: String?,
        password: String?,
        qnameMtuOrder: IntArray
    ): List<SpeedProbeResult> {
        if (resolverHosts.isEmpty()) return emptyList()
        val executor = Executors.newFixedThreadPool(resolverHosts.size)
        val completion = ExecutorCompletionService<SpeedProbeResult>(executor)
        return try {
            resolverHosts.forEach { host ->
                completion.submit(Callable {
                    speedProbeBatch(domain, listOf(host), resolverPort, username, password, qnameMtuOrder)
                })
            }
            val results = ArrayList<SpeedProbeResult>(resolverHosts.size)
            repeat(resolverHosts.size) {
                val result = completion.poll(
                    (SPEED_PROBE_READY_TIMEOUT_MS + SPEED_PROBE_SOCKET_TIMEOUT_MS + 1500L),
                    TimeUnit.MILLISECONDS
                )?.get()
                if (result != null) {
                    results += result
                }
            }
            val missing = resolverHosts.filter { host -> results.none { it.host == host } }
            results += missing.map { host ->
                SpeedProbeResult(host = host, ok = false, error = "parallel probe timed out")
            }
            results.sortedBy { resolverHosts.indexOf(it.host).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun speedProbeBatch(
        domain: String,
        resolverHosts: List<String>,
        resolverPort: Int,
        username: String?,
        password: String?,
        qnameMtuOrder: IntArray
    ): SpeedProbeResult {
        val primaryHost = resolverHosts.firstOrNull().orEmpty()
        var lastError = ""
        for (qnameMtu in qnameMtuOrder) {
            val slipstreamPort = findFreeLocalPort()
            val startAt = System.nanoTime()
            try {
                AppLog.i(TAG, "speed probe try resolvers=${resolverHosts.joinToString()} qnameMtu=${if (qnameMtu > 0) qnameMtu else "max"}")
                SlipstreamBridge.startProbeClient(
                    domain,
                    ResolverListConfig(resolverHosts, resolverPort, true),
                    slipstreamPort,
                    qnameMtu
                ).getOrThrow()
                waitProbeReady(slipstreamPort)
                val readyMs = elapsedMs(startAt)
                val metrics = runCatching {
                    downloadProbe(
                        slipstreamPort,
                        startAt,
                        username?.takeIf { it.isNotBlank() },
                        password?.takeIf { it.isNotBlank() }
                    )
                }
                metrics.onFailure {
                    AppLog.w(
                        TAG,
                        "speed probe data path failed after ready; rejecting resolver " +
                            "qnameMtu=${if (qnameMtu > 0) qnameMtu else "max"} error=${it.message ?: it::class.java.simpleName}"
                    )
                }
                val value = metrics.getOrThrow()
                return SpeedProbeResult(
                    host = primaryHost,
                    hosts = resolverHosts,
                    ok = true,
                    lookupMs = readyMs,
                    connectMs = value.connectMs,
                    tlsMs = value.tlsMs,
                    startMs = value.startMs,
                    totalMs = value.totalMs,
                    bytes = value.bytes,
                    qnameMtu = qnameMtu,
                    error = ""
                )
            } catch (e: Throwable) {
                lastError = "qnameMtu=${if (qnameMtu > 0) qnameMtu else "max"} ${e.message ?: e::class.java.simpleName}"
                AppLog.w(TAG, "speed probe try failed resolvers=${resolverHosts.joinToString()} $lastError")
            } finally {
                runCatching { SlipstreamBridge.stopProbeClient(slipstreamPort) }
            }
        }
        return SpeedProbeResult(host = primaryHost, hosts = resolverHosts, ok = false, error = lastError)
    }

    private data class DownloadMetrics(
        val connectMs: Long,
        val tlsMs: Long,
        val startMs: Long,
        val totalMs: Long,
        val bytes: Long
    )

    private fun downloadProbe(socksPort: Int, startAt: Long, username: String?, password: String?): DownloadMetrics {
        val socket = socksConnect(socksPort, SPEED_PROBE_HOST, 443, username, password)
        socket.use { raw ->
            val connectedMs = elapsedMs(startAt)
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = sslFactory
                .createSocket(raw, SPEED_PROBE_HOST, 443, true) as SSLSocket
            ssl.use { tls ->
                tls.soTimeout = SPEED_PROBE_SOCKET_TIMEOUT_MS
                tls.startHandshake()
                val tlsMs = elapsedMs(startAt)
                val out = tls.getOutputStream()
                val input = tls.getInputStream()
                out.write(
                    (
                        "GET $SPEED_PROBE_PATH HTTP/1.1\r\n" +
                            "Host: $SPEED_PROBE_HOST\r\n" +
                            "User-Agent: SlipstreamCLI-SpeedProbe\r\n" +
                            "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII)
                )
                out.flush()
                val header = readHttpHeader(input)
                val startMs = elapsedMs(startAt)
                if (!header.startsWith("HTTP/1.1 200") && !header.startsWith("HTTP/2 200")) {
                    error("speed http rejected: ${header.lineSequence().firstOrNull().orEmpty()}")
                }
                val bytes = drainBytes(input, SPEED_PROBE_BYTES)
                if (bytes < SPEED_PROBE_MIN_BYTES) {
                    error("speed probe too few bytes: $bytes")
                }
                return DownloadMetrics(
                    connectMs = connectedMs,
                    tlsMs = tlsMs,
                    startMs = startMs,
                    totalMs = elapsedMs(startAt),
                    bytes = bytes
                )
            }
        }
    }

    private fun waitReady() {
        val deadline = System.currentTimeMillis() + SPEED_PROBE_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (SlipstreamBridge.isReady()) return
            Thread.sleep(50)
        }
        error("slipstream speed probe not ready")
    }

    private fun waitProbeReady(listenPort: Int) {
        val deadline = System.currentTimeMillis() + SPEED_PROBE_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (SlipstreamBridge.isProbeReady(listenPort)) return
            Thread.sleep(50)
        }
        error("slipstream speed probe not ready")
    }

    private fun socksConnect(listenPort: Int, host: String, port: Int, username: String?, password: String?): Socket {
        val sock = Socket()
        try {
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress("127.0.0.1", listenPort), SPEED_PROBE_SOCKET_TIMEOUT_MS)
            sock.soTimeout = SPEED_PROBE_SOCKET_TIMEOUT_MS
            val input = sock.getInputStream()
            val output = sock.getOutputStream()
            val hasAuth = !username.isNullOrBlank() && !password.isNullOrBlank()
            output.write(if (hasAuth) byteArrayOf(0x05, 0x01, 0x02) else byteArrayOf(0x05, 0x01, 0x00))
            output.flush()
            val greeting = ByteArray(2)
            input.readFullyStrict(greeting)
            if (greeting[0] != 0x05.toByte() || greeting[1] == 0xFF.toByte()) error("socks greeting rejected")
            if (greeting[1] == 0x02.toByte()) {
                val user = username.orEmpty().toByteArray(Charsets.UTF_8)
                val pass = password.orEmpty().toByteArray(Charsets.UTF_8)
                require(user.size <= 255 && pass.size <= 255) { "auth too long" }
                output.write(byteArrayOf(0x01, user.size.toByte()))
                output.write(user)
                output.write(pass.size)
                output.write(pass)
                output.flush()
                val auth = ByteArray(2)
                input.readFullyStrict(auth)
                if (auth[1] != 0x00.toByte()) error("socks auth rejected")
            }
            val hostBytes = host.toByteArray(Charsets.US_ASCII)
            require(hostBytes.size <= 255) { "host too long" }
            output.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, hostBytes.size.toByte()))
            output.write(hostBytes)
            output.write(byteArrayOf(((port shr 8) and 0xFF).toByte(), (port and 0xFF).toByte()))
            output.flush()
            val header = ByteArray(4)
            input.readFullyStrict(header)
            if (header[1] != 0x00.toByte()) error("socks connect rejected rep=${header[1].toInt() and 0xFF}")
            skipSocksBindAddress(input, header[3].toInt() and 0xFF)
            return sock
        } catch (e: Throwable) {
            runCatching { sock.close() }
            throw e
        }
    }

    private fun readHttpHeader(input: InputStream): String {
        val bytes = ArrayList<Byte>(512)
        var matched = 0
        while (bytes.size < 16384) {
            val b = input.read()
            if (b < 0) break
            bytes.add(b.toByte())
            matched = when {
                matched == 0 && b == '\r'.code -> 1
                matched == 1 && b == '\n'.code -> 2
                matched == 2 && b == '\r'.code -> 3
                matched == 3 && b == '\n'.code -> 4
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) break
        }
        return bytes.toByteArray().toString(Charsets.ISO_8859_1)
    }

    private fun drainBytes(input: InputStream, maxBytes: Int): Long {
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (total < maxBytes) {
            val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - total.toInt()))
            if (read < 0) break
            total += read
        }
        return total
    }

    private fun skipSocksBindAddress(input: InputStream, atyp: Int) {
        val bytes = when (atyp) {
            0x01 -> 4
            0x03 -> input.read().coerceAtLeast(0)
            0x04 -> 16
            else -> 0
        }
        if (bytes > 0) input.readFullyStrict(ByteArray(bytes))
        input.readFullyStrict(ByteArray(2))
    }

    private fun findFreeLocalPort(): Int =
        ServerSocket(0).use { it.localPort }

    private fun elapsedMs(startNs: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

    private fun InputStream.readFullyStrict(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) error("unexpected eof")
            offset += read
        }
    }
}
