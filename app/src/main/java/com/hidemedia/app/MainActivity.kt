package com.hidemedia.app

import android.content.ContentUris
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
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var manifestFile: File

    private var hiddenDirs: List<File> = emptyList()
    private val nameCounter = AtomicInteger(0)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { checkPermissionAndUpdateUI() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("hide_media_prefs", MODE_PRIVATE)
        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)
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
            setupHiddenDirs()
            val hidden = prefs.getBoolean("is_hidden", false)
            updateButtonState(hidden)
            toggleButton.setOnClickListener { onToggleClicked() }
        }
    }

    private fun setupHiddenDirs() {
        val roots = mutableListOf<File>()
        val externalDirs = ContextCompat.getExternalFilesDirs(this, null)
        for (dir in externalDirs) {
            if (dir == null) continue
            val path = dir.absolutePath
            val cutIndex = path.indexOf("/Android/data")
            val rootPath = if (cutIndex > 0) path.substring(0, cutIndex) else null
            if (rootPath != null) {
                val root = File(rootPath)
                if (root.exists()) roots.add(root)
            }
        }
        if (roots.isEmpty()) {
            roots.add(Environment.getExternalStorageDirectory())
        }
        hiddenDirs = roots.map { root ->
            val dir = File(root, ".ocultados")
            if (!dir.exists()) dir.mkdirs()
            val nomedia = File(dir, ".nomedia")
            if (!nomedia.exists()) {
                try { nomedia.createNewFile() } catch (e: Exception) { /* ignorar */ }
            }
            dir
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

    private data class MediaEntry(val file: File, val uri: Uri)

    private fun collectMediaEntries(): List<MediaEntry> {
        val results = mutableListOf<MediaEntry>()
        val collections = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
        for (collection in collections) {
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA)
            contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataCol) ?: continue
                    val f = File(path)
                    if (f.exists() && !isInsideAnyHiddenDir(f)) {
                        val id = cursor.getLong(idCol)
                        val uri = ContentUris.withAppendedId(collection, id)
                        results.add(MediaEntry(f, uri))
                    }
                }
            }
        }
        return results
    }

    private fun isInsideAnyHiddenDir(file: File): Boolean {
        return hiddenDirs.any { file.path.startsWith(it.path) }
    }

    private fun hiddenDirForFile(file: File): File {
        val match = hiddenDirs.firstOrNull { hidden ->
            val root = hidden.parentFile ?: return@firstOrNull false
            file.path.startsWith(root.path)
        }
        return match ?: hiddenDirs.first()
    }

    private fun uniqueDestName(file: File): String {
        return "${System.currentTimeMillis()}_${nameCounter.incrementAndGet()}_${file.name}"
    }

    private fun moveManyInParallel(
        jobs: List<Pair<File, File>>
    ): List<Pair<File, File>> {
        if (jobs.isEmpty()) return emptyList()
        val threadCount = minOf(8, Runtime.getRuntime().availableProcessors() * 2).coerceAtLeast(2)
        val executor = Executors.newFixedThreadPool(threadCount)
        val succeeded = Collections.synchronizedList(mutableListOf<Pair<File, File>>())

        val futures = jobs.map { (source, dest) ->
            executor.submit {
                try {
                    if (moveFile(source, dest)) {
                        succeeded.add(source to dest)
                    }
                } catch (e: Exception) {
                    // se ignora este archivo, seguimos con los demás
                }
            }
        }
        futures.forEach {
            try { it.get() } catch (e: Exception) { /* ignorar */ }
        }
        executor.shutdown()
        return succeeded.toList()
    }

    private fun hideAllMedia() {
        val entries = collectMediaEntries()

        val jobs = entries.map { entry ->
            val targetDir = hiddenDirForFile(entry.file)
            val dest = File(targetDir, uniqueDestName(entry.file))
            entry.file to dest
        }
        val moved = moveManyInParallel(jobs)

        val manifest = JSONArray()
        for ((source, dest) in moved) {
            val entry = JSONObject()
            entry.put("original", source.absolutePath)
            entry.put("hidden", dest.absolutePath)
            entry.put("lastModified", dest.lastModified())
            manifest.put(entry)
        }
        manifestFile.writeText(manifest.toString())

        val movedOriginalPaths = moved.map { it.first.absolutePath }.toSet()
        for (entry in entries) {
            if (entry.file.absolutePath in movedOriginalPaths) {
                try {
                    contentResolver.delete(entry.uri, null, null)
                } catch (e: Exception) {
                    MediaScannerConnection.scanFile(this, arrayOf(entry.file.absolutePath), null, null)
                }
            }
        }
    }

    private fun restoreAllMedia() {
        if (!manifestFile.exists()) return
        val manifest = JSONArray(manifestFile.readText())

        val jobs = mutableListOf<Pair<File, File>>()
        val lastModifiedMap = mutableMapOf<String, Long>()
        for (i in 0 until manifest.length()) {
            val entry = manifest.getJSONObject(i)
            val originalFile = File(entry.getString("original"))
            val hiddenFile = File(entry.getString("hidden"))
            originalFile.parentFile?.mkdirs()
            jobs.add(hiddenFile to originalFile)
            if (entry.has("lastModified")) {
                lastModifiedMap[originalFile.absolutePath] = entry.getLong("lastModified")
            }
        }

        val moved = moveManyInParallel(jobs)

        for ((_, dest) in moved) {
            lastModifiedMap[dest.absolutePath]?.let { originalTime ->
                try { dest.setLastModified(originalTime) } catch (e: Exception) { /* ignorar */ }
            }
        }

        val restoredPaths = moved.map { it.second.absolutePath }
        if (restoredPaths.isNotEmpty()) {
            MediaScannerConnection.scanFile(this, restoredPaths.toTypedArray(), null, null)
        }

        manifestFile.delete()
    }

    private fun moveFile(source: File, dest: File): Boolean {
        if (source.renameTo(dest)) return true
        return try {
            val originalTime = source.lastModified()
            FileInputStream(source).use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            dest.setLastModified(originalTime)
            source.delete()
            true
        } catch (e: Exception) {
            false
        }
    }
}
