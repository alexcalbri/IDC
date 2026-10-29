package com.ideasdeveloper.idc.modules.crm

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds the zip of the IDC Connector WordPress plugin from the module resources. */
object CrmPlugin {
    private const val Root = "modules/crm/wordpress/idc-connector"
    // Files of the plugin; WordPress expects them inside an "idc-connector" folder.
    val files = listOf("idc-connector.php", "readme.txt")

    fun zip(classLoader: ClassLoader): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { name ->
                val bytes = classLoader.getResourceAsStream("$Root/$name")?.use { it.readBytes() }
                    ?: error("Missing plugin resource $Root/$name")
                zip.putNextEntry(ZipEntry("idc-connector/$name"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
