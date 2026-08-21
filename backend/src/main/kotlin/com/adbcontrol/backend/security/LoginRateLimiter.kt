package com.adbcontrol.backend.security

object LoginRateLimiter {
    private val counter = LinkedHashMap<String, Pair<Int, Long>>(64, 0.75f, true)
    private const val MAX_ATTEMPTS = 5
    private const val LOCK_MS = 5 * 60_000L

    @Synchronized fun fail(key: String) {
        val (n, _) = counter.getOrDefault(key, 0 to 0L)
        counter[key] = (n + 1) to System.currentTimeMillis()
        if (counter.size > 1000) {
            val it = counter.entries.iterator()
            it.next(); it.remove()
        }
    }
    @Synchronized fun isLocked(key: String): Boolean {
        val (n, ts) = counter.getOrDefault(key, 0 to 0L)
        if (n >= MAX_ATTEMPTS) {
            if (System.currentTimeMillis() - ts < LOCK_MS) return true
            counter.remove(key)
        }
        return false
    }
    @Synchronized fun clear(key: String) { counter.remove(key) }
}
