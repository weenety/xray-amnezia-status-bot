package statusbot

import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val DEFAULT_NETWORK_DNS_HOST = "google.com"
private val DEFAULT_NETWORK_URLS = listOf(
    "https://www.google.com/generate_204",
    "https://cp.cloudflare.com/generate_204",
)
private const val DEFAULT_NETWORK_HTTP_TIMEOUT_SECONDS = 5
private const val DEFAULT_NETWORK_TCP_HOST = "1.1.1.1"
private const val DEFAULT_NETWORK_TCP_PORT = 443
private const val DEFAULT_NETWORK_PING_HOST = "1.1.1.1"
private const val DEFAULT_NETWORK_PING_COUNT = 4
private const val DEFAULT_NETWORK_PING_TIMEOUT_SECONDS = 3
private const val DEFAULT_NETWORK_PING_WARN_LOSS_PERCENT = 5.0
private const val DEFAULT_NETWORK_PING_CRIT_LOSS_PERCENT = 20.0
private const val DEFAULT_NETWORK_PING_WARN_AVG_MS = 250.0
private const val DEFAULT_XRAY_RESTART_WARN_COUNT = 3
private const val DEFAULT_CPU_SAMPLE_WINDOW_MILLIS = 1_000L
private val SPLIT_WHITESPACE_REGEX = Regex("\\s+")
private val PING_LOSS_REGEX = Regex("""([\d.]+)%\s+packet loss""")
private val PING_AVG_REGEX = Regex("""=\s*[\d.]+/([\d.]+)/[\d.]+/[\d.]+""")
private val PING_AVG_SHORT_REGEX = Regex("""=\s*[\d.]+/([\d.]+)/[\d.]+""")
private val DEFAULT_ROUTE_REGEX = Regex("""default via (\S+) dev (\S+)""")
private val SHARED_HTTP_CLIENT: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(DEFAULT_NETWORK_HTTP_TIMEOUT_SECONDS.toLong()))
    .build()
private val RESOLVED_PING_PATH: String? by lazy { resolvePingBinary() }

object ThresholdEvaluator {
    fun classify(valuePercent: Double, criticalPercent: Double): HealthLevel {
        if (valuePercent < 0) {
            return HealthLevel.WARN
        }
        if (valuePercent >= criticalPercent) {
            return HealthLevel.CRIT
        }
        if (valuePercent >= criticalPercent * 0.9) {
            return HealthLevel.WARN
        }
        return HealthLevel.OK
    }
}

data class PingProbeResult(
    val lossPercent: Double? = null,
    val avgMs: Double? = null,
    val error: String? = null,
)

data class NetworkProbeData(
    val dnsOk: Boolean,
    val httpOk: Int,
    val httpTotal: Int,
    val httpFailures: List<String>,
    val tcpOk: Boolean,
    val ping: PingProbeResult,
    val gateway: String?,
    val defaultIface: String?,
    val ifaceStats: InterfaceStats?,
)

data class InterfaceStats(
    val rxErrors: Long,
    val txErrors: Long,
    val rxDropped: Long,
    val txDropped: Long,
    val rxBytes: Long,
    val txBytes: Long,
)

private data class TrafficCounterSample(
    val iface: String,
    val rxBytes: Long,
    val txBytes: Long,
    val measuredAtNanos: Long,
)

class NetworkHealthChecker {
    @Volatile
    private var latestTraffic: TrafficSnapshot? = null
    private var previousTrafficSample: TrafficCounterSample? = null

    fun check(): CheckResult {
        val (probe, errors, warnings) = collectProbeData()
        latestTraffic = probe.defaultIface
            ?.let { iface -> probe.ifaceStats?.let { stats -> toTrafficSnapshot(iface, stats) } }
        evaluateHttp(probe.httpOk, probe.httpFailures, errors, warnings)
        evaluatePing(probe.ping, errors, warnings)
        val level = resolveLevel(errors, warnings)
        val summary = buildSummary(probe)
        val details = buildDetails(probe, warnings, errors)
        return CheckResult("Network", level, summary, details)
    }

    fun latestTrafficSnapshot(): TrafficSnapshot? = latestTraffic

