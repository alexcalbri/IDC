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
 * network the guest comes from) and {{error}} somewhere. Scripts, event handlers, javascript: links,
 * frames and forms posting elsewhere are rejected: the page is served to every guest on the WiFi.
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

    private val commentPattern = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val tagPattern = Regex("<\\s*(/?)\\s*([a-zA-Z][a-zA-Z0-9-]*)((?:\"[^\"]*\"|'[^']*'|[^'\">])*)>")
    private val attributePattern = Regex("([^\\s\"'>/=]+)(?:\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s\"'>]+))?")
    private val placeholderPattern = Regex("\\{\\{\\s*([a-zA-Z_]+)\\s*}}")
    private val forbiddenTags = setOf("script", "iframe", "frame", "frameset", "object", "embed", "base", "applet")
    private val urlAttributes = setOf("href", "src", "action", "formaction", "xlink:href", "data", "poster", "background")

    private class Tag(val closing: Boolean, val name: String, val attributes: Map<String, String>, val start: Int, val end: Int)

    private fun tags(html: String): List<Tag> = tagPattern.findAll(html).map { match ->
        val attributes = attributePattern.findAll(match.groupValues[3]).associate { attribute ->
            attribute.groupValues[1].lowercase() to attribute.groupValues[2].trim().removeSurrounding("\"").removeSurrounding("'")
        }
        Tag(match.groupValues[1] == "/", match.groupValues[2].lowercase(), attributes, match.range.first, match.range.last + 1)
    }.toList()

    /** Problems that keep [html] from working as [view]; empty when it can be saved. */
    fun validate(view: View, html: String): List<String> {
        val problems = mutableListOf<String>()
        if (html.length > MaxLength) problems += "El HTML no puede pasar de $MaxLength caracteres."
        // Commented-out markup does not count as present.
        val code = html.replace(commentPattern, "")
        val tags = tags(code)

        tags.filter { !it.closing && it.name in forbiddenTags }.map { it.name }.distinct().forEach {
            problems += "No se permite la etiqueta <$it>: el portal no ejecuta scripts ni incrusta otras paginas."
        }
        tags.filter { !it.closing }.forEach { tag ->
            tag.attributes.keys.firstOrNull { it.startsWith("on") }?.let { problems += "No se permiten eventos como $it= en <${tag.name}>." }
            tag.attributes.filterKeys { it in urlAttributes }.values.firstOrNull { it.replace(Regex("\\s"), "").lowercase().startsWith("javascript:") }
                ?.let { problems += "No se permiten direcciones javascript: en <${tag.name}>." }
            if (tag.name == "meta" && tag.attributes["http-equiv"]?.lowercase() == "refresh") problems += "No se permite <meta http-equiv=\"refresh\">."
            if ("formaction" in tag.attributes) problems += "No se permite formaction en <${tag.name}>."
        }

        val forms = tags.filter { !it.closing && it.name == "form" }
        val foreign = forms.filter { normalizePath(it.attributes["action"].orEmpty()) != view.action }
        if (foreign.isNotEmpty()) {
            problems += "Todos los formularios deben enviar a ${view.action}; hay ${foreign.size} que envia(n) a otra direccion."
        }
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
