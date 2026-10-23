package com.ideasdeveloper.idc.server.modules

import kotlinx.serialization.Serializable

// Generic payload for views with kind = "report". The client renders metrics,
// bar charts and tables without knowing which module produced them.
@Serializable
data class ModuleReport(
    val metrics: List<ModuleReportMetric> = emptyList(),
    val charts: List<ModuleReportChart> = emptyList(),
    val tables: List<ModuleReportTable> = emptyList(),
)

@Serializable
data class ModuleReportMetric(
    val label: String,
    val value: String,
    val help: String = "",
)

@Serializable
data class ModuleReportChart(
    val title: String,
    val points: List<ModuleReportPoint>,
)

@Serializable
data class ModuleReportPoint(
    val label: String,
    val value: Double,
)

@Serializable
data class ModuleReportTable(
    val title: String,
    val columns: List<ModuleReportColumn>,
    val rows: List<Map<String, String>>,
)

@Serializable
data class ModuleReportColumn(
    val key: String,
    val label: String,
)
