package com.ideasdeveloper.idc.server.extensions.packages

/**
 * Revisa el SQL de las migraciones de un paquete externo: solo pueden crear y cambiar tablas
 * `ext_<id>__*` (doble guion bajo, para que la extension `a` no toque tablas de `a_b`). No es un
 * parser completo de PostgreSQL; por eso es una lista blanca estricta: toda sentencia, accion o
 * funcion que no reconoce se rechaza. El limite real de confianza es la firma o la autorizacion por
 * hash del paquete; este validador evita errores y abusos obvios, no sustituye esa confianza.
 *
 * - Permitido: BEGIN, COMMIT, CREATE/ALTER/DROP TABLE, TRUNCATE, INSERT ... VALUES, UPDATE, DELETE,
 *   CREATE/DROP INDEX, GRANT/REVOKE y COMMENT ON TABLE/COLUMN, siempre sobre `ext_<id>__*` (los
 *   indices tambien, porque sus nombres son globales).
 * - ALTER TABLE solo con ADD COLUMN, ADD CONSTRAINT, DROP COLUMN, DROP CONSTRAINT,
 *   ALTER COLUMN ... SET/DROP DEFAULT|NOT NULL|TYPE, RENAME COLUMN y RENAME TO ext_<id>__*.
 * - Prohibido: comillas dobles, `\`, cadenas E'' y U&'', comentarios de bloque, comillas de dolar,
 *   DO, funciones/triggers/extensiones/roles, SET, COPY, SELECT, TABLE x, VALUES fuera de INSERT,
 *   REFERENCES ajenas, cualquier schema distinto de public y toda llamada a funcion salvo una lista
 *   corta de tipos y funciones puras.
 * - Las tablas se nombran completas: no se permiten alias ni calificadores que no sean public.
 */
object MigrationTableValidator {
    private const val Name = "[a-z_][a-z0-9_]*"
    private val tokens = Regex("'(?:[^']|'')*'|--[^\\n]*", RegexOption.DOT_MATCHES_ALL)
    private val dollarQuote = Regex("[$][a-z_0-9]*[$]", RegexOption.IGNORE_CASE)
    private val qualifier = Regex("([a-z_][a-z0-9_]*)\\s*\\.\\s*(?=[a-z_*])")
    private val publicSchema = Regex("\\bpublic\\.")
    private val call = Regex("($Name)\\s*\\(")

    private val rawForbidden = listOf(
        Regex("\"") to "comillas dobles (identificadores entre comillas)",
        Regex("\\\\") to "la barra invertida",
        Regex("(?<![a-z0-9_])e'", RegexOption.IGNORE_CASE) to "cadenas E'...'",
        Regex("u&", RegexOption.IGNORE_CASE) to "cadenas U&'...'",
        Regex("/\\*") to "comentarios de bloque /* */",
    )

    // Tipos y funciones puras que pueden llamarse con parentesis.
    private val allowedCalls = setOf(
        "varchar", "char", "character", "varying", "numeric", "decimal", "timestamp", "timestamptz", "time", "interval",
        "bit", "varbit", "now", "current_timestamp", "current_date", "gen_random_uuid", "lower", "upper", "coalesce",
        "length", "nullif",
    )

    // Palabras SQL que pueden ir pegadas a un parentesis.
    private val keywordsBeforeParen = setOf(
        "values", "check", "key", "unique", "in", "and", "or", "not", "default", "where", "set", "on", "as", "exists",
        "conflict", "btree", "hash", "when", "then", "else",
    )

    private val forbiddenStart = listOf(
        Regex("^do\\b") to "DO",
        Regex("^(create|alter|drop)\\s+(or\\s+replace\\s+)?(constraint\\s+)?trigger\\b") to "CREATE TRIGGER",
        Regex("^(create|alter|drop)\\s+(or\\s+replace\\s+)?(function|procedure|aggregate)\\b") to "CREATE FUNCTION",
        Regex("^(create|alter|drop)\\s+extension\\b") to "CREATE EXTENSION",
        Regex("^(create|alter|drop)\\s+(role|user|group)\\b") to "CREATE ROLE",
        Regex("^(create|alter|drop)\\s+(schema|database)\\b") to "CREATE SCHEMA",
        Regex("^set\\b") to "SET (incluye SET ROLE)",
        Regex("^reset\\b") to "RESET",
        Regex("^copy\\b") to "COPY",
        Regex("^table\\b") to "TABLE x",
        Regex("^values\\b") to "VALUES fuera de INSERT",
    )
    private val forbiddenAnywhere = listOf(
        Regex("\\bselect\\b") to "SELECT (ni subconsultas)",
        Regex("\\bset\\s+schema\\b") to "SET SCHEMA",
        Regex("\\bowner\\s+to\\b") to "OWNER TO",
        Regex("\\bno\\s+inherit\\b|\\binherits?\\b") to "INHERIT / INHERITS",
        Regex("\\bpartition\\s+of\\b|\\b(attach|detach)\\s+partition\\b") to "particiones (PARTITION OF, ATTACH/DETACH PARTITION)",
        Regex("\\b(enable|disable)\\s+(replica\\s+|always\\s+)?(trigger|rule)\\b") to "ENABLE/DISABLE TRIGGER",
        Regex("\\bforce\\s+row\\b|\\brow\\s+level\\s+security\\b") to "FORCE/ROW LEVEL SECURITY",
    )

    /** Motivos de rechazo del [sql] de una migracion de la extension [extensionId]; vacio si cumple. */
    fun validate(extensionId: String, sql: String): List<String> {
        val prefix = tablePrefix(extensionId)
        val raw = rawForbidden.filter { (pattern, _) -> pattern.containsMatchIn(sql) }.map { (_, label) -> "no se permite $label." }
        if (raw.isNotEmpty()) return raw

        // Quita comentarios y deja las cadenas vacias para que su texto no se confunda con SQL.
        val cleaned = tokens.replace(sql) { if (it.value.startsWith("'")) "''" else " " }
        if (dollarQuote.containsMatchIn(cleaned)) return listOf("no se permiten comillas de dolar (DO \$\$ ni cuerpos de funciones).")
        if (cleaned.replace("''", "").contains('\'')) return listOf("hay una comilla simple sin cerrar.")
        val text = cleaned.lowercase().replace(Regex("\\s+"), " ").trim()

        val problems = mutableListOf<String>()
        qualifier.findAll(text).map { it.groupValues[1] }.distinct()
            .filter { it != "public" && !it.startsWith(prefix) }
            .forEach { problems += "'$it.' no esta permitido: solo el schema public y nombres completos de tablas $prefix* (sin alias)." }

        val normalized = publicSchema.replace(text, "")
        normalized.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { checkStatement(it, prefix, problems) }
        forbiddenAnywhere.filter { (pattern, _) -> pattern.containsMatchIn(normalized) }
            .forEach { (_, label) -> problems += "$label no esta permitido en migraciones de extensiones." }
        call.findAll(normalized).map { it.groupValues[1] }.distinct()
            .filterNot { it in allowedCalls || it in keywordsBeforeParen || it.startsWith(prefix) }
            .forEach { problems += "la funcion o construccion '$it(' no esta permitida; solo tipos y funciones puras (${allowedCalls.filter { c -> c != "varying" }.sorted().joinToString(", ")})." }
        Regex("\\breferences\\s+($Name)").findAll(normalized).map { it.groupValues[1] }
            .filterNot { it.startsWith(prefix) }
            .forEach { problems += "REFERENCES $it: una clave foranea solo puede apuntar a tablas $prefix*." }
        return problems.distinct()
    }

    /** Prefijo de las tablas e indices de una extension: ext_<id>__ (doble guion bajo). */
    fun tablePrefix(extensionId: String) = "ext_${extensionId}__"

