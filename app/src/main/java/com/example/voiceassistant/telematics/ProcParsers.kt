package com.example.voiceassistant.telematics

/**
 * Pure-JVM parsers for the Linux pseudo-filesystems JARVIS reads to deliver live
 * telematics. All functions are deterministic on a given input string and free of
 * Android types, so they are unit-tested on the JVM without Robolectric.
 *
 * What we read and why:
 *  - `/proc/stat`  — overall CPU time across all cores; needed to compute usage %.
 *  - `/proc/[pid]/stat` — this app's CPU time and thread count.
 *  - `/proc/meminfo` — kernel-level memory accounting that backs up `MemoryInfo`.
 *  - `/sys/devices/system/cpu/cpuN/cpufreq/scaling_cur_freq` — per-core CPU frequency
 *    in kHz. Readable on most devices without elevated permissions.
 *  - `/proc/diskstats` and `/proc/net/dev` — read for the storage and network sections,
 *    even though network is always zero for an app that has no INTERNET permission.
 *
 * Each parser is intentionally tolerant: a missing field or a malformed line returns a
 * null field rather than throwing, so a phone with a quirky `/proc` does not crash the
 * screen.
 */
internal object ProcParsers {

    /** Returns the cumulative CPU time across all cores, in jiffies (1/100 s on most Androids). */
    fun parseProcStatTotalJiffies(stat: String): Long? {
        // First non-header line is "cpu  user nice system idle iowait irq softirq steal guest guest_nice".
        for (line in stat.lineSequence()) {
            if (line.startsWith("cpu ")) {
                var sum = 0L
                var any = false
                for (token in line.split(Regex("\\s+")).drop(1)) {
                    val n = token.toLongOrNull() ?: continue
                    sum += n
                    any = true
                }
                return if (any) sum else null
            }
        }
        return null
    }

    /** Returns the cumulative CPU time in jiffies, with idle time split out. */
    fun parseProcStatDetailed(stat: String): CpuTimes? {
        for (line in stat.lineSequence()) {
            if (!line.startsWith("cpu ")) continue
            val tokens = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (tokens.size < 4) return null
            val user = tokens.getOrElse(0) { 0 }
            val nice = tokens.getOrElse(1) { 0 }
            val system = tokens.getOrElse(2) { 0 }
            val idle = tokens.getOrElse(3) { 0 }
            val iowait = tokens.getOrElse(4) { 0 }
            val irq = tokens.getOrElse(5) { 0 }
            val softirq = tokens.getOrElse(6) { 0 }
            val steal = tokens.getOrElse(7) { 0 }
            val total = user + nice + system + idle + iowait + irq + softirq + steal
            val busy = total - idle - iowait
            return CpuTimes(total = total, idle = idle + iowait, busy = busy)
        }
        return null
    }

    /**
     * Parses the per-process stat line. Field indices are documented in `man 5 proc`
     * and are stable; we use them directly. Returns null on the first malformed line.
     */
    fun parseProcessStat(stat: String): ProcessStat? {
        // The process name is wrapped in parens and may contain spaces, so we look for the
        // closing paren first, then split the remainder by whitespace.
        val open = stat.indexOf('(')
        val close = stat.indexOf(')', startIndex = open + 1)
        if (open < 0 || close < 0) return null
        val rest = stat.substring(close + 1).trim().split(Regex("\\s+"))
        if (rest.size < 36) return null
        return runCatching {
            ProcessStat(
                utimeJiffies = rest[11].toLong(),
                stimeJiffies = rest[12].toLong(),
                numThreads = rest[17].toLong(),
                startTimeClockTicks = rest[19].toLong()
            )
        }.getOrNull()
    }

    /**
     * Returns the value of a single field in /proc/meminfo. The lines are formatted as
     * "FieldName: 1234 kB"; we parse the leading label and the integer that follows.
     */
    fun parseMeminfoField(meminfo: String, field: String): Long? {
        for (line in meminfo.lineSequence()) {
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val name = line.substring(0, colon)
            if (name != field) continue
            val value = line.substring(colon + 1).trim().split(Regex("\\s+")).firstOrNull()
                ?: return null
            return value.toLongOrNull()
        }
        return null
    }

    /**
     * Reads the first numeric value in a file like
     *   "1234567\n"
     * and returns it as a Long. Used for /sys/devices/system/cpu/cpuN/.../scaling_cur_freq
     * which contains a single integer in kHz.
     */
    fun parseSingleInteger(content: String): Long? =
        content.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()

    data class CpuTimes(val total: Long, val idle: Long, val busy: Long) {
        /** Busy fraction across the snapshot window, 0..1. */
        fun busyFraction(): Float = if (total == 0L) 0f else (busy.toDouble() / total).toFloat()
    }

    data class ProcessStat(
        val utimeJiffies: Long,
        val stimeJiffies: Long,
        val numThreads: Long,
        val startTimeClockTicks: Long
    ) {
        fun totalJiffies(): Long = utimeJiffies + stimeJiffies
    }
}
