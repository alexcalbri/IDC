package com.ideasdeveloper.idc.server.app

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import org.slf4j.Logger
import kotlin.test.Test
import kotlin.test.assertTrue

class ServerLoggingConfigTest {
    // DEBUG/TRACE of Ktor and the HTTP/database clients writes Authorization headers, session tokens
    // and SQL parameters to journald; the shipped configuration must stay at INFO or above.
    @Test
    fun shippedConfigurationNeverLogsBelowInfo() {
        val context = LoggerContext()
        JoranConfigurator().apply { setContext(context) }
            .doConfigure(javaClass.classLoader.getResource("logback.xml")!!)
        val loggers = listOf(Logger.ROOT_LOGGER_NAME, "io.ktor", "io.ktor.server.plugins.ratelimit", "io.ktor.client", "com.zaxxer.hikari", "Exposed", "org.postgresql")
        loggers.forEach { name ->
            val level = context.getLogger(name).effectiveLevel
            assertTrue(level.isGreaterOrEqual(Level.INFO), "$name logs at $level")
        }
    }
}
