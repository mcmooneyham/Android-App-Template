package com.mattmooneyham.base.android.constants

/**
 * Severity of a log line. [priority] lets the LogManager filter out
 * anything below a configured threshold; file lines and the platform
 * mirror print the full level name (e.g. "DEBUG").
 */
enum class LogLevel(val priority: Int) {
    DEBUG(priority = 0),
    INFO(priority = 1),
    WARN(priority = 2),
    ERROR(priority = 3),
}
