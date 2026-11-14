package com.aaai.app.data

enum class DownloadState {
    IDLE,
    QUEUED,
    DOWNLOADING,
    INSTALLING,
    COMPLETED,
    FAILED,
    PAUSED
}
