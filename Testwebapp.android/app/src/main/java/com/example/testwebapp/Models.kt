package com.example.testwebapp

enum class ConsoleLevel {
    LOG,
    WARN,
    ERROR,
}

data class ConsoleEntry(
    val timeMs: Long,
    val level: ConsoleLevel,
    val message: String,
)

enum class RequestKind {
    FETCH,
    XHR,
    RESOURCE,
    NAVIGATION,
}

data class RequestEntry(
    val timeMs: Long,
    val kind: RequestKind,
    val method: String,
    val url: String,
    val status: Int?,
)
