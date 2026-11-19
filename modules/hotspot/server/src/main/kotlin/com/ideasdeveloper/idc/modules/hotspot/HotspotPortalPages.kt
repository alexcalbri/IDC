package com.ideasdeveloper.idc.modules.hotspot

import java.security.MessageDigest
import java.util.Base64

// Captive portal HTML. Every dynamic value goes through esc(); colors were validated as #RRGGBB.
object HotspotPortalPages {
    const val BasePath = "/guest/s/default"

    data class GuestContext(val mac: String, val apMac: String, val ssid: String, val continueUrl: String)

    data class FormValues(
        val name: String = "",
        val email: String = "",
        val phone: String = "",
        val birthday: String = "",
    )

    fun form(settings: HotspotSettings, guest: GuestContext, values: FormValues = FormValues(), error: String? = null): String =
        custom(settings, HotspotTemplates.Form, guest, error, mapOf(
            "nombre" to values.name, "correo" to values.email, "celular" to values.phone, "cumpleanos" to values.birthday,
            "consentimiento" to settings.consentText, "terminos_url" to settings.termsUrl.orEmpty(), "privacidad_url" to settings.privacyUrl.orEmpty(),
        )) ?: builtInForm(settings, guest, values, error)

    /**
     * The company's own HTML for [view] (see [HotspotTemplates]) with its placeholders filled, or null
     * to use the built-in design.
     */
    private fun custom(settings: HotspotSettings, view: HotspotTemplates.View, guest: GuestContext, error: String?, extra: Map<String, String>): String? {
        val template = settings.templates[view.id] ?: return null
        val values = mapOf(
            "titulo" to settings.portalTitle,
            "subtitulo" to settings.portalSubtitle,
            "red" to guest.ssid.ifBlank { settings.networkName },
            "logo_url" to settings.logoUrl.orEmpty(),
            "pie" to settings.footerText,
            "color_primario" to settings.brand.primary,
            "color_secundario" to settings.brand.secondary,
            "color_texto" to settings.brand.onPrimary,
            "tipo_codigo" to view.label,
        ) + extra
        val raw = mapOf(
            "campos_ocultos" to guestFields(guest),
            "error" to error?.let { "<div class=\"idc-error\" role=\"alert\">${esc(it)}</div>" }.orEmpty(),
        )
        return HotspotTemplates.render(template, values, raw)
    }

    private fun builtInForm(settings: HotspotSettings, guest: GuestContext, values: FormValues, error: String?): String = page(
        settings,
        header = networkHeader(settings, guest),
        body = """
            <form action="$BasePath/register" method="POST">
              <h2>Conectate ahora</h2>
              <p class="muted">Dejanos tus datos y te enviaremos un codigo de acceso a tu correo.</p>
              ${error?.let { "<div class=\"error\">${esc(it)}</div>" }.orEmpty()}
              <label for="name">Nombre</label>
              <input id="name" name="name" type="text" autocomplete="name" maxlength="60" required value="${esc(values.name)}">
              <label for="email">Correo electronico</label>
              <input id="email" name="email" type="email" autocomplete="email" maxlength="120" required value="${esc(values.email)}">
              <label for="phone">Celular</label>
              <input id="phone" name="phone" type="tel" autocomplete="tel" maxlength="20" required value="${esc(values.phone)}">
              <label for="birthday">Cumpleanos <span class="optional">(opcional)</span></label>
              <input id="birthday" name="birthday" type="date" value="${esc(values.birthday)}">
              <label class="consent">
                <input type="checkbox" name="terms" value="si" required>
                <span>${esc(settings.consentText)}${legalLinks(settings)}</span>
              </label>
              ${guestFields(guest)}
              <button type="submit" data-busy="Enviando codigo...">Enviar codigo</button>
            </form>
        """.trimIndent(),
    )

