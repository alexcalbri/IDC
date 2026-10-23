package com.ideasdeveloper.idc

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import web.window.window

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport {
        App(initialServerUrl = window.location.origin)
    }
}
