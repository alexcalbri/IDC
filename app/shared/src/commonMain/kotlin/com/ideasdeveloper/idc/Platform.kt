package com.ideasdeveloper.idc

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform