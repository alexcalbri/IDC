package com.ideasdeveloper.idc.extensions.wordpress

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds the zip of the IDC Connector WordPress plugin from the resources of the extension package. */
object CrmPlugin {
    const val Root = "extensions/wordpress/idc-connector"
    // Files of the plugin; WordPress expects them inside an "idc-connector" folder.
    val files = listOf("idc-connector.php", "readme.txt")

    // Un paquete de extension no admite recursos .php (ExtensionPackageSafety): el PHP se guarda como texto
    // inerte con el sufijo .txt y solo se renombra al meterlo en el zip del plugin. El servidor nunca lo ejecuta.
    private const val ResourceSuffix = ".txt"

    private fun resourceName(name: String) = if (name.endsWith(".php")) name + ResourceSuffix else name

    fun zip(classLoader: ClassLoader): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { name ->
                val bytes = classLoader.getResourceAsStream("$Root/${resourceName(name)}")?.use { it.readBytes() }
                    ?: error("Missing plugin resource $Root/${resourceName(name)}")
                zip.putNextEntry(ZipEntry("idc-connector/$name"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