    // Networks configured for access by code ask for the customer code (Clientes) or the user code
    // (Usuarios integration) instead of the form.
    fun accessCode(settings: HotspotSettings, guest: GuestContext, byUser: Boolean = false, error: String? = null): String {
        custom(settings, if (byUser) HotspotTemplates.UserCode else HotspotTemplates.CustomerCode, guest, error, emptyMap())?.let { return it }
        val label = if (byUser) "Codigo de usuario" else "Codigo de cliente"
        return page(
            settings,
            header = networkHeader(settings, guest),
            body = """
                <form action="$BasePath/code" method="POST">
                  <h2>Ingresa tu codigo</h2>
                  <p class="muted">Escribe tu ${label.lowercase()} para conectarte a esta red.</p>
                  ${error?.let { "<div class=\"error\">${esc(it)}</div>" }.orEmpty()}
                  <label for="access_code">$label</label>
                  <input id="access_code" name="code" type="text" autocomplete="off" autocapitalize="characters" spellcheck="false" maxlength="12" required class="code">
                  ${guestFields(guest)}
                  <button type="submit" data-busy="Conectando...">Conectarme al WiFi</button>
                </form>
            """.trimIndent(),
        )
    }

    fun code(settings: HotspotSettings, pendingId: String, email: String, continueUrl: String, error: String? = null): String = page(
        settings,
        header = "<h1>${esc(settings.portalTitle)}</h1>",
        body = """
            <form action="$BasePath/verify" method="POST">
              <h2>Revisa tu correo</h2>
              <p class="muted">Enviamos un codigo de 6 digitos a <strong>${esc(maskEmail(email))}</strong>. Vence en ${settings.codeTtlMinutes} minutos.</p>
              ${error?.let { "<div class=\"error\">${esc(it)}</div>" }.orEmpty()}
              <label for="code">Codigo</label>
              <input id="code" name="code" type="text" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" maxlength="6" required class="code">
              <input type="hidden" name="pending" value="${esc(pendingId)}">
              <input type="hidden" name="url" value="${esc(continueUrl)}">
              <button type="submit" data-busy="Conectando...">Conectarme al WiFi</button>
            </form>
            <p class="small"><a href="$BasePath/">Volver a empezar</a></p>
        """.trimIndent(),
    )

    /**
     * [continueUrl] opens automatically after a few seconds; the button lets the guest go right away.
     * [showDuration] is for form access only: code accesses last as long as the customer's stay or
     * until a user is revoked, so a fixed length would be wrong.
     */
    fun success(settings: HotspotSettings, name: String, continueUrl: String?, showDuration: Boolean = true): String = page(
        settings,
        header = "<h1>${esc(settings.portalTitle)}</h1>",
        head = continueUrl?.let { "<meta http-equiv=\"refresh\" content=\"$RedirectSeconds;url=${esc(it)}\">" }.orEmpty(),
        body = """
            <div class="center">
              <div class="check"><svg viewBox="0 0 24 24"><polyline points="20 6 9 17 4 12"></polyline></svg></div>
              <h2>Estas conectado</h2>
              <p>Bienvenido, ${esc(name)}. Ya puedes navegar.</p>
              ${if (showDuration && settings.accessMinutes > 0) "<p class=\"note\">Tu sesion de WiFi dura <strong>${settings.accessMinutes} minutos</strong>.</p>" else ""}
              ${continueUrl?.let { "<p class=\"small\">Te llevaremos a la pagina en unos segundos.</p><a class=\"button\" href=\"${esc(it)}\">Continuar</a>" }.orEmpty()}
            </div>
        """.trimIndent(),
    )

