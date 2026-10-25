package com.ideasdeveloper.idc.app.core.theme

import java.util.prefs.Preferences

actual object PersistentPreferences {
    private val preferences = Preferences.userRoot().node("com/ideasdeveloper/idc/preferences")

    actual fun get(key: String): String? = preferences.get(key, null)

    actual fun set(key: String, value: String) {
        preferences.put(key, value)
    }
}
