package com.ideasdeveloper.idc.modules.hotspot

import com.ideasdeveloper.idc.modules.hotspot.HotspotPortalPages.BasePath
import java.sql.Connection

/**
 * Custom HTML for the portal entry views (hotspot_portal_templates): the registration form and the
 * customer-code and user-code pages. A template is a whole HTML page; the portal fills its
 * {{placeholders}} (always escaped, except {{campos_ocultos}} and {{error}}, which the portal builds).
 *
 * Before saving, [validate] checks that the page still works with the portal: one form posting to the
 * view's address with the fields the backend reads, {{campos_ocultos}} inside it (the device and
 * network the guest comes from) and {{error}} somewhere. Scripts, event handlers, javascript:/vbscript:/data: links
 * (entities decoded first), frames, svg, math, link, ambiguous comments and forms posting elsewhere are rejected, checked on
 * the whole HTML (a comment hides nothing): the page is served to every guest on the WiFi. The portal also sends a
 * Content-Security-Policy ([HotspotPortalPages.ContentSecurityPolicy]) as a second barrier.
 */
object HotspotTemplates {
    const val MaxLength = 100_000

    /** A view that can be customized, with the form it must keep. */
    data class View(
        val id: String,
        val label: String,
        val action: String,
        // Fields the backend reads, by name, that must be inside the form.
        val requiredFields: List<String>,
        val placeholders: List<String>,
    )

    // Every view can use these; values come from Configuracion and the company's colors.
    private val common = listOf(
        "titulo", "subtitulo", "red", "logo_url", "pie", "color_primario", "color_secundario", "color_texto",
        "error", "campos_ocultos",
    )

    val Form = View(
        id = HotspotNetworks.Form,
        label = "Formulario",
        action = "$BasePath/register",
        requiredFields = listOf("name", "email", "phone", "terms"),
        placeholders = common + listOf("nombre", "correo", "celular", "cumpleanos", "consentimiento", "terminos_url", "privacidad_url"),
    )
    val CustomerCode = View(
        id = HotspotNetworks.CustomerCode,
        label = "Codigo de cliente",
        action = "$BasePath/code",
        requiredFields = listOf("code"),
        placeholders = common + "tipo_codigo",
    )
    val UserCode = CustomerCode.copy(id = HotspotNetworks.UserCode, label = "Codigo de usuario")

    val views = listOf(Form, CustomerCode, UserCode)

    fun view(id: String): View? = views.firstOrNull { it.id == id }

    // ---- Validation ----

    // The tag as the browser reads it (a quoted value may hold a ">").
    // Possessive quantifiers: no backtracking, so a huge run of spaces or attributes cannot make it quadratic.
    private val tagPattern = Regex("<\\s*+(/?+)\\s*+([a-zA-Z][a-zA-Z0-9:-]*+)((?:\"[^\"]*+\"|'[^']*+'|[^'\">]++)*+)>")

    // Conservative reading: up to the first ">" (quotes ignored) and even without it. Next to [tagPattern], a stray quote cannot
    // hide a tag from the safety checks.
    private val looseTagPattern = Regex("<\\s*+(/?+)\\s*+([a-zA-Z][a-zA-Z0-9:-]*+)([^>]*+)>?+")
    private val attributePattern = Regex("([^\\s\"'>/=]++)(?:\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s\"'>]+))?")
    private val placeholderPattern = Regex("\\{\\{\\s*([a-zA-Z_]+)\\s*}}")
    private val styleBlockPattern = Regex("<\\s*style\\b[^>]*>(.*?)(?:<\\s*/\\s*style|$)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    // svg, math and link are not used by the example templates; style is (colors), so it stays and its content is checked.
    private val forbiddenTags = setOf("script", "iframe", "frame", "frameset", "object", "embed", "base", "applet", "svg", "math", "link")
    private val urlAttributes = setOf(
        "href", "src", "action", "formaction", "xlink:href", "data", "poster", "background", "ping", "cite", "icon", "manifest", "dynsrc", "lowsrc",
    )

    // Not allowed at the start of a URL: it runs code or loads a document the portal did not write.
    private val dangerousSchemes = listOf("javascript:", "vbscript:", "data:")

    private class Tag(
        val closing: Boolean,
        val name: String,
        // The first value wins, like in the browser (a repeated attribute is ignored).
        val attributes: Map<String, String>,
        // Every attribute in order, repeated ones included, with its value as written (no quotes).
        val attributeList: List<Pair<String, String>>,
        val start: Int,
        val end: Int,
    )

    private fun tags(html: String, pattern: Regex = tagPattern): List<Tag> = pattern.findAll(html).map { match ->
        val list = attributePattern.findAll(match.groupValues[3]).map { attribute ->
            attribute.groupValues[1].lowercase() to attribute.groupValues[2].trim().removeSurrounding("\"").removeSurrounding("'")
        }.toList()
        val first = LinkedHashMap<String, String>()
        list.forEach { (name, value) -> first.putIfAbsent(name, value) }
        Tag(match.groupValues[1] == "/", match.groupValues[2].lowercase(), first, list, match.range.first, match.range.last + 1)
    }.toList()

    /**
     * The page without its comments, closing them like the browser does (a comment never closed runs to the end of the page), and
     * the problems of ambiguous comments: "<!-->" and "<!--->" are empty comments for the browser and "--!>" also ends one. Those
     * templates are rejected, so a tag never hides behind a "comment" that the validator and the browser read differently.
     */
    fun withoutComments(html: String): Pair<String, List<String>> {
        val problems = mutableListOf<String>()
        val out = StringBuilder()
        var index = 0
        while (index < html.length) {
            val open = html.indexOf("<!--", index)
            if (open < 0) {
                out.append(html, index, html.length)
                break
            }
            out.append(html, index, open)
            val body = open + 4
            if (html.startsWith(">", body) || html.startsWith("->", body)) {
                problems += "No se permiten los comentarios <!--> ni <!--->: el navegador los cierra de inmediato."
                index = body + (if (html.startsWith(">", body)) 1 else 2)
                continue
            }
            val plain = html.indexOf("-->", body)
            val bang = html.indexOf("--!>", body)
            val close = listOf(plain, bang).filter { it >= 0 }.minOrNull()
            if (close == null) {
                problems += "Hay un comentario <!-- sin cerrar con -->: el navegador lo extiende hasta el final de la pagina."
                break
            }
            if (close == bang) problems += "No se permite cerrar un comentario con --!>: usa -->."
            index = close + (if (close == bang) 4 else 3)
        }
        return out.toString() to problems.distinct()
    }

    private val namedEntities = mapOf(
        "colon" to ":", "tab" to "\t", "newline" to "\n", "amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">",
        "sol" to "/", "lpar" to "(", "rpar" to ")", "semi" to ";", "nbsp" to "\u00A0",
    )
    private val entityPattern = Regex("&#[xX]([0-9a-fA-F]+);?|&#([0-9]+);?|&([a-zA-Z][a-zA-Z0-9]*);?")

    /** Decodes the HTML entities of an attribute value once, like the browser (numeric ones with or without ";"). */
    fun decodeEntities(value: String): String = entityPattern.replace(value) { match ->
        val hex = match.groups[1]?.value
        val decimal = match.groups[2]?.value
        val named = match.groups[3]?.value
        if (named != null) {
            namedEntities[named.lowercase()] ?: match.value
        } else {
            val code = (if (hex != null) hex.toLongOrNull(16) else decimal?.toLongOrNull()) ?: -1L
            if (code in 1..0x10FFFF && code !in 0xD800..0xDFFF) String(Character.toChars(code.toInt())) else "\uFFFD"
        }
    }

    /** The dangerous scheme ("javascript:", "vbscript:", "data:") a URL starts with once the browser reads it, or null. */
    fun dangerousScheme(value: String): String? {
        // Entities decoded; spaces, tabs, line breaks and control characters out, because the browser ignores them inside the scheme.
        val clean = decodeEntities(value).filter { it > ' ' && !it.isWhitespace() && it != '\u007F' }.lowercase()
        return dangerousSchemes.firstOrNull { clean.startsWith(it) }
    }

    private val cssComment = Regex("/\\*.*?(\\*/|$)", RegexOption.DOT_MATCHES_ALL)
    private val cssEscape = Regex("\\\\([0-9a-fA-F]{1,6})\\s?|\\\\(.)", RegexOption.DOT_MATCHES_ALL)
    private val cssUrl = Regex("url\\(([^)]*)\\)?")

    /** What is wrong in a piece of CSS (a <style> block or a style= attribute) that runs code or loads a dangerous address; null when fine. */
    fun cssProblem(css: String): String? {
        val unescaped = cssEscape.replace(css.replace(cssComment, "")) { escape ->
            val code = escape.groups[1]?.value?.toIntOrNull(16)
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else escape.groups[2]?.value.orEmpty()
        }
        val clean = unescaped.filter { it > ' ' && !it.isWhitespace() }.lowercase()
        if ("expression(" in clean || "-moz-binding" in clean || "behavior:" in clean || "@import" in clean) {
            return "No se permiten expression(, behavior, -moz-binding ni @import en los estilos."
        }
        cssUrl.findAll(clean).forEach { match ->
            val address = match.groupValues[1].trim('"', '\'')
            if (dangerousSchemes.any { address.startsWith(it) } && !address.startsWith("data:image/")) {
                return "No se permiten direcciones ${address.substringBefore(':')}: dentro de url() en los estilos."
            }
        }
        return null
    }

    /** Problems that keep [html] from working as [view]; empty when it can be saved. */
    fun validate(view: View, html: String): List<String> {
        // Too long: nothing else runs (no pattern is evaluated over a huge text).
        if (html.length > MaxLength) return listOf("El HTML no puede pasar de $MaxLength caracteres.")
        return try {
            check(view, html)
        } catch (overflow: StackOverflowError) {
            listOf("La plantilla tiene una etiqueta demasiado larga.")
        }
    }

    // Placeholders the portal fills with HTML it built: never allowed inside a tag, not even as a whole value.
    private val rawPlaceholders = setOf("campos_ocultos", "error")
    private val wholeValuePlaceholder = Regex("=\\s*+([\"'])\\{\\{\\s*+([a-zA-Z_]++)\\s*+}}\\1")

    /** Safety problems of [html] (all of it: a comment hides nothing, the browser reads "<!-->" as closed). */
    private fun safetyProblems(view: View, html: String): List<String> {
        val problems = mutableListOf<String>()
        // Read in two ways so a stray quote cannot hide a tag.
        val scanned = (tags(html) + tags(html, looseTagPattern)).filter { !it.closing }
        scanned.map { it.name }.filter { it in forbiddenTags }.distinct().forEach {
            problems += "No se permite la etiqueta <$it>: el portal no ejecuta scripts ni incrusta otras paginas."
        }
        scanned.forEach { tag ->
            tag.attributeList.map { it.first }.firstOrNull { it.startsWith("on") }?.let { problems += "No se permiten eventos como $it= en <${tag.name}>." }
            tag.attributeList.filter { it.first in urlAttributes }.firstNotNullOfOrNull { dangerousScheme(it.second) }
                ?.let { problems += "No se permiten direcciones $it en <${tag.name}>." }
            tag.attributeList.firstOrNull { it.first == "style" }?.let { cssProblem(decodeEntities(it.second)) }?.let { problems += it }
            if (tag.name == "meta" && decodeEntities(tag.attributes["http-equiv"].orEmpty()).trim().lowercase() == "refresh") {
                problems += "No se permite <meta http-equiv=\"refresh\">."
            }
            if ("formaction" in tag.attributes) problems += "No se permite formaction en <${tag.name}>."
            // A {{marker}} that builds part of a name or value could assemble "<script>" or "javascript:" once filled.
            val raw = html.substring(tag.start, tag.end)
            val rest = wholeValuePlaceholder.replace(raw) { if (it.groupValues[2] in rawPlaceholders) it.value else "=\"\"" }
            if ("{{" in rest) {
                problems += "No se permiten {{marcadores}} dentro de una etiqueta (<${tag.name}>), salvo como valor completo entre comillas, por ejemplo value=\"{{nombre}}\"."
            }
        }
        styleBlockPattern.findAll(html).forEach { block -> cssProblem(block.groupValues[1])?.let { problems += it } }
        val foreign = scanned.filter { it.name == "form" && normalizePath(it.attributes["action"].orEmpty()) != view.action }.distinctBy { it.start }
        if (foreign.isNotEmpty()) {
            problems += "Todos los formularios deben enviar a ${view.action}; hay ${foreign.size} que envia(n) a otra direccion."
        }
        return problems
    }

    private fun check(view: View, html: String): List<String> {
        val problems = mutableListOf<String>()
        // Only the check of the required fields and markers ignores what is commented out; safety looks at everything.
        val (code, commentProblems) = withoutComments(html)
        problems += commentProblems
        problems += safetyProblems(view, html)
        // The portal fills the {{markers}} afterwards: what it serves (markers empty) must be safe too.
        problems += safetyProblems(view, render(html, emptyMap(), emptyMap()))

        val tags = tags(code)
        val forms = tags.filter { !it.closing && it.name == "form" }
        val form = forms.firstOrNull { normalizePath(it.attributes["action"].orEmpty()) == view.action }
        if (form == null) {
            problems += "Falta el formulario: <form action=\"${view.action}\" method=\"post\">."
        } else {
            if (forms.count { normalizePath(it.attributes["action"].orEmpty()) == view.action } > 1) problems += "Debe haber un solo formulario."
            if (form.attributes["method"]?.lowercase() != "post") problems += "El formulario debe usar method=\"post\"."
            val close = tags.firstOrNull { it.closing && it.name == "form" && it.start > form.end }
            if (close == null) problems += "Falta cerrar el formulario con </form>."
            val inside = tags.filter { !it.closing && it.start > form.end && (close == null || it.start < close.start) }
            val named = inside.filter { it.name in setOf("input", "select", "textarea") }.groupBy { it.attributes["name"].orEmpty() }
            view.requiredFields.forEach { field ->
                if (field !in named) problems += "Falta dentro del formulario el campo name=\"$field\"."
            }
            if (view == Form) {
                val terms = named["terms"]?.firstOrNull()
                if (terms != null && (terms.attributes["type"]?.lowercase() != "checkbox" || terms.attributes["value"] != "si")) {
                    problems += "El campo terms debe ser <input type=\"checkbox\" name=\"terms\" value=\"si\">."
                }
            }
            val hidden = placeholderPattern.findAll(code).firstOrNull { it.groupValues[1] == "campos_ocultos" }
            if (hidden == null) {
                problems += "Falta {{campos_ocultos}} dentro del formulario (identifica el dispositivo y la red)."
            } else if (hidden.range.first < form.end || (close != null && hidden.range.first > close.start)) {
                problems += "{{campos_ocultos}} debe ir dentro del formulario."
            }
        }

        val used = placeholderPattern.findAll(code).map { it.groupValues[1] }.toSet()
        if ("error" !in used) problems += "Falta {{error}} para mostrar los mensajes (codigo incorrecto, datos invalidos...)."
        (used - view.placeholders.toSet()).sorted().forEach { problems += "{{$it}} no existe en esta vista." }
        return problems.distinct()
    }

    private fun normalizePath(action: String): String = action.trim().substringBefore('?').removeSuffix("/")

    // ---- Rendering ----

    /** [template] with its placeholders filled; [raw] values are HTML the portal built, the rest is escaped. */
    fun render(template: String, values: Map<String, String>, raw: Map<String, String>): String =
        placeholderPattern.replace(template) { match ->
            val key = match.groupValues[1]
            raw[key] ?: values[key]?.let(HotspotPortalPages::esc) ?: ""
        }

    // ---- Storage ----

    fun load(connection: Connection): Map<String, String> = connection.prepareStatement(
        "SELECT view, html FROM hotspot_portal_templates"
    ).use { query -> query.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getString(1), rows.getString(2)) } } }

    /** Saves the template of [view]; a blank one goes back to the built-in design. */
    fun save(connection: Connection, view: View, html: String) {
        if (html.isBlank()) {
            connection.prepareStatement("DELETE FROM hotspot_portal_templates WHERE view = ?").use { delete ->
                delete.setString(1, view.id)
                delete.executeUpdate()
            }
            return
        }
        connection.prepareStatement(
            """
            INSERT INTO hotspot_portal_templates (view, html) VALUES (?, ?)
            ON CONFLICT (view) DO UPDATE SET html = EXCLUDED.html, updated_at = CURRENT_TIMESTAMP
            """.trimIndent()
        ).use { upsert ->
            upsert.setString(1, view.id)
            upsert.setString(2, html)
            upsert.executeUpdate()
        }
    }

    /** A starting point for [view] that passes [validate]. */
    fun example(view: View): String {
        val fields = if (view == Form) {
            """
                <input name="name" placeholder="Nombre" value="{{nombre}}" required>
                <input name="email" type="email" placeholder="Correo" value="{{correo}}" required>
                <input name="phone" type="tel" placeholder="Celular" value="{{celular}}" required>
                <label><input type="checkbox" name="terms" value="si" required> {{consentimiento}}</label>
            """.trimIndent()
        } else {
            """<input name="code" placeholder="{{tipo_codigo}}" autocomplete="off" required>"""
        }
        return """
            <!DOCTYPE html>
            <html lang="es">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>{{titulo}}</title>
              <style>
                body { margin: 0; font-family: system-ui, sans-serif; background: {{color_primario}}; color: {{color_texto}}; }
                main { max-width: 420px; margin: 40px auto; padding: 24px; background: #fff; color: #222; border-radius: 16px; }
                input, button { display: block; width: 100%; margin-top: 12px; padding: 12px; font-size: 16px; }
                button { background: {{color_primario}}; color: {{color_texto}}; border: 0; border-radius: 999px; }
              </style>
            </head>
            <body>
              <main>
                <h1>{{titulo}}</h1>
                <p>Red: {{red}}</p>
                {{error}}
                <form action="${view.action}" method="post">
            ${fields.prependIndent("        ")}
                  {{campos_ocultos}}
                  <button type="submit">Conectarme</button>
                </form>
              </main>
            </body>
            </html>
        """.trimIndent()
    }
}
