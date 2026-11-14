package com.aaai.app.data

enum class AppCategory {
    MULTIMEDIA,
    MIRRORING,
    UTILITIES,
    NAVIGATION,
    COMMUNICATION,
    OTHER;

    companion object {
        fun fromString(value: String?): AppCategory =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: OTHER
    }
}
