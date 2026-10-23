package com.ideasdeveloper.idc.ui

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/*
 * Server-driven UI contract shared by the server and every client.
 *
 * A module chooses how its interface is delivered (ModuleUiEntry):
 * - "native": the server answers GET {entry}/{screen} with a UiScreen built from the
 *   generic pieces below and the app draws it with native components;
 * - "web": the module serves its own HTML under {entry} and the app opens it in a WebView.
 *
 * Compatibility rule: clients ignore piece and action types they do not know, so a
 * newer server never breaks an older app. Add new types; never change existing ones.
 */

val UiJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
data class ModuleUiEntry(
    // "native" or "web".
    val type: String,
    // Native: base route of the screens, e.g. /modules/hotspot/ui. Web: first page, e.g. /modules/crm/web/.
    val entry: String,
    // Native: screen opened first.
    val home: String = "home",
)

@Serializable
data class UiScreen(
    val title: String,
    val theme: UiTheme? = null,
    val navigation: UiNavigation? = null,
    val notice: UiNotice? = null,
    val body: List<UiNode> = emptyList(),
)

// Colors as #RRGGBB. Missing values fall back to the client's defaults.
@Serializable
data class UiTheme(
    val background: String? = null,
    val surface: String? = null,
    val surfaceAlt: String? = null,
    val border: String? = null,
    val text: String? = null,
    val muted: String? = null,
    val accent: String? = null,
    val onAccent: String? = null,
    val success: String? = null,
    val warning: String? = null,
    val danger: String? = null,
)

// Module sections. Wide screens show a sidebar; narrow screens a horizontal bar.
@Serializable
data class UiNavigation(
    val title: String = "",
    val items: List<UiNavItem>,
    val selected: String = "",
)

@Serializable
data class UiNavItem(val id: String, val label: String, val action: UiAction)

@Serializable
data class UiNotice(val text: String, val tone: String = UiTone.Info)

object UiTone {
    const val Info = "info"
    const val Success = "success"
    const val Warning = "warning"
    const val Danger = "danger"
    const val Neutral = "neutral"
}

object UiTextStyle {
    const val Title = "title"
    const val Subtitle = "subtitle"
    const val Body = "body"
    const val Caption = "caption"
}

@Serializable
data class UiField(
    val key: String,
    val label: String,
    // text, email, phone, url, number, password, select, textarea, color, date, boolean.
    val type: String = "text",
    val required: Boolean = false,
    val readOnly: Boolean = false,
    val options: List<String> = emptyList(),
    val help: String = "",
    val placeholder: String = "",
    // Consecutive fields with the same section are grouped under one heading.
    val section: String = "",
)

@Serializable
data class UiTableColumn(val key: String, val label: String)

@Serializable
data class UiChartPoint(val label: String, val value: Double)

// ---- Pieces ----

