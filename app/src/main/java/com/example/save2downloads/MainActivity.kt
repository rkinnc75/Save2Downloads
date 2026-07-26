package com.example.save2downloads

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
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
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {

    companion object {
        const val CHANNEL_ID = "save2downloads"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()

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
            else -> finish()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Save2Downloads", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "File save notifications"
                setSound(null, null)
                enableVibration(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
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
                val savedUri = saveFile(sourceUri, finalName)
                if (savedUri != null) {
                    showSavedNotification(finalName, savedUri)
                }
                finish()
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun resolveUniqueName(desiredName: String): String {
        if (!fileExistsInDownloads(desiredName)) return desiredName
        val dotIndex = desiredName.lastIndexOf('.')
        val base = if (dotIndex > 0) desiredName.substring(0, dotIndex) else desiredName
        val ext = if (dotIndex > 0) desiredName.substring(dotIndex) else ""
        var n = 1
        while (true) {
            val candidate = "$base ($n)$ext"
            if (!fileExistsInDownloads(candidate)) return candidate
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
            )?.use { it.count > 0 } ?: false
        } else {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName).exists()
        }
    }

    private fun saveFile(sourceUri: Uri, fileName: String): Uri? {
        return try {
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
                    contentResolver.update(uri, ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }, null, null)
                }
                destUri
            } else {
                val destFile = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    fileName
                )
                contentResolver.openInputStream(sourceUri)?.use { input ->
                    FileOutputStream(destFile).use { output -> input.copyTo(output) }
                }
                FileProvider.getUriForFile(this, "$packageName.fileprovider", destFile)
            }
        } catch (e: Exception) { null }
    }

    private fun showSavedNotification(fileName: String, fileUri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, fileUri).apply {
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("Saved to Downloads")
            .setContentText(fileName)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setTimeoutAfter(5000)
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(System.currentTimeMillis().toInt(), builder.build())
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
                } finally { cursor?.close() }
            }
            "file" -> uri.lastPathSegment
            else -> null
        }
    }
}