    private fun collectProbeData(): Triple<NetworkProbeData, MutableList<String>, MutableList<String>> {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val dnsOk = checkDns(errors)
        val (httpOk, httpTotal, httpFailures) = checkHttp()
        val tcpOk = checkTcp(errors)
        val ping = checkPing()
        val (gateway, defaultIface) = defaultRoute()
        val ifaceStats = if (defaultIface != null) readInterfaceStats(defaultIface) else null

        val probe = NetworkProbeData(
            dnsOk = dnsOk,
            httpOk = httpOk,
            httpTotal = httpTotal,
            httpFailures = httpFailures,
            tcpOk = tcpOk,
            ping = ping,
            gateway = gateway,
            defaultIface = defaultIface,
            ifaceStats = ifaceStats,
        )

        return Triple(probe, errors, warnings)
    }

    private fun evaluateHttp(
        httpOk: Int,
        httpFailures: List<String>,
        errors: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        if (httpOk == 0) {
            errors += httpFailures
            return
        }
        warnings += httpFailures
    }

    private fun evaluatePing(
        ping: PingProbeResult,
        errors: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        if (ping.error != null) {
            warnings += "ping($DEFAULT_NETWORK_PING_HOST): ${ping.error}"
            return
        }

        ping.lossPercent?.let { loss ->
            when {
                loss >= DEFAULT_NETWORK_PING_CRIT_LOSS_PERCENT -> errors += "ping loss ${formatDouble(loss)}%"
                loss >= DEFAULT_NETWORK_PING_WARN_LOSS_PERCENT -> warnings += "ping loss ${formatDouble(loss)}%"
            }
        }

        ping.avgMs?.let { avg ->
            if (avg >= DEFAULT_NETWORK_PING_WARN_AVG_MS) {
                warnings += "ping avg ${formatDouble(avg)}ms"
            }
        }
    }

    private fun resolveLevel(errors: List<String>, warnings: List<String>): HealthLevel {
        if (errors.isNotEmpty()) {
            return HealthLevel.CRIT
        }
        if (warnings.isNotEmpty()) {
            return HealthLevel.WARN
        }
        return HealthLevel.OK
    }

    private fun buildSummary(probe: NetworkProbeData): String {
        val summary = StringBuilder()
        summary.append("dns=").append(if (probe.dnsOk) "ok" else "fail")
        summary.append(", http=").append(probe.httpOk).append('/').append(probe.httpTotal)
        summary.append(", tcp=").append(if (probe.tcpOk) "ok" else "fail")
        probe.ping.lossPercent?.let { summary.append(", pingLoss=").append(formatDouble(it)).append('%') }
        return summary.toString()
    }

    private fun buildDetails(
        probe: NetworkProbeData,
        warnings: List<String>,
        errors: List<String>,
    ): String {
        val detailsParts = mutableListOf<String>()
        detailsParts += "defaultIface=${probe.defaultIface ?: "unknown"}"
        detailsParts += "defaultGw=${probe.gateway ?: "unknown"}"

        probe.ping.avgMs?.let { detailsParts += "pingAvg=${formatDouble(it)}ms" }

        probe.ifaceStats?.let { stats ->
            detailsParts += "ifaceErrors=" +
                "rx_err ${stats.rxErrors}, " +
                "tx_err ${stats.txErrors}, " +
                "rx_drop ${stats.rxDropped}, " +
                "tx_drop ${stats.txDropped}"
        }

        if (warnings.isNotEmpty()) {
            detailsParts += "warn=${warnings.joinToString(" | ")}"
        }
        if (errors.isNotEmpty()) {
            detailsParts += "errors=${errors.joinToString(" | ")}"
        }

        return detailsParts.joinToString(", ")
    }

    private fun checkDns(errors: MutableList<String>): Boolean {
        return try {
            InetAddress.getAllByName(DEFAULT_NETWORK_DNS_HOST)
            true
        } catch (ex: Exception) {
            errors += "dns($DEFAULT_NETWORK_DNS_HOST): ${ex.message ?: ex::class.simpleName}"
            false
        }
    }

