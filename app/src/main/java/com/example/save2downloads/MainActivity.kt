package com.example.save2downloads

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent?.action) {
            Intent.ACTION_SEND -> {
                (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uri ->
                    promptAndSave(uri)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach { uri ->
                    promptAndSave(uri)
                }
            }
            else -> {
                Toast.makeText(this, "Open this app by sharing a file to it", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun promptAndSave(sourceUri: Uri) {
        val defaultName = getFileName(sourceUri) ?: "shared_file"

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 0)
        }

        val input = EditText(this).apply {
            setText(defaultName)
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        layout.addView(input)

        AlertDialog.Builder(this)
            .setTitle("Save to Downloads")
            .setMessage("Edit filename if needed:")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val chosenName = input.text.toString().trim().ifEmpty { defaultName }
                val finalName = resolveUniqueName(chosenName)
                saveFile(sourceUri, finalName)
                finish()
            }
            .setNegativeButton("Cancel") { _, _ ->
                finish()
            }
            .setOnCancelListener {
                finish()
            }
            .show()
    }

    private fun resolveUniqueName(desiredName: String): String {
        if (!fileExistsInDownloads(desiredName)) {
            return desiredName
        }

        val dotIndex = desiredName.lastIndexOf('.')
        val base = if (dotIndex > 0) desiredName.substring(0, dotIndex) else desiredName
        val ext = if (dotIndex > 0) desiredName.substring(dotIndex) else ""

        var n = 1
        while (true) {
            val candidate = "$base ($n)$ext"
            if (!fileExistsInDownloads(candidate)) {
                return candidate
            }
            n++
        }
    }

    private fun fileExistsInDownloads(fileName: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(MediaStore.Downloads._ID)
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf(fileName, "%${Environment.DIRECTORY_DOWNLOADS}%")
            contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs, null
            )?.use { cursor ->
                cursor.count > 0
            } ?: false
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(downloadsDir, fileName).exists()
        }
    }

    private fun saveFile(sourceUri: Uri, fileName: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, contentResolver.getType(sourceUri) ?: "*/*")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }

                val destUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                destUri?.let { uri ->
                    contentResolver.openInputStream(sourceUri)?.use { input ->
                        contentResolver.openOutputStream(uri)?.use { output ->
                            input.copyTo(output)
                        }
                    }
                    val clearValues = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    contentResolver.update(uri, clearValues, null, null)
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val destFile = File(downloadsDir, fileName)

                contentResolver.openInputStream(sourceUri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            Toast.makeText(this, "Saved to Downloads: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun getFileName(uri: Uri): String? {
        return when (uri.scheme) {
            "content" -> {
                var cursor: Cursor? = null
                try {
                    cursor = contentResolver.query(uri, null, null, null, null)
                    if (cursor != null && cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                } finally {
                    cursor?.close()
                }
            }
            "file" -> uri.lastPathSegment
            else -> null
        }
    }
}
