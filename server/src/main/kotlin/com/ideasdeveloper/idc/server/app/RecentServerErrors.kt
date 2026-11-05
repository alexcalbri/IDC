package com.ideasdeveloper.idc.server.app

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.ArrayDeque

@Serializable
data class ServerErrorLogEntry(
    val timestamp: String,
    val logger: String,
    val message: String,
    val details: String = "",
)

@Serializable
data class ServerLogsResponse(val errors: List<ServerErrorLogEntry>)

object RecentServerErrors {
    private const val MaxEntries = 100
    private const val MaxEntryCharacters = 12_000
    private val entries = ArrayDeque<ServerErrorLogEntry>()
    private val sensitiveValue = Regex(
        """(?i)((?:authorization\s*[:=]\s*bearer\s+)|(?:password|secret|token|api[_-]?key)\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^\s,;]+)"""
    )

    @Synchronized
    internal fun add(event: ILoggingEvent) {
        event.prepareForDeferredProcessing()
        val throwable = event.throwableProxy?.let { ThrowableProxyUtil.asString(it) }.orEmpty()
        val details = sanitize(throwable).take(MaxEntryCharacters)
        val entry = ServerErrorLogEntry(
            timestamp = Instant.ofEpochMilli(event.timeStamp).toString(),
            logger = event.loggerName,
            message = sanitize(event.formattedMessage).take(MaxEntryCharacters),
            details = details,
        )
        if (entries.size == MaxEntries) entries.removeFirst()
        entries.addLast(entry)
    }

    @Synchronized
    fun snapshot(): List<ServerErrorLogEntry> = entries.toList()

    private fun sanitize(value: String): String = sensitiveValue.replace(value) { match ->
        val prefix = match.groupValues[1]
        "$prefix[redacted]"
    }
}

class RecentServerErrorsAppender : AppenderBase<ILoggingEvent>() {
    override fun append(eventObject: ILoggingEvent) {
        if (eventObject.level.isGreaterOrEqual(Level.ERROR)) {
            RecentServerErrors.add(eventObject)
        }
    }
}
