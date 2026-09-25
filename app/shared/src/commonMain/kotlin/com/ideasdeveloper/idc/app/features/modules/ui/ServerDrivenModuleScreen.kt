package com.ideasdeveloper.idc.app.features.modules.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.idc.app.core.company.CompanyIdentityStore
import com.ideasdeveloper.idc.app.features.modules.data.ModuleApi
import com.ideasdeveloper.idc.app.features.modules.data.ModuleDefinition
import com.ideasdeveloper.idc.app.features.modules.data.ModuleException
import com.ideasdeveloper.idc.app.features.shell.ui.AuthenticatedTopBar
import com.ideasdeveloper.idc.app.features.shell.ui.toComposeColor

@Composable
fun ServerDrivenModuleScreen(
    moduleId: String,
    serverUrl: String?,
    fallbackTitle: String,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    onMinimize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val companyIdentity = remember { CompanyIdentityStore.current() }
    val primaryColor = remember(companyIdentity.primaryColor) {
        companyIdentity.primaryColor.toComposeColor(Color(0xFF667EEA))
    }
    val secondaryColor = remember(companyIdentity.secondaryColor) {
        companyIdentity.secondaryColor.toComposeColor(Color(0xFF764BA2))
    }
    var metadata by remember(moduleId) { mutableStateOf<ModuleDefinition?>(null) }
    var error by remember(moduleId) { mutableStateOf<String?>(null) }
    var loading by remember(moduleId) { mutableStateOf(false) }

    LaunchedEffect(moduleId, serverUrl) {
        val activeServerUrl = serverUrl
        if (activeServerUrl == null) {
            error = "No hay URL de servidor configurada."
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            metadata = ModuleApi(activeServerUrl).use { it.metadata(moduleId) }
        } catch (exception: ModuleException) {
            error = exception.message
        } finally {
            loading = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.horizontalGradient(listOf(primaryColor, secondaryColor)))
    ) {
        AuthenticatedTopBar(
            title = metadata?.displayName ?: fallbackTitle,
            subtitle = metadata?.views?.firstOrNull()?.title,
            primaryColor = primaryColor,
            onSettings = onSettings,
            onLogout = onLogout,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .align(Alignment.Center),
            color = Color.White,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    loading -> CircularProgressIndicator(color = primaryColor)
                    error != null -> Text(error.orEmpty(), color = Color(0xFFB00020))
                    metadata != null -> {
                        Text(
                            text = metadata?.description.orEmpty(),
                            color = Color(0xFF333333),
                            fontWeight = FontWeight.SemiBold,
                        )
                        metadata?.views.orEmpty().forEach { view ->
                            Text("${view.title} (${view.kind})", color = Color(0xFF666666))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onMinimize,
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                ) {
                    Text("Minimizar")
                }
            }
        }
    }
}

private suspend inline fun <T> ModuleApi.use(block: suspend (ModuleApi) -> T): T {
    return try {
        block(this)
    } finally {
        close()
    }
}