    private fun checkStatement(statement: String, prefix: String, problems: MutableList<String>) {
        forbiddenStart.firstOrNull { (pattern, _) -> pattern.containsMatchIn(statement) }?.let { (_, label) ->
            problems += "$label no esta permitido en migraciones de extensiones."
            return
        }
        if (statement == "begin" || statement == "commit") return

        fun owned(name: String, what: String) {
            if (!name.startsWith(prefix)) problems += "'${statement.take(60)}' toca $what '$name'; solo se permiten nombres $prefix*."
        }
        fun names(list: String) = list.split(',').map { it.trim().removePrefix("only ").removeSuffix(" *").trim() }.filter { it.isNotEmpty() }
        fun match(pattern: String, block: (MatchResult) -> Unit): Boolean {
            val result = Regex(pattern).find(statement) ?: return false
            block(result)
            return true
        }

        val handled =
            match("^create\\s+(?:unlogged\\s+)?table\\s+(?:if\\s+not\\s+exists\\s+)?($Name)(.*)$") {
                owned(it.groupValues[1], "la tabla")
                if (!it.groupValues[2].trim().startsWith("(")) problems += "CREATE TABLE solo con lista de columnas entre parentesis (sin AS ni herencia)."
                Regex("\\blike\\s+($Name)").find(statement)?.let { like -> owned(like.groupValues[1], "la tabla") }
            } ||
                match("^alter\\s+table\\s+(?:if\\s+exists\\s+)?(?:only\\s+)?($Name)(.*)$") {
                    owned(it.groupValues[1], "la tabla")
                    checkAlterActions(it.groupValues[2].trim(), prefix, problems)
                } ||
                match("^drop\\s+table\\s+(?:if\\s+exists\\s+)?(.+?)(?:\\s+(?:cascade|restrict))?$") {
                    names(it.groupValues[1]).forEach { table -> owned(table, "la tabla") }
                } ||
                match("^truncate\\s+(?:table\\s+)?(.+?)(?:\\s+(?:restart|continue)\\s+identity)?(?:\\s+(?:cascade|restrict))?$") {
                    names(it.groupValues[1]).forEach { table -> owned(table, "la tabla") }
                } ||
                match("^insert\\s+into\\s+($Name)(.*)$") {
                    owned(it.groupValues[1], "la tabla")
                    // Solo INSERT ... VALUES: ni TABLE x ni subconsultas.
                    if (!Regex("^(\\([^)]*\\)\\s*)?(default values\\b|values\\b).*$").matches(it.groupValues[2].trim())) {
                        problems += "INSERT solo con VALUES (sin TABLE ni subconsultas)."
                    }
                } ||
                match("^update\\s+(?:only\\s+)?($Name)") {
                    owned(it.groupValues[1], "la tabla")
                    if (Regex("\\sfrom\\s").containsMatchIn(statement)) problems += "UPDATE ... FROM no esta permitido."
                    if (Regex("\\b(table|values)\\b").containsMatchIn(statement)) problems += "UPDATE no puede usar TABLE ni VALUES."
                } ||
                match("^delete\\s+from\\s+(?:only\\s+)?($Name)") {
                    owned(it.groupValues[1], "la tabla")
                    if (Regex("\\susing\\s").containsMatchIn(statement)) problems += "DELETE ... USING no esta permitido."
                    if (Regex("\\b(table|values)\\b").containsMatchIn(statement)) problems += "DELETE no puede usar TABLE ni VALUES."
                } ||
                match("^create\\s+(?:unique\\s+)?index\\s+(?:concurrently\\s+)?(?:if\\s+not\\s+exists\\s+)?($Name)\\s+on\\s+(?:only\\s+)?($Name)") {
                    owned(it.groupValues[1], "el indice")
                    owned(it.groupValues[2], "la tabla")
                } ||
                match("^drop\\s+index\\s+(?:concurrently\\s+)?(?:if\\s+exists\\s+)?(.+?)(?:\\s+(?:cascade|restrict))?$") {
                    names(it.groupValues[1]).forEach { index -> owned(index, "el indice") }
                } ||
                match("^(?:grant|revoke)\\b.*?\\son\\s+(?:table\\s+)?(.+?)\\s+(?:to|from)\\s") {
                    names(it.groupValues[1]).forEach { table -> owned(table, "la tabla") }
                } ||
                match("^comment\\s+on\\s+(?:table|column)\\s+($Name)(?:\\.$Name)?\\s+is\\b") { owned(it.groupValues[1], "la tabla") }
        if (!handled) problems += "sentencia no permitida: '${statement.take(60)}'."
    }

    // Acciones permitidas de ALTER TABLE, separadas por comas fuera de parentesis.
    private fun checkAlterActions(actions: String, prefix: String, problems: MutableList<String>) {
        val allowed = listOf(
            Regex("^add\\s+column\\s+.+$"),
            Regex("^add\\s+constraint\\s+.+$"),
            Regex("^add\\s+(primary\\s+key|unique|check|foreign\\s+key)\\b.*$"),
            Regex("^drop\\s+column\\s+(if\\s+exists\\s+)?$Name(\\s+(cascade|restrict))?$"),
            Regex("^drop\\s+constraint\\s+(if\\s+exists\\s+)?$Name(\\s+(cascade|restrict))?$"),
            Regex("^alter\\s+(column\\s+)?$Name\\s+(set\\s+default\\s+.+|drop\\s+default|set\\s+not\\s+null|drop\\s+not\\s+null|(set\\s+data\\s+)?type\\s+.+)$"),
            Regex("^rename\\s+(column\\s+)?$Name\\s+to\\s+$Name$"),
        )
        val rename = Regex("^rename\\s+to\\s+($Name)$")
        splitTopLevel(actions).forEach { action ->
            val renameTo = rename.find(action)
            when {
                renameTo != null ->
                    if (!renameTo.groupValues[1].startsWith(prefix)) problems += "RENAME TO '${renameTo.groupValues[1]}': la tabla debe seguir llamandose $prefix*."
                // "rename to" ya se atendio; "rename column" necesita la palabra column para no confundirse con RENAME TO.
                allowed.none { it.matches(action) } || Regex("^rename\\s+to\\b").containsMatchIn(action) ->
                    problems += "ALTER TABLE: accion no permitida '${action.take(60)}' (solo ADD/DROP COLUMN, ADD/DROP CONSTRAINT, ALTER COLUMN SET/DROP DEFAULT|NOT NULL|TYPE, RENAME COLUMN)."
            }
        }
    }

    private fun splitTopLevel(text: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        val current = StringBuilder()
        text.forEach { char ->
            when {
                char == '(' -> { depth++; current.append(char) }
                char == ')' -> { depth--; current.append(char) }
                char == ',' && depth == 0 -> { parts += current.toString().trim(); current.clear() }
                else -> current.append(char)
            }
        }
        parts += current.toString().trim()
        return parts.filter { it.isNotEmpty() }
    }
}
