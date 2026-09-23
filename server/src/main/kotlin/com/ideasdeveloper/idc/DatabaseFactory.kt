package com.ideasdeveloper.idc

import io.ktor.server.config.ApplicationConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database
import javax.sql.DataSource

object DatabaseFactory {

    private var dataSource: HikariDataSource? = null
    private var database: Database? = null

    fun init(config: ApplicationConfig) {
        check(dataSource == null) { "Database already initialized" }
        val dbConfig = config.config("database")

        val hikariConfig = HikariConfig().apply {
            driverClassName = dbConfig.property("driver").getString()
            jdbcUrl = dbConfig.property("url").getString()
            username = dbConfig.property("user").getString()
            password = dbConfig.property("password").getString()
            maximumPoolSize = dbConfig.property("maxPoolSize").getString().toInt()
            isIsolateInternalQueries = true
            addDataSourceProperty("cachePrepStmts", "true")
            addDataSourceProperty("prepStmtCacheSize", "250")
            addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
            addDataSourceProperty("useServerPrepStmts", "true")
            connectionInitSql = "SET search_path TO \"public\""
        }

        val pool = HikariDataSource(hikariConfig)
        try {
            pool.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT 1").use { result ->
                        check(result.next() && result.getInt(1) == 1) {
                            "PostgreSQL connection check failed"
                        }
                    }
                }
            }
            database = Database.connect(pool)
            dataSource = pool
        } catch (failure: Exception) {
            pool.close()
            throw failure
        }
    }

    fun getDataSource(): DataSource = dataSource ?: error("Database not initialized")

    fun getDatabase(): Database =
        database ?: error("Database not initialized")
    fun close() {
        dataSource?.close()
        dataSource = null
        database = null
    }

}
