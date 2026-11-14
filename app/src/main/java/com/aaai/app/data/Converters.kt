package com.aaai.app.data

import androidx.room.TypeConverter

class Converters {

    @TypeConverter
    fun fromAppCategory(value: AppCategory?): String? = value?.name

    @TypeConverter
    fun toAppCategory(value: String?): AppCategory? =
        value?.let { AppCategory.fromString(it) }

    @TypeConverter
    fun fromAppSourceType(value: AppSourceType?): String? = value?.name

    @TypeConverter
    fun toAppSourceType(value: String?): AppSourceType? =
        value?.let { AppSourceType.fromString(it) }

    @TypeConverter
    fun fromDownloadState(value: DownloadState?): String? = value?.name

    @TypeConverter
    fun toDownloadState(value: String?): DownloadState? =
        value?.let { v -> DownloadState.entries.firstOrNull { it.name == v } }
}