    /**
     * A device that already had access on a shared network, continued without logging in again.
     * [minutesLeft] null means the access does not expire.
     */
    fun welcomeBack(settings: HotspotSettings, name: String, minutesLeft: Int?, continueUrl: String?): String = page(
        settings,
        header = "<h1>${esc(settings.portalTitle)}</h1>",
        head = continueUrl?.let { "<meta http-equiv=\"refresh\" content=\"$RedirectSeconds;url=${esc(it)}\">" }.orEmpty(),
        body = """
            <div class="center">
              <div class="check"><svg viewBox="0 0 24 24"><polyline points="20 6 9 17 4 12"></polyline></svg></div>
              <h2>Bienvenido de nuevo</h2>
              <p>${esc(name.ifBlank { "Hola" })}, ya tenias acceso a esta red. Puedes seguir navegando.</p>
              ${minutesLeft?.let { "<p class=\"note\">Te quedan <strong>${timeLeft(it.toLong())}</strong> de tu sesion de WiFi.</p>" }.orEmpty()}
              ${continueUrl?.let { "<p class=\"small\">Te llevaremos a la pagina en unos segundos.</p><a class=\"button\" href=\"${esc(it)}\">Continuar</a>" }.orEmpty()}
            </div>
        """.trimIndent(),
    )

    // Exact time left (formatMinutes rounds up, which would promise more than the guest has).
    fun timeLeft(minutes: Long): String {
        val days = minutes / 1440
        val hours = minutes % 1440 / 60
        val rest = minutes % 60
        return listOfNotNull(
            days.takeIf { it > 0 }?.let { "$it dia${if (it == 1L) "" else "s"}" },
            hours.takeIf { it > 0 }?.let { "$it h" },
            rest.takeIf { it > 0 || minutes == 0L }?.let { "$it min" },
        ).joinToString(" ")
    }

    fun wait(settings: HotspotSettings, minutesRemaining: Long, byCode: Boolean = false): String = message(
        settings,
        title = "Ya usaste tu acceso",
        text = "${if (byCode) "Este codigo" else "Este correo"} ya se conecto recientemente. Podras volver a conectarte en ${formatMinutes(minutesRemaining)}.",
    )

    fun message(settings: HotspotSettings?, title: String, text: String, retry: Boolean = true): String = page(
        settings,
        header = "<h1>${esc(settings?.portalTitle ?: "WiFi")}</h1>",
        body = """
            <div class="center">
              <h2>${esc(title)}</h2>
              <p>${esc(text)}</p>
              ${if (retry) "<p class=\"small\"><a href=\"$BasePath/\">Volver a empezar</a></p>" else ""}
            </div>
        """.trimIndent(),
    )

    // The network shown is the SSID the guest joined, or the configured name when UniFi did not send it.
    private fun networkHeader(settings: HotspotSettings, guest: GuestContext) = """
        <h1>${esc(settings.portalTitle)}</h1>
        ${settings.portalSubtitle.takeIf { it.isNotBlank() }?.let { "<p class=\"lead\">${esc(it)}</p>" }.orEmpty()}
        <div class="network">${WifiIcon} Red: ${esc(guest.ssid.ifBlank { settings.networkName })}</div>
    """.trimIndent()

    private fun guestFields(guest: GuestContext) = listOf(
        "id" to guest.mac,
        "ap" to guest.apMac,
        "ssid" to guest.ssid,
        "url" to guest.continueUrl,
    ).joinToString("\n") { (name, value) -> "<input type=\"hidden\" name=\"$name\" value=\"${esc(value)}\">" }

    private fun legalLinks(settings: HotspotSettings): String {
        val links = listOfNotNull(
            settings.termsUrl?.let { "<a href=\"${esc(it)}\" target=\"_blank\" rel=\"noopener\">Terminos</a>" },
            settings.privacyUrl?.let { "<a href=\"${esc(it)}\" target=\"_blank\" rel=\"noopener\">Privacidad</a>" },
        )
        return if (links.isEmpty()) "" else " " + links.joinToString(" · ")
    }