    private fun checkHttp(): Triple<Int, Int, List<String>> {
        var ok = 0
        val failures = mutableListOf<String>()

        for (url in DEFAULT_NETWORK_URLS) {
            try {
                val request = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(DEFAULT_NETWORK_HTTP_TIMEOUT_SECONDS.toLong()))
                    .build()

                val response = SHARED_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.discarding())
                val status = response.statusCode()
                if (status in 200..399) {
                    ok += 1
                } else {
                    failures += "http($url) status=$status"
                }
            } catch (ex: Exception) {
                failures += "http($url): ${ex.message ?: ex::class.simpleName}"
            }
        }

        return Triple(ok, DEFAULT_NETWORK_URLS.size, failures)
    }

    private fun checkTcp(errors: MutableList<String>): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(DEFAULT_NETWORK_TCP_HOST, DEFAULT_NETWORK_TCP_PORT),
                    DEFAULT_NETWORK_HTTP_TIMEOUT_SECONDS * 1000,
                )
            }
            true
        } catch (ex: Exception) {
            errors += "tcp($DEFAULT_NETWORK_TCP_HOST:$DEFAULT_NETWORK_TCP_PORT): ${ex.message ?: ex::class.simpleName}"
            false
        }
    }

    private fun checkPing(): PingProbeResult {
        val pingPath = RESOLVED_PING_PATH ?: return PingProbeResult(error = "ping not installed")
        val command = listOf(
            pingPath,
            "-c",
            DEFAULT_NETWORK_PING_COUNT.toString(),
            "-W",
            DEFAULT_NETWORK_PING_TIMEOUT_SECONDS.toString(),
            DEFAULT_NETWORK_PING_HOST,
        )

        val timeout = maxOf(3, DEFAULT_NETWORK_PING_COUNT * DEFAULT_NETWORK_PING_TIMEOUT_SECONDS + 2)
        val result = runCommand(command, timeout)
        if (result.timedOut) {
            return PingProbeResult(error = "ping timeout")
        }

        val (loss, avg) = parsePingOutput(result.output)
        if (result.code != 0 && loss == null) {
            return PingProbeResult(error = "ping command failed")
        }

        return PingProbeResult(lossPercent = loss, avgMs = avg)
    }

    private fun parsePingOutput(output: String): Pair<Double?, Double?> {
        val loss = PING_LOSS_REGEX
            .find(output)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()

        val avg = PING_AVG_REGEX
            .find(output)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?: PING_AVG_SHORT_REGEX
                .find(output)
                ?.groupValues
                ?.getOrNull(1)
                ?.toDoubleOrNull()

        return loss to avg
    }

    private fun defaultRoute(): Pair<String?, String?> {
        val result = runCommand(listOf("ip", "route", "show", "default"), 2)
        if (result.code != 0 || result.output.isBlank()) {
            return null to null
        }

        val line = result.output.lineSequence().firstOrNull()?.trim().orEmpty()
        val match = DEFAULT_ROUTE_REGEX.find(line) ?: return null to null
        return match.groupValues[1] to match.groupValues[2]
    }

    private fun readInterfaceStats(iface: String): InterfaceStats? {
        val statsPath = Paths.get("/sys/class/net/$iface/statistics")
        if (!Files.isDirectory(statsPath)) {
            return null
        }

        fun readStat(field: String): Long? {
            return try {
                Files.readString(statsPath.resolve(field)).trim().toLongOrNull()
            } catch (_: Exception) {
                null
            }
        }

        return InterfaceStats(
            rxErrors = readStat("rx_errors") ?: return null,
            txErrors = readStat("tx_errors") ?: return null,
            rxDropped = readStat("rx_dropped") ?: return null,
            txDropped = readStat("tx_dropped") ?: return null,
            rxBytes = readStat("rx_bytes") ?: return null,
            txBytes = readStat("tx_bytes") ?: return null,
        )
    }

    private fun toTrafficSnapshot(iface: String, stats: InterfaceStats): TrafficSnapshot {
        val current = TrafficCounterSample(
            iface = iface,
            rxBytes = stats.rxBytes,
            txBytes = stats.txBytes,
            measuredAtNanos = System.nanoTime(),
        )
        val previous = previousTrafficSample
        previousTrafficSample = current

        val rates = if (previous != null && previous.iface == current.iface) {
            val elapsedNanos = current.measuredAtNanos - previous.measuredAtNanos
            val rxDelta = current.rxBytes - previous.rxBytes
            val txDelta = current.txBytes - previous.txBytes
            if (elapsedNanos > 0 && rxDelta >= 0 && txDelta >= 0) {
                TrafficRates(
                    rxBytesPerSecond = rxDelta * 1_000_000_000.0 / elapsedNanos.toDouble(),
                    txBytesPerSecond = txDelta * 1_000_000_000.0 / elapsedNanos.toDouble(),
                )
            } else {
                null
            }
        } else {
            null
        }

        return TrafficSnapshot(
            totals = TrafficTotals(
                iface = iface,
                rxBytes = stats.rxBytes,
                txBytes = stats.txBytes,
            ),
            rates = rates,
        )
    }
}

