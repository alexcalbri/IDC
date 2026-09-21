package com.ideasdeveloper.idc

import com.typesafe.config.Config
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import javax.sql.DataSource

object DatabaseFactory {

    private var dataSource: HikariDataSource? = null

    fun init(config: Config) {
        val dbConfig = config.getConfig("database")

        val hikariConfig = HikariConfig().apply {
            driverClassName = dbConfig.getString("driver")
            jdbcUrl = dbConfig.getString("url")
            username = dbConfig.getString("user")
            password = dbConfig.getString("password")
            maximumPoolSize = dbConfig.getInt("maxPoolSize")
            isIsolateInternalQueries = true
            addDataSourceProperty("cachePrepStmts", "true")
            addDataSourceProperty("prepStmtCacheSize", "250")
            addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
            addDataSourceProperty("useServerPrepStmts", "true")
            connectionInitSql = "SET search_path TO \"public\""
        }

        dataSource = HikariDataSource(hikariConfig)
        Database.connect(dataSource!!)
    }

    fun getDataSource(): DataSource = dataSource ?: error("Database not initialized")

    fun close() {
        dataSource?.close()
    }
}