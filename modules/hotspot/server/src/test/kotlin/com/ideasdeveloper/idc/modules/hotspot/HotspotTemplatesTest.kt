package com.ideasdeveloper.idc.modules.hotspot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HotspotTemplatesTest {
    private val form = HotspotTemplates.Form
    private val code = HotspotTemplates.CustomerCode

    private fun page(form: String, outside: String = "{{error}}") = "<html><body>$outside$form</body></html>"

    private val validForm = page(
        """
        <form action="/guest/s/default/register" method="POST">
          <input name="name" value="{{nombre}}"><input name="email"><input name="phone">
          <input type="checkbox" name="terms" value="si"> {{consentimiento}}
          {{campos_ocultos}}
          <button>Entrar</button>
        </form>
        """.trimIndent()
    )

    @Test
    fun examplesOfEveryViewAreValid() {
        HotspotTemplates.views.forEach { view ->
            assertEquals(emptyList(), HotspotTemplates.validate(view, HotspotTemplates.example(view)), view.id)
        }
        assertEquals(emptyList(), HotspotTemplates.validate(form, validForm))
    }

    @Test
    fun theFormNeedsItsAddressMethodAndFields() {
        val problems = HotspotTemplates.validate(form, page("""<form action="/guest/s/default/register" method="get"><input name="name">{{campos_ocultos}}</form>"""))
        assertTrue(problems.any { "post" in it })
        listOf("email", "phone", "terms").forEach { field -> assertTrue(problems.any { "name=\"$field\"" in it }, field) }
        assertTrue(HotspotTemplates.validate(code, "{{error}}<p>sin formulario</p>").any { "Falta el formulario" in it })
    }

    @Test
    fun fieldsAndHiddenFieldsMustBeInsideTheForm() {
        val outside = page(
            """<input name="code"><form action="/guest/s/default/code" method="post"><button>Ok</button></form>{{campos_ocultos}}""",
        )
        val problems = HotspotTemplates.validate(code, outside)
        assertTrue(problems.any { "name=\"code\"" in it })
        assertTrue(problems.any { "{{campos_ocultos}} debe ir dentro" in it })
    }

    @Test
    fun commentedOutMarkupDoesNotCount() {
        val commented = page("""<!-- <form action="/guest/s/default/code" method="post"><input name="code">{{campos_ocultos}}</form> -->""")
        assertTrue(HotspotTemplates.validate(code, commented).any { "Falta el formulario" in it })
    }

    @Test
    fun scriptsEventsAndForeignFormsAreRejected() {
        val valid = HotspotTemplates.example(code)
        assertTrue(HotspotTemplates.validate(code, valid.replace("</body>", "<script>alert(1)</script></body>")).any { "<script>" in it })
        assertTrue(HotspotTemplates.validate(code, valid.replace("<button", "<button onclick=\"x()\"")).any { "onclick" in it })
        assertTrue(HotspotTemplates.validate(code, valid.replace("</body>", "<a href=\" JavaScript:x()\">x</a></body>")).any { "javascript:" in it })
        assertTrue(HotspotTemplates.validate(code, valid.replace("</body>", "<form action=\"https://evil.example/\" method=\"post\"></form></body>")).any { "otra direccion" in it })
    }

    private fun withExtra(extra: String) = HotspotTemplates.example(code).replace("</body>", "$extra</body>")

    private fun rejected(extra: String) = HotspotTemplates.validate(code, withExtra(extra)).isNotEmpty()

    @Test
    fun markupHiddenBehindBrowserClosedCommentsIsRejected() {
        // The browser closes "<!-->" and "<!--->" at once: what follows is live markup.
        assertTrue(HotspotTemplates.validate(code, withExtra("<!--><script>alert(1)</script><!-- -->")).any { "comentarios" in it })
        assertTrue(HotspotTemplates.validate(code, withExtra("<!--><script>alert(1)</script><!-- -->")).any { "<script>" in it })
        assertTrue(HotspotTemplates.validate(code, withExtra("<!---><img src=x onerror=alert(1)>")).any { "onerror" in it })
        assertTrue(HotspotTemplates.validate(code, withExtra("<!-- sin cerrar <b>")).any { "sin cerrar" in it })
        assertTrue(HotspotTemplates.validate(code, withExtra("<!-- x --!><script>1</script>")).any { "--!>" in it })
        // Even a "normal" comment does not hide a forbidden tag.
        assertTrue(HotspotTemplates.validate(code, withExtra("<!-- <script>alert(1)</script> -->")).any { "<script>" in it })
        // Comments themselves are fine.
        assertEquals(emptyList(), HotspotTemplates.validate(code, withExtra("<!-- nota -->")))
        assertEquals("<p>a</p><p>b</p>", HotspotTemplates.withoutComments("<p>a</p><!-- x --><p>b</p>").first)
        assertEquals("<p>a</p>", HotspotTemplates.withoutComments("<p>a</p><!-- sin fin <b>").first)
    }

    @Test
    fun dangerousUrlsAreRejectedEvenWhenWrittenWithEntities() {
        listOf(
            """<a href="&#106;avascript:alert(1)">x</a>""",
            """<a href="jav&#x09;ascript:alert(1)">x</a>""",
            """<a href="javascript&colon;alert(1)">x</a>""",
            """<a href="java&Tab;script:alert(1)">x</a>""",
            """<a href="&#x6A;&#x61;vascript&#58;alert(1)">x</a>""",
            """<a href="&#0000106avascript:alert(1)">x</a>""",
            """<a href=" &NewLine;javascript:alert(1)">x</a>""",
            """<a href="vbscript:msgbox(1)">x</a>""",
            """<a href="data:text/html;base64,PHNjcmlwdD4=">x</a>""",
            """<img src="data:image/svg+xml;base64,AAAA">""",
            """<a href='JaVaScRiPt:alert(1)' href='/ok'>x</a>""",
            """<a href=javascript:alert(1)>x</a>""",
        ).forEach { assertTrue(rejected(it), it) }
        assertEquals(emptyList(), HotspotTemplates.validate(code, withExtra("""<a href="https://example.com/?a=1&amp;b=2">x</a><a href="data-sheet.html">y</a>""")))
    }

    @Test
    fun svgMathLinkAndEventsInOddPlacesAreRejected() {
        listOf(
            "<svg onload=alert(1)>",
            "<svg/onload=alert(1)>",
            "<math><mi xlink:href=\"javascript:alert(1)\">x</mi></math>",
            "<link rel=stylesheet href=//evil.example/x.css>",
            "<img\nsrc=x\nonerror=alert(1)>",
            "<img/src=x/onerror=alert(1)>".replace("/onerror", " onerror"),
            "<img src=\"x\"onerror=alert(1)>",
            "<img alt=\">\" onerror=alert(1)>",
            // A stray quote must not hide the next tag from the checks.
            "<a b=x\"><script>alert(1)</script>\"></a>",
            "<meta http-equiv=\"&#114;efresh\" content=\"0;url=https://evil.example\">",
            "<p style=\"background:url(javascript:alert(1))\">x</p>",
            "<p style=\"width:expression(alert(1))\">x</p>",
            "<style>body{background:url('&#106;avascript:x')}</style>".replace("&#106;", "j"),
            "<style>@import url(//evil.example/x.css);</style>",
            "<style>a{b:e\\78 pression(alert(1))}</style>",
        ).forEach { assertTrue(rejected(it), it) }
    }

    @Test
    fun markersCannotAssembleForbiddenMarkup() {
        listOf(
            "<scr{{error}}ipt>alert(1)</script>",
            "<img o{{error}}nerror=alert(1)>",
            "<a href=\"java{{error}}script:alert(1)\">x</a>",
            "<meta http-equiv=\"ref{{error}}resh\" content=\"0;url=https://evil.example\">",
            "<a href={{titulo}}>x</a>",
            "<input value=\"{{error}}\">",
        ).forEach { assertTrue(rejected(it), it) }
        // A whole quoted value is fine (that is how the default templates use them).
        assertEquals(emptyList(), HotspotTemplates.validate(code, withExtra("""<input value="{{titulo}}" placeholder='{{tipo_codigo}}'>""")))
    }

    @Test
    fun aFormHiddenWhereTheBrowserSeesNoCommentIsRejected() {
        val hidden = "<textarea><!--</textarea><form action=\"https://evil.example\" method=post><input name=code></form>-->"
        assertTrue(HotspotTemplates.validate(code, withExtra(hidden)).any { "otra direccion" in it })
    }

    @Test
    fun hugeInputsFinishQuicklyWithoutErrors() {
        val started = System.nanoTime()
        val tooLong = HotspotTemplates.validate(code, "<" + " ".repeat(HotspotTemplates.MaxLength + 1))
        assertTrue(tooLong.single().contains("no puede pasar"))
        val spaces = HotspotTemplates.validate(code, "<" + " ".repeat(HotspotTemplates.MaxLength - 10))
        val attributes = HotspotTemplates.validate(code, "<a " + "x".repeat(HotspotTemplates.MaxLength - 10))
        val many = HotspotTemplates.validate(code, "<a " + "b=\"c\" ".repeat(14_000))
        assertTrue(spaces.isNotEmpty() && attributes.isNotEmpty() && many.isNotEmpty())
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
    }

    @Test
    fun defaultTemplatesAndOrdinaryStylesStayValid() {
        HotspotTemplates.views.forEach { assertEquals(emptyList(), HotspotTemplates.validate(it, HotspotTemplates.example(it)), it.id) }
        assertEquals(emptyList(), HotspotTemplates.validate(code, withExtra("""<p style="color:red;background:url(data:image/png;base64,AAAA)">x</p><style>a{color:#fff}</style>""")))
    }

    @Test
    fun portalSendsAPolicyThatOnlyAllowsItsOwnInlineScript() {
        val policy = HotspotPortalPages.ContentSecurityPolicy
        val hash = java.util.Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest(HotspotPortalPages.InlineScript.toByteArray(Charsets.UTF_8)),
        )
        assertTrue("script-src 'sha256-$hash'" in policy)
        listOf("default-src 'self'", "form-action 'self'", "base-uri 'none'", "frame-ancestors 'none'", "object-src 'none'").forEach { assertTrue(it in policy, it) }
        assertFalse("script-src 'unsafe-inline'" in policy)
        // The page really carries that script, byte for byte.
        assertTrue("<script>${HotspotPortalPages.InlineScript}</script>" in HotspotPortalPages.message(null, "Titulo", "Texto", retry = false))
    }

    @Test
    fun errorIsRequiredAndUnknownPlaceholdersAreReported() {
        assertTrue(HotspotTemplates.validate(form, validForm.replace("{{error}}", "")).any { "{{error}}" in it })
        // {{nombre}} belongs to the form, not to the code views.
        assertTrue(HotspotTemplates.validate(code, HotspotTemplates.example(code) + "{{nombre}}{{titlo}}").let { problems ->
            problems.any { "{{nombre}}" in it } && problems.any { "{{titlo}}" in it }
        })
        assertFalse(HotspotTemplates.validate(form, validForm).any { "no existe" in it })
    }

    @Test
    fun renderingEscapesValuesButKeepsThePortalHtml() {
        val html = HotspotTemplates.render(
            "<h1>{{titulo}}</h1>{{error}}<form>{{ campos_ocultos }}</form>{{pie}}",
            values = mapOf("titulo" to "<b>WiFi</b> & más"),
            raw = mapOf("campos_ocultos" to "<input type=\"hidden\" name=\"id\" value=\"aa\">", "error" to ""),
        )
        assertEquals("<h1>&lt;b&gt;WiFi&lt;/b&gt; &amp; más</h1><form><input type=\"hidden\" name=\"id\" value=\"aa\"></form>", html)
    }
}