@Serializable(with = UiNodeSerializer::class)
sealed interface UiNode {
    val type: String
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("text")
data class UiText(
    val text: String,
    val style: String = UiTextStyle.Body,
    val tone: String? = null,
    @EncodeDefault override val type: String = "text",
) : UiNode

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UiColumn(
    val children: List<UiNode>,
    val spacing: Int = 12,
    @EncodeDefault override val type: String = "column",
) : UiNode

// Horizontal group. With fill = true the children share the width equally.
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UiRow(
    val children: List<UiNode>,
    val spacing: Int = 12,
    val fill: Boolean = false,
    @EncodeDefault override val type: String = "row",
) : UiNode

// Responsive grid: as many columns as fit with at least minItemWidth dp each.
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UiGrid(
    val children: List<UiNode>,
    val minItemWidth: Int = 220,
    val spacing: Int = 16,
    @EncodeDefault override val type: String = "grid",
) : UiNode

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UiCard(
    val children: List<UiNode>,
    val title: String? = null,
    // Pieces shown at the right of the title, typically buttons.
    val actions: List<UiNode> = emptyList(),
    @EncodeDefault override val type: String = "card",
) : UiNode

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UiMetric(
    val label: String,
    val value: String,
    val caption: String = "",
    val captionTone: String = UiTone.Neutral,
    @EncodeDefault override val type: String = "metric",
) : UiNode

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UiStatus(
    val label: String,
    val tone: String = UiTone.Success,
    @EncodeDefault override val type: String = "status",
) : UiNode

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UiBarChart(
    val points: List<UiChartPoint>,
    val height: Int = 180,
    @EncodeDefault override val type: String = "barChart",
) : UiNode

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UiTable(
    val columns: List<UiTableColumn>,
    val rows: List<Map<String, String>>,
    val emptyText: String = "Sin datos.",
    @EncodeDefault override val type: String = "table",
) : UiNode

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UiButton(
    val label: String,
    val action: UiAction,
    // primary, secondary or danger.
    val style: String = "primary",
    val enabled: Boolean = true,
    @EncodeDefault override val type: String = "button",
) : UiNode

/**
 * Editable fields. [submit] runs with the form values: a "request" sends them as a JSON body,
 * "navigate" and "download" as query parameters. Other pieces can use the values through formId.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UiForm(
    val id: String,
    val fields: List<UiField>,
    val values: Map<String, String> = emptyMap(),
    val submit: UiButton? = null,
    val minFieldWidth: Int = 280,
    @EncodeDefault override val type: String = "form",
) : UiNode

/**
 * Image from an absolute http(s) URL or a module route ("/modules/..."). Module routes are
 * requested with the user's session; web clients need CORS on external hosts.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UiImage(
    val url: String,
    val height: Int = 64,
    val description: String = "",
    @EncodeDefault override val type: String = "image",
) : UiNode

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UiDivider(
    @EncodeDefault override val type: String = "divider",
) : UiNode

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UiSpacer(
    val size: Int = 16,
    @EncodeDefault override val type: String = "spacer",
) : UiNode

// Piece sent by a newer server; clients skip it.
@Serializable
data class UiUnknownNode(override val type: String = "unknown") : UiNode

object UiNodeSerializer : JsonContentPolymorphicSerializer<UiNode>(UiNode::class) {
    override fun selectDeserializer(element: JsonElement): KSerializer<out UiNode> =
        when ((element as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull) {
            "text" -> UiText.serializer()
            "column" -> UiColumn.serializer()
            "row" -> UiRow.serializer()
            "grid" -> UiGrid.serializer()
            "card" -> UiCard.serializer()
            "metric" -> UiMetric.serializer()
            "status" -> UiStatus.serializer()
            "barChart" -> UiBarChart.serializer()
            "table" -> UiTable.serializer()
            "button" -> UiButton.serializer()
            "form" -> UiForm.serializer()
            "image" -> UiImage.serializer()
            "divider" -> UiDivider.serializer()
            "spacer" -> UiSpacer.serializer()
            else -> UiUnknownNode.serializer()
        }
}

// ---- Actions ----

@Serializable(with = UiActionSerializer::class)
sealed interface UiAction {
    val type: String
    // Form whose values are attached to the action.
    val formId: String?
}

// Loads another screen of the same module: GET {entry}/{screen}?params.
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UiNavigate(
    val screen: String,
    val params: Map<String, String> = emptyMap(),
    override val formId: String? = null,
    @EncodeDefault override val type: String = "navigate",
) : UiAction

// Calls a module route; the response is the UiScreen to show next.
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UiRequest(
    val route: String,
    val method: String = "POST",
    val params: Map<String, String> = emptyMap(),
    // Question shown before running the action, e.g. before deleting.
    val confirm: String? = null,
    override val formId: String? = null,
    @EncodeDefault override val type: String = "request",
) : UiAction

// Downloads a text file (e.g. CSV) from a module route.
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UiDownload(
    val route: String,
    val fileName: String,
    val params: Map<String, String> = emptyMap(),
    override val formId: String? = null,
    @EncodeDefault override val type: String = "download",
) : UiAction

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UiOpenUrl(
    val url: String,
    override val formId: String? = null,
    @EncodeDefault override val type: String = "openUrl",
) : UiAction

// Action sent by a newer server; clients disable the control that uses it.
@Serializable
data class UiUnknownAction(override val type: String = "unknown", override val formId: String? = null) : UiAction

object UiActionSerializer : JsonContentPolymorphicSerializer<UiAction>(UiAction::class) {
    override fun selectDeserializer(element: JsonElement): KSerializer<out UiAction> =
        when ((element as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull) {
            "navigate" -> UiNavigate.serializer()
            "request" -> UiRequest.serializer()
            "download" -> UiDownload.serializer()
            "openUrl" -> UiOpenUrl.serializer()
            else -> UiUnknownAction.serializer()
        }
}
