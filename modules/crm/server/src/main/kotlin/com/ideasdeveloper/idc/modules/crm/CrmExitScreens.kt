package com.ideasdeveloper.idc.modules.crm

import com.ideasdeveloper.idc.ui.UiButton
import com.ideasdeveloper.idc.ui.UiCard
import com.ideasdeveloper.idc.ui.UiField
import com.ideasdeveloper.idc.ui.UiForm
import com.ideasdeveloper.idc.ui.UiNode
import com.ideasdeveloper.idc.ui.UiRequest
import com.ideasdeveloper.idc.ui.UiText
import com.ideasdeveloper.idc.ui.UiTextStyle
import java.time.Instant
import java.time.ZoneId

/**
 * Automatizacion de salida: archive leads after check-out. A section of its own (one card, one form,
 * the "settings:exitSave" action) so it can move to the Automatizaciones tab without touching the rest.
 */
object CrmExitScreens {
    private const val StagePrefix = "exitStage_"

    fun card(context: CrmScreens.Context, submitted: Map<String, String>?): UiNode {
        val config = CrmExit.load(context.tenant)
        val cloudbeds = CrmCloudbeds.load(context.tenant)
        val section = "Etapas de origen"
        // Leads the automation would archive right now, per stage: shown before turning it on.
        val overdue = CrmExit.dueByStage(context.tenant, config, context.stages.map { it.id }, Instant.now())
        val overdueOfChosen = config.stageIds.sumOf { overdue[it] ?: 0 }
        // Asked again after a first save attempt that would archive leads (see "settings:exitSave").
        val asksConfirmation = submitted?.containsKey("exitConfirm") == true
        val fields = listOf(
            UiField("exitEnabled", "Archivar los leads al hacer check-out", type = "boolean"),
            UiField("exitHour", "Hora (0-23)", type = "number", help = "Sin Cloudbeds, a esta hora del dia del check-out se archivan los leads."),
            UiField(
                "exitTimeZone", "Zona horaria", placeholder = ZoneId.systemDefault().id,
                help = "Nombre de zona como America/Panama. Vacio usa la del servidor (${ZoneId.systemDefault().id}).",
            ),
        ) + context.stages.map {
            val count = overdue[it.id] ?: 0
            UiField(StagePrefix + it.id, if (count > 0) "${it.name} ($count con salida vencida)" else it.name, type = "boolean", section = section)
        } + listOfNotNull(
            UiField("exitConfirm", "Confirmo archivar de una vez los leads con salida vencida", type = "boolean").takeIf { asksConfirmation },
        )
        val values = mapOf(
            "exitEnabled" to config.enabled.toString(),
            "exitHour" to config.hour.toString(),
            "exitTimeZone" to config.timeZone,
        ) + context.stages.associate { (StagePrefix + it.id) to (it.id in config.stageIds).toString() }
        val how = when {
            !cloudbeds.ready ->
                "Sin Cloudbeds, el lead se archiva cuando su fecha de fin ya paso, o es hoy y paso la hora elegida. Queda una nota en su historial."
            cloudbeds.archiveOnCheckout ->
                "Cloudbeds esta activo: la hora no archiva. Se archivan los leads de estas etapas cuya reserva Cloudbeds marca como check-out " +
                    "(tambien los que llegan a la etapa despues del check-out). Un lead sin reserva de Cloudbeds no se archiva."
            else ->
                "Cloudbeds esta activo pero \"archivar al hacer check-out\" esta apagado en Integraciones: no se archiva nada hasta que lo enciendas."
        }
        val warning = if (!config.enabled && overdueOfChosen > 0) " Al activar se archivaran $overdueOfChosen leads con salida vencida." else ""
        return UiCard(
            listOf(
                UiText("Solo se archivan los leads que esten en las etapas de origen elegidas. $how$warning", UiTextStyle.Caption),
                UiForm(
                    "exit", fields, values + submitted.orEmpty().filterKeys { it in values.keys || it == "exitConfirm" },
                    submit = UiButton("Guardar", UiRequest(CrmScreens.UiRoute + "/settings", params = mapOf("op" to "exitSave"), formId = "exit")),
                    minFieldWidth = 200,
                ),
            ),
            title = "Automatizacion de salida",
            collapsible = true,
            // Open only after this card's own failed or pending save, not after another form's.
            expanded = submitted?.keys?.any { it.startsWith("exit") } == true,
        )
    }

    /** Reads the form of [card]. */
    fun parse(context: CrmScreens.Context, values: Map<String, String>): CrmExitAutomation {
        val chosen = context.stages.filter { values[StagePrefix + it.id] == "true" }.map { it.id }.toSet()
        return CrmExit.parse(values["exitEnabled"] == "true", values["exitHour"].orEmpty(), values["exitTimeZone"].orEmpty(), chosen, context.stages)
    }
}
