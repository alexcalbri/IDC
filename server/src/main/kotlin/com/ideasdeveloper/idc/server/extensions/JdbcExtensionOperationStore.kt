package com.ideasdeveloper.idc.server.extensions

/** extension_operations and extension_operation_targets in the control database (V011). */
class JdbcExtensionOperationStore(private val connections: ExtensionConnections) : ExtensionOperationStore {
    override fun create(actor: String, operation: String, extensionId: String, sourceCompany: String?, targets: List<String>): Long =
        connections.central().use { connection ->
            val previous = connection.autoCommit
            connection.autoCommit = false
            try {
                val id = connection.prepareStatement(
                    "INSERT INTO extension_operations (actor, operation, extension_id, source_company) VALUES (?, ?, ?, ?) RETURNING id"
                ).use { statement ->
                    statement.setString(1, actor)
                    statement.setString(2, operation)
                    statement.setString(3, extensionId)
                    statement.setString(4, sourceCompany)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getLong(1)
                    }
                }
                connection.prepareStatement("INSERT INTO extension_operation_targets (operation_id, company_code) VALUES (?, ?) ON CONFLICT DO NOTHING").use { statement ->
                    targets.forEach { code ->
                        statement.setLong(1, id)
                        statement.setString(2, code)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.commit()
                id
            } catch (failure: Throwable) {
                runCatching { connection.rollback() }
                throw failure
            } finally {
                connection.autoCommit = previous
            }
        }

    override fun failInterrupted(): Int = connections.central().use { connection ->
        val previous = connection.autoCommit
        connection.autoCommit = false
        try {
            connection.prepareStatement(
                """
                UPDATE extension_operation_targets
                SET status = 'failed', reason = ?, updated_at = CURRENT_TIMESTAMP
                WHERE status = 'pending' AND operation_id IN (SELECT id FROM extension_operations WHERE status = 'running')
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, InterruptedReason)
                statement.executeUpdate()
            }
            val closed = connection.prepareStatement(
                "UPDATE extension_operations SET status = 'failed', finished_at = CURRENT_TIMESTAMP WHERE status = 'running'"
            ).use { it.executeUpdate() }
            connection.commit()
            closed
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }
            throw failure
        } finally {
            connection.autoCommit = previous
        }
    }

    override fun targets(operationId: Long): List<TargetRecord> = connections.central().use { connection ->
        connection.prepareStatement(
            "SELECT company_code, status, reason, attempts FROM extension_operation_targets WHERE operation_id = ? ORDER BY company_code"
        ).use { query ->
            query.setLong(1, operationId)
            query.executeQuery().use { rows ->
                buildList { while (rows.next()) add(TargetRecord(rows.getString(1), rows.getString(2), rows.getString(3), rows.getInt(4))) }
            }
        }
    }

    override fun updateTarget(operationId: Long, companyCode: String, status: String, reason: String?, attempts: Int) {
        connections.central().use { connection ->
            connection.prepareStatement(
                """
                UPDATE extension_operation_targets
                SET status = ?, reason = ?, attempts = ?, updated_at = CURRENT_TIMESTAMP
                WHERE operation_id = ? AND company_code = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, status)
                statement.setString(2, reason?.take(500))
                statement.setInt(3, attempts)
                statement.setLong(4, operationId)
                statement.setString(5, companyCode)
                statement.executeUpdate()
            }
        }
    }

    override fun finish(operationId: Long, status: String) {
        connections.central().use { connection ->
            connection.prepareStatement("UPDATE extension_operations SET status = ?, finished_at = CURRENT_TIMESTAMP WHERE id = ?").use { statement ->
                statement.setString(1, status)
                statement.setLong(2, operationId)
                statement.executeUpdate()
            }
        }
    }

    override fun header(operationId: Long): OperationHeader? = connections.central().use { connection ->
        connection.prepareStatement("SELECT id, actor, operation, extension_id, source_company, status FROM extension_operations WHERE id = ?").use { query ->
            query.setLong(1, operationId)
            query.executeQuery().use { rows ->
                if (rows.next()) OperationHeader(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6)) else null
            }
        }
    }
}