class XrayHealthChecker(private val settings: Settings) {
    fun check(): CheckResult {
        val timeout = settings.xrayCommandTimeoutSeconds
        val serviceName = settings.xrayServiceName

        val status = runCommand(listOf("systemctl", "is-active", serviceName), timeout)
        if (status.timedOut) {
            return CheckResult(
                component = "Xray",
                level = HealthLevel.CRIT,
                summary = "status timeout",
                details = "systemctl is-active timed out",
            )
        }

        val output = status.output.trim()
        if (status.code != 0 || output != "active") {
            val details = output.ifBlank { "inactive" }
            return CheckResult("Xray", HealthLevel.CRIT, "inactive", details)
        }

        val meta = runCommand(
            listOf(
                "systemctl",
                "show",
                serviceName,
                "--property",
                "SubState,ActiveEnterTimestamp,NRestarts",
                "--value",
            ),
            timeout,
        )

        if (meta.code != 0) {
            return CheckResult("Xray", HealthLevel.OK, "active", "active")
        }

        val lines = meta.output.lines().map { it.trim() }
        val subState = lines.getOrNull(0).orEmpty().ifBlank { "active" }
        val activeSince = lines.getOrNull(1).orEmpty().ifBlank { "unknown" }
        val restartCount = lines.getOrNull(2)?.toIntOrNull() ?: 0

        if (restartCount >= DEFAULT_XRAY_RESTART_WARN_COUNT) {
            return CheckResult(
                component = "Xray",
                level = HealthLevel.WARN,
                summary = "active, restarts=$restartCount",
                details = "subState=$subState; since=$activeSince",
            )
        }

        return CheckResult(
            component = "Xray",
            level = HealthLevel.OK,
            summary = "active",
            details = "subState=$subState; since=$activeSince; restarts=$restartCount",
        )
    }
}

class AmneziaDockerHealthChecker(private val settings: Settings) {
    fun check(): CheckResult {
        val timeout = settings.amneziaDockerTimeoutSeconds
        val containerName = settings.amneziaContainerName

        val inspect = runCommand(
            listOf(
                "docker",
                "inspect",
                "--format",
                "{{.State.Status}}|{{.State.Running}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}|{{.Name}}",
                containerName,
            ),
            timeout,
        )

        if (inspect.timedOut) {
            return CheckResult(
                component = "AmneziaWG",
                level = HealthLevel.CRIT,
                summary = "inspect timeout",
                details = "docker inspect timed out",
            )
        }

        if (inspect.code != 0) {
            val details = inspect.output.ifBlank { "container not found" }
            return CheckResult(
                component = "AmneziaWG",
                level = HealthLevel.CRIT,
                summary = "inspect failed",
                details = details,
            )
        }

        val parsed = parseInspectOutput(inspect.output, containerName)
            ?: return CheckResult(
                component = "AmneziaWG",
                level = HealthLevel.WARN,
                summary = "inspect parse failed",
                details = inspect.output,
            )

        if (!parsed.running) {
            return CheckResult(
                component = "AmneziaWG",
                level = HealthLevel.CRIT,
                summary = "not running",
                details = "name=${parsed.name}; status=${parsed.status}; health=${parsed.health}",
            )
        }

        if (parsed.health == "unhealthy") {
            return CheckResult(
                component = "AmneziaWG",
                level = HealthLevel.CRIT,
                summary = "running, unhealthy",
                details = "name=${parsed.name}; status=${parsed.status}; health=${parsed.health}",
            )
        }

        if (parsed.health == "starting") {
            return CheckResult(
                component = "AmneziaWG",
                level = HealthLevel.WARN,
                summary = "running, health=starting",
                details = "name=${parsed.name}; status=${parsed.status}; health=${parsed.health}",
            )
        }

        return CheckResult(
            component = "AmneziaWG",
            level = HealthLevel.OK,
            summary = "running",
            details = "name=${parsed.name}; status=${parsed.status}; health=${parsed.health}",
        )
    }

    private fun parseInspectOutput(output: String, fallbackName: String): DockerContainerState? {
        val line = output.lineSequence().firstOrNull()?.trim().orEmpty()
        if (line.isBlank()) {
            return null
        }

        val parts = line.split("|")
        if (parts.size < 4) {
            return null
        }

        val status = parts[0].trim().ifBlank { "unknown" }
        val running = parts[1].trim().equals("true", ignoreCase = true)
        val health = parts[2].trim().ifBlank { "none" }
        val name = parts[3].trim().removePrefix("/").ifBlank { fallbackName }
        return DockerContainerState(name = name, status = status, running = running, health = health)
    }
}