    private fun page(settings: HotspotSettings?, header: String, body: String, head: String = ""): String {
        // The company's colors (IDC's when it has not set its own or the portal was not found).
        val brand = settings?.brand ?: PortalBrand.Idc
        val primary = brand.primary
        val secondary = brand.secondary
        val onPrimary = brand.onPrimary
        val logo = settings?.logoUrl?.let { "<img class=\"logo\" src=\"${esc(it)}\" alt=\"\">" }.orEmpty()
        val footer = settings?.footerText?.takeIf { it.isNotBlank() }?.let { "<footer>${esc(it)}</footer>" }.orEmpty()
        return """
            <!DOCTYPE html>
            <html lang="es">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="robots" content="noindex">
            <title>${esc(settings?.portalTitle ?: "WiFi")}</title>
            $head
            <style>
            :root { --primary: $primary; --secondary: $secondary; --on-primary: $onPrimary; --ink: #1a1815; --muted: #6d6965; --line: #e5e2df; --panel: #f4f2f0; }
            * { box-sizing: border-box; }
            body { margin: 0; min-height: 100vh; background: linear-gradient(135deg, var(--primary), var(--secondary)) fixed; color: var(--ink);
                   font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; display: flex; justify-content: center; }
            .wrap { width: 100%; max-width: 430px; padding: 0 20px 40px; display: flex; flex-direction: column; }
            header { text-align: center; padding: 36px 0 24px; color: var(--on-primary); }
            .logo { max-height: 120px; max-width: 80%; margin-bottom: 16px; }
            h1 { font-size: 34px; line-height: 1; margin: 0; text-transform: uppercase; font-weight: 900; }
            .lead { margin: 12px 0 0; font-size: 15px; opacity: .9; }
            .network { display: inline-flex; align-items: center; gap: 8px; margin-top: 16px; padding: 8px 18px; border-radius: 999px;
                       border: 1px solid currentColor; font-size: 13px; font-weight: 700; text-transform: uppercase; }
            .network svg { width: 16px; height: 16px; }
            main { background: #fff; border-radius: 16px; padding: 28px 24px; box-shadow: 0 12px 30px rgba(0,0,0,.18); }
            h2 { margin: 0 0 6px; font-size: 24px; text-transform: uppercase; font-weight: 900; }
            .muted, .small, .optional { color: var(--muted); }
            .muted { font-size: 14px; margin: 0 0 18px; }
            label { display: block; font-size: 12px; font-weight: 800; text-transform: uppercase; letter-spacing: .06em; margin: 14px 0 6px; }
            .optional { font-weight: 500; text-transform: none; letter-spacing: 0; }
            input[type=text], input[type=email], input[type=tel], input[type=date] {
                width: 100%; padding: 14px 15px; font-size: 16px; border: 2px solid var(--line); border-radius: 10px; font-family: inherit; }
            input:focus { border-color: var(--ink); outline: none; }
            .code { text-align: center; font-size: 28px !important; letter-spacing: .4em; }
            .consent { display: flex; gap: 10px; align-items: flex-start; text-transform: none; letter-spacing: 0; font-weight: 400;
                       font-size: 13px; line-height: 1.45; background: var(--panel); border: 2px solid var(--line); border-radius: 10px; padding: 12px; }
            .consent input { width: 20px; height: 20px; flex: none; margin: 0; accent-color: var(--primary); }
            a { color: var(--primary); font-weight: 700; }
            button, .button { display: flex; align-items: center; justify-content: center; width: 100%; height: 56px; margin-top: 20px;
                              border: 0; border-radius: 999px; background: var(--primary); color: var(--on-primary); font-size: 17px;
                              font-weight: 800; text-transform: uppercase; cursor: pointer; text-decoration: none; }
            button:disabled { opacity: .75; cursor: progress; }
            .spinner { width: 20px; height: 20px; margin-right: 10px; border: 3px solid currentColor; border-right-color: transparent;
                       border-radius: 50%; animation: spin .8s linear infinite; }
            @keyframes spin { to { transform: rotate(360deg); } }
            .error { background: #fdecec; color: #8a1020; border-radius: 10px; padding: 12px 14px; font-size: 14px; margin-bottom: 6px; }
            .center { text-align: center; }
            .check { width: 74px; height: 74px; margin: 0 auto 16px; background: var(--primary); border-radius: 50%;
                     display: flex; align-items: center; justify-content: center; }
            .check svg { width: 38px; height: 38px; stroke: var(--on-primary); stroke-width: 3.5; fill: none; stroke-linecap: round; stroke-linejoin: round; }
            .note { font-size: 13px; background: var(--panel); border-radius: 10px; padding: 12px 14px; }
            .small { font-size: 13px; text-align: center; margin-top: 16px; }
            footer { text-align: center; padding-top: 24px; color: var(--on-primary); opacity: .8; font-size: 12px; }
            </style>
            </head>
            <body>
            <div class="wrap">
            <header>$logo$header</header>
            <main>$body</main>
            $footer
            </div>
            <script>$SubmitOnceScriptText</script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun maskEmail(email: String): String {
        val user = email.substringBefore('@')
        val domain = email.substringAfter('@', "")
        val visible = user.take(2)
        return visible + "*".repeat((user.length - visible.length).coerceIn(1, 6)) + "@" + domain
    }

    private fun formatMinutes(minutes: Long): String = when {
        minutes < 60 -> "$minutes minuto${if (minutes == 1L) "" else "s"}"
        minutes < 1440 -> {
            val hours = (minutes + 59) / 60
            "$hours hora${if (hours == 1L) "" else "s"}"
        }
        else -> {
            val days = (minutes + 1439) / 1440
            "$days dia${if (days == 1L) "" else "s"}"
        }
    }

    fun esc(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(char)
            }
        }
    }

    private const val RedirectSeconds = 3

    // Disables the submit button after the first click and shows a spinner, so a slow UniFi
    // answer cannot send the same code twice. A page restored from the back cache is re-enabled.
    private const val SubmitOnceScript = """
document.querySelectorAll('form').forEach(function (form) {
  form.addEventListener('submit', function (event) {
    var button = form.querySelector('button[type=submit]');
    if (!button) return;
    if (button.disabled) { event.preventDefault(); return; }
    button.dataset.label = button.textContent;
    button.disabled = true;
    button.innerHTML = '<span class="spinner"></span>';
    button.appendChild(document.createTextNode(button.dataset.busy || 'Enviando...'));
  });
});
window.addEventListener('pageshow', function (event) {
  if (!event.persisted) return;
  document.querySelectorAll('button[data-label]').forEach(function (button) {
    button.disabled = false;
    button.textContent = button.dataset.label;
  });
});
"""

    // The exact text of the only inline script of the portal pages (line breaks normalized, so its hash is stable).
    private val SubmitOnceScriptText: String = SubmitOnceScript.replace("\r\n", "\n").replace("\r", "\n")

    /**
     * Content-Security-Policy of every portal answer: defense in depth against HTML injected by a custom template
     * (see [HotspotTemplates.validate]). What the portal really uses, and nothing more:
     * - script-src: only the hash of [SubmitOnceScriptText] (the one inline script); no other script runs, even if one slips in.
     * - style-src: the pages and the templates carry their own inline <style>/style= (colors come from Configuracion).
     * - img-src: the company logo is any http(s) address chosen in Configuracion, and data: images; frames, objects and fonts
     *   have no outside source (system fonts only).
     * - form-action 'self': every form posts to the portal itself (no redirects out of a form); the success page
     *   leaves through <meta refresh> and links, which this policy does not restrict.
     */
    val ContentSecurityPolicy: String by lazy {
        val hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(SubmitOnceScriptText.toByteArray(Charsets.UTF_8)))
        listOf(
            "default-src 'self'",
            "script-src 'sha256-$hash'",
            "img-src 'self' data: https: http:",
            "style-src 'self' 'unsafe-inline'",
            "form-action 'self'",
            "base-uri 'none'",
            "frame-ancestors 'none'",
            "object-src 'none'",
        ).joinToString("; ")
    }

    /** What the page really carries inside its script tag, for tests. */
    val InlineScript: String get() = SubmitOnceScriptText

    private const val WifiIcon = """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M5 12.55a11 11 0 0 1 14.08 0"></path><path d="M1.42 9a16 16 0 0 1 21.16 0"></path><path d="M8.53 16.11a6 6 0 0 1 6.95 0"></path><circle cx="12" cy="20" r="1" fill="currentColor"></circle></svg>"""
}
