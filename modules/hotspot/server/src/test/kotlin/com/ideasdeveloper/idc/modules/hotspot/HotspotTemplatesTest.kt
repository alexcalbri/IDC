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
