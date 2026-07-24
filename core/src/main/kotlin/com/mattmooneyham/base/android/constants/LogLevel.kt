package com.mattmooneyham.base.android.constants

/**
 * Severity of a log line. [priority] lets the LogManager filter out anything
 * below a configured threshold; [label] is the single-letter tag used in the
 * log file output.
 */
enum class LogLevel(val priority: Int, val label: String) {
    DEBUG(priority = 0, label = "D"),
    INFO(priority = 1, label = "I"),
    WARN(priority = 2, label = "W"),
    ERROR(priority = 3, label = "E"),
}