private data class DockerContainerState(
    val name: String,
    val status: String,
    val running: Boolean,
    val health: String,
)

class SystemHealthChecker(private val settings: Settings) {
    fun check(): List<CheckResult> {
        val cpu = cpuPercent()
        val ram = ramPercent()
        val disk = rootDiskPercent()

        val cpuLevel = ThresholdEvaluator.classify(cpu, settings.thresholdCpuPercent)
        val ramLevel = ThresholdEvaluator.classify(ram, settings.thresholdRamPercent)
        val diskLevel = ThresholdEvaluator.classify(disk, settings.thresholdDiskPercent)

        return listOf(
            CheckResult(
                component = "CPU",
                level = cpuLevel,
                summary = fmtPercent(cpu),
                details = "cpuLoad=${fmtPercent(cpu)}",
            ),
            CheckResult(
                component = "RAM",
                level = ramLevel,
                summary = fmtPercent(ram),
                details = "used=${fmtPercent(ram)}",
            ),
            CheckResult(
                component = "Disk",
                level = diskLevel,
                summary = fmtPercent(disk),
                details = "rootUsed=${fmtPercent(disk)}",
            ),
        )
    }

    private fun cpuPercent(): Double {
        return try {
            val (idle1, total1) = readProcStat()
            Thread.sleep(DEFAULT_CPU_SAMPLE_WINDOW_MILLIS)
            val (idle2, total2) = readProcStat()

            val totalDelta = total2 - total1
            val idleDelta = idle2 - idle1
            if (totalDelta <= 0) {
                -1.0
            } else {
                ((1.0 - (idleDelta / totalDelta)) * 100.0).coerceIn(0.0, 100.0)
            }
        } catch (_: Exception) {
            -1.0
        }
    }

    private fun readProcStat(): Pair<Double, Double> {
        val line = Files.newBufferedReader(Paths.get("/proc/stat")).use { it.readLine() ?: "" }.trim()
        val parts = line.split(SPLIT_WHITESPACE_REGEX)
        val values = parts.drop(1).map { it.toDouble() }
        val idle = values.getOrElse(3) { 0.0 } + values.getOrElse(4) { 0.0 }
        val total = values.sum()
        return idle to total
    }

    private fun ramPercent(): Double {
        return try {
            var totalKb = 0.0
            var availableKb = 0.0

            Files.newBufferedReader(Paths.get("/proc/meminfo")).useLines { lines ->
                lines.forEach { line ->
                    when {
                        line.startsWith("MemTotal:") -> {
                            totalKb = line.split(SPLIT_WHITESPACE_REGEX).getOrNull(1)?.toDoubleOrNull() ?: 0.0
                        }

                        line.startsWith("MemAvailable:") -> {
                            availableKb = line.split(SPLIT_WHITESPACE_REGEX).getOrNull(1)?.toDoubleOrNull() ?: 0.0
                        }
                    }
                }
            }

            if (totalKb <= 0) {
                -1.0
            } else {
                ((totalKb - availableKb) / totalKb * 100.0).coerceIn(0.0, 100.0)
            }
        } catch (_: Exception) {
            -1.0
        }
    }

    private fun rootDiskPercent(): Double {
        return try {
            val file = File("/")
            val total = file.totalSpace
            if (total <= 0) {
                -1.0
            } else {
                val used = total - file.freeSpace
                (used.toDouble() / total.toDouble() * 100.0).coerceIn(0.0, 100.0)
            }
        } catch (_: Exception) {
            -1.0
        }
    }

    private fun fmtPercent(value: Double): String {
        if (value < 0) {
            return "unknown"
        }
        return String.format(Locale.US, "%.1f%%", value)
    }
}

private data class CommandResult(
    val code: Int,
    val output: String,
    val timedOut: Boolean,
)

private fun runCommand(command: List<String>, timeoutSeconds: Int): CommandResult {
    return try {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val completed = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
            return CommandResult(-1, "timeout", true)
        }

        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        CommandResult(process.exitValue(), output, false)
    } catch (ex: Exception) {
        CommandResult(-1, ex.message ?: ex::class.simpleName.orEmpty(), false)
    }
}

private fun resolvePingBinary(): String? {
    val result = runCommand(listOf("sh", "-c", "command -v ping"), 2)
    if (result.code != 0 || result.output.isBlank()) {
        return null
    }
    return result.output.lineSequence().firstOrNull()?.trim().takeUnless { it.isNullOrBlank() }
}

private fun formatDouble(value: Double): String {
    return String.format(Locale.US, "%.1f", value)
}
