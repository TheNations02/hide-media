package com.hidemedia.app

import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * App muy simple: un botón central.
 * - Primer toque: mueve TODAS las fotos y videos del almacenamiento
 *   compartido (DCIM, Pictures, Movies, WhatsApp, etc.) a una carpeta
 *   privada de la app, que la galería del sistema no puede ver.
 * - Segundo toque: los mueve de vuelta exactamente a su carpeta original.
 *
 * La carpeta privada donde se guardan temporalmente se llama ".ocultados"
 * (dentro del espacio privado de la app), y además incluye un archivo
 * .nomedia como refuerzo para que ningún escáner de medios la indexe.
 *
 * Solo se ocultan los archivos que existían en el momento del toque.
 * Cualquier foto o video nuevo que tomes mientras está "oculto" queda
 * normal y visible en la galería, porque la app no vigila continuamente,
 * solo actúa en el instante en que tocas el botón.
 *
 * Requiere el permiso "Acceso a todos los archivos" (Android 11+),
 * porque se necesita mover archivos reales fuera de la carpeta propia
 * de la app, algo que el almacenamiento restringido normal no permite.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var hiddenDir: File
    private lateinit var manifestFile: File

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { checkPermissionAndUpdateUI() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("hide_media_prefs", MODE_PRIVATE)
        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)

        hiddenDir = File(getExternalFilesDir(null), ".ocultados")
        if (!hiddenDir.exists()) hiddenDir.mkdirs()
        // Refuerzo extra: un .nomedia impide que CUALQUIER escáner de medios
        // (aunque cambie de opinión sobre carpetas con punto) indexe esta carpeta.
        val nomedia = File(hiddenDir, ".nomedia")
        if (!nomedia.exists()) nomedia.createNewFile()
        manifestFile = File(filesDir, "manifest.json")

        checkPermissionAndUpdateUI()
    }

    override fun onResume() {
        super.onResume()
        checkPermissionAndUpdateUI()
    }

    private fun hasAllFilesPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else true
    }

    private fun checkPermissionAndUpdateUI() {
        if (!hasAllFilesPermission()) {
            statusText.text = "Se necesita el permiso \"Acceso a todos los archivos\".\n" +
                "Toca el botón para concederlo en Ajustes."
            toggleButton.text = "Conceder permiso"
            toggleButton.setOnClickListener { requestAllFilesPermission() }
        } else {
            val hidden = prefs.getBoolean("is_hidden", false)
            updateButtonState(hidden)
            toggleButton.setOnClickListener { onToggleClicked() }
        }
    }

    private fun requestAllFilesPermission() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            permissionLauncher.launch(intent)
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            permissionLauncher.launch(intent)
        }
    }

    private fun updateButtonState(hidden: Boolean) {
        toggleButton.text = if (hidden) "Mostrar fotos y videos" else "Ocultar fotos y videos"
        statusText.text = if (hidden)
            "Las fotos y videos están OCULTOS.\nToca para restaurarlos."
        else
            "Las fotos y videos están VISIBLES.\nToca para ocultarlos."
    }

    private fun onToggleClicked() {
        val hidden = prefs.getBoolean("is_hidden", false)
        toggleButton.isEnabled = false
        statusText.text = "Procesando, por favor espera..."

        Thread {
            var success = true
            var errorMsg = ""
            try {
                if (!hidden) {
                    hideAllMedia()
                } else {
                    restoreAllMedia()
                }
            } catch (e: Exception) {
                success = false
                errorMsg = e.message ?: "Error desconocido"
            }

            runOnUiThread {
                if (success) {
                    prefs.edit().putBoolean("is_hidden", !hidden).apply()
                    updateButtonState(!hidden)
                } else {
                    Toast.makeText(this, "Error: $errorMsg", Toast.LENGTH_LONG).show()
                    statusText.text = "Ocurrió un error. Inténtalo de nuevo."
                }
                toggleButton.isEnabled = true
            }
        }.start()
    }

    /** Recorre MediaStore para encontrar todas las fotos y videos reales en el dispositivo. */
    private fun collectMediaFiles(): List<File> {
        val files = mutableListOf<File>()
        val collections = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
        for (collection in collections) {
            val projection = arrayOf(MediaStore.MediaColumns.DATA)
            contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataCol) ?: continue
                    val f = File(path)
                    if (f.exists() && !f.path.startsWith(hiddenDir.path)) {
                        files.add(f)
                    }
                }
            }
        }
        return files
    }

    private fun hideAllMedia() {
        val files = collectMediaFiles()
        val manifest = JSONArray()
        val scannedOriginals = mutableListOf<String>()

        for (file in files) {
            try {
                val destName = "${System.currentTimeMillis()}_${files.indexOf(file)}_${file.name}"
                val dest = File(hiddenDir, destName)
                if (moveFile(file, dest)) {
                    val entry = JSONObject()
                    entry.put("original", file.absolutePath)
                    entry.put("hidden", dest.absolutePath)
                    manifest.put(entry)
                    scannedOriginals.add(file.absolutePath)
                }
            } catch (e: Exception) {
                // si un archivo falla, seguimos con los demás
            }
        }

        manifestFile.writeText(manifest.toString())

        // Avisamos al escáner de medios de que los archivos originales ya no existen,
        // para que desaparezcan de la galería inmediatamente.
        if (scannedOriginals.isNotEmpty()) {
            MediaScannerConnection.scanFile(this, scannedOriginals.toTypedArray(), null, null)
        }
    }

    private fun restoreAllMedia() {
        if (!manifestFile.exists()) return
        val manifest = JSONArray(manifestFile.readText())
        val scannedRestored = mutableListOf<String>()

        for (i in 0 until manifest.length()) {
            val entry = manifest.getJSONObject(i)
            val originalPath = entry.getString("original")
            val hiddenPath = entry.getString("hidden")
            val hiddenFile = File(hiddenPath)
            val originalFile = File(originalPath)
            try {
                originalFile.parentFile?.mkdirs()
                if (moveFile(hiddenFile, originalFile)) {
                    scannedRestored.add(originalFile.absolutePath)
                }
            } catch (e: Exception) {
                // seguimos con los demás
            }
        }

        if (scannedRestored.isNotEmpty()) {
            MediaScannerConnection.scanFile(this, scannedRestored.toTypedArray(), null, null)
        }

        manifestFile.delete()
    }

    private fun moveFile(source: File, dest: File): Boolean {
        if (source.renameTo(dest)) return true
        // Si renameTo falla (por ejemplo, entre distintos volúmenes de almacenamiento),
        // hacemos copia + borrado como respaldo.
        return try {
            FileInputStream(source).use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            source.delete()
            true
        } catch (e: Exception) {
            false
        }
    }
}
