package com.open.glassesdownloader

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.widget.*
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

/** Built-in private viewer + explicit, per-file export. No library permissions. */
class PrivateViewerActivity : Activity() {
    companion object {
        const val EXTRA_RELATIVE_NAME = "relative_name"
        private const val EXPORT_FILE = 201
    }
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var source: File
    private lateinit var relativeName: String
    private var pendingExport = false
    private var player: MediaPlayer? = null
    private var video: VideoView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        relativeName = intent.getStringExtra(EXTRA_RELATIVE_NAME) ?: run { finish(); return }
        source = try { PrivateMediaStorage.file(this, relativeName) }
        catch (_: Exception) { finish(); return }
        if (!source.isFile) { finish(); return }
        pendingExport = savedInstanceState?.getBoolean("pendingExport") ?: false

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(17, 24, 32))
            setPadding(dp(12), dp(12), dp(12), dp(10))
        }
        val back = Button(this).apply { text = "Back to gallery"; setOnClickListener { finish() } }
        root.addView(back)
        root.addView(TextView(this).apply {
            text = if (relativeName.startsWith("ARCHIVED/"))
                "Earlier phone copy · ${source.name}"
            else source.name
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            maxLines = 2
        })

        val extension = source.extension.lowercase(Locale.US)
        when (extension) {
            "jpg", "jpeg", "png" -> {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(source.path, options)
                var sample = 1
                while (options.outWidth / sample > 2300 || options.outHeight / sample > 2300) sample *= 2
                options.inJustDecodeBounds = false
                options.inSampleSize = sample
                val picture = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(BitmapFactory.decodeFile(source.path, options))
                }
                root.addView(picture, LinearLayout.LayoutParams(-1, 0, 1f))
            }
            "mp4" -> {
                video = VideoView(this).apply {
                    setVideoPath(source.path)
                    setMediaController(MediaController(this@PrivateViewerActivity).also { it.setAnchorView(this) })
                    setOnPreparedListener { it.isLooping = false }
                    start()
                }
                root.addView(video, LinearLayout.LayoutParams(-1, 0, 1f))
            }
            else -> {
                val middle = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                }
                middle.addView(TextView(this).apply {
                    text = "Audio recording"; textSize = 20f; setTextColor(Color.WHITE)
                })
                val toggle = Button(this).apply { text = "Play audio" }
                toggle.setOnClickListener {
                    if (player?.isPlaying == true) { player?.pause(); toggle.text = "Play audio" }
                    else {
                        try {
                            if (player == null) {
                                player = MediaPlayer().apply {
                                    setDataSource(source.path)
                                    prepare()
                                    setOnCompletionListener { toggle.text = "Play audio" }
                                }
                            }
                            player?.start(); toggle.text = "Pause audio"
                        } catch (e: Exception) { Toast.makeText(this, "Audio unavailable: ${e.message}", Toast.LENGTH_LONG).show() }
                    }
                }
                middle.addView(toggle)
                root.addView(middle, LinearLayout.LayoutParams(-1, 0, 1f))
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            root.addView(Button(this).apply {
                text = "Copy to Android gallery (no photo access needed)"
                setOnClickListener { publishOwnedMedia() }
            })
        } else {
            root.addView(TextView(this).apply {
                text = "Copying straight into the Android gallery needs Android 10+. Use Export below."
                setTextColor(Color.LTGRAY)
                textSize = 12f
            })
        }
        root.addView(Button(this).apply {
            text = "Export file to a location you choose"
            setOnClickListener { chooseExportLocation() }
        })
        root.addView(TextView(this).apply {
            text = "Private originals are deleted if you uninstall this app. Export important files first."
            setTextColor(Color.LTGRAY)
            textSize = 12f
        })
        setContentView(root)
    }

    private fun chooseExportLocation() {
        pendingExport = true
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime(source)
            putExtra(Intent.EXTRA_TITLE, source.name)
        }
        startActivityForResult(intent, EXPORT_FILE)
    }

    @Deprecated("Compatible with Android API 29+ without an additional Activity library")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != EXPORT_FILE) return
        pendingExport = false
        val destination = if (resultCode == RESULT_OK) data?.data else null
        if (destination != null) {
            worker.execute {
                try {
                    contentResolver.openOutputStream(destination, "w")?.use { out ->
                        source.inputStream().use { it.copyTo(out) }
                    } ?: throw IOException("Cannot open destination")
                    notice("Exported ${source.name}")
                } catch (e: Exception) { notice("Export failed: ${e.message}") }
            }
        }
    }

    private fun publishOwnedMedia() {
        if (Build.VERSION.SDK_INT < 29) {
            notice("Copying into the Android gallery needs Android 10+. Use Export instead.")
            return
        }
        worker.execute {
            var dest: Uri? = null
            try {
                val ext = source.extension.lowercase(Locale.US)
                val collection: Uri
                val folder: String
                when (ext) {
                    "jpg", "jpeg", "png" -> {
                        collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        folder = "${Environment.DIRECTORY_PICTURES}/GlassesDownloader/"
                    }
                    "mp4" -> {
                        collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        folder = "${Environment.DIRECTORY_MOVIES}/GlassesDownloader/"
                    }
                    else -> {
                        collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        folder = "${Environment.DIRECTORY_MUSIC}/GlassesDownloader/"
                    }
                }
                // Check for previously exported owned files; no read access to the user's library.
                val exists = contentResolver.query(collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(source.name, folder), null)?.use { it.moveToFirst() } ?: false
                if (exists) { notice("Already present in Android gallery"); return@execute }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime(source))
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    put(MediaStore.MediaColumns.DATE_MODIFIED, source.lastModified() / 1000)
                }
                dest = contentResolver.insert(collection, values) ?: throw IOException("MediaStore refused file")
                contentResolver.openOutputStream(dest!!, "w")?.use { out ->
                    source.inputStream().use { it.copyTo(out) }
                } ?: throw IOException("Cannot write Android gallery")
                if (contentResolver.update(dest!!,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 0)
                    throw IOException("Couldn't publish gallery item")
                dest = null
                notice("Copied ${source.name} to Android gallery")
            } catch (e: Exception) {
                dest?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) { } }
                notice("Gallery copy failed: ${e.message}")
            }
        }
    }

    private fun mime(file: File): String = when (file.extension.lowercase(Locale.US)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "mp4" -> "video/mp4"
        "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        else -> "application/octet-stream"
    }
    private fun notice(message: String) = runOnUiThread {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    override fun onSaveInstanceState(state: Bundle) {
        state.putBoolean("pendingExport", pendingExport)
        super.onSaveInstanceState(state)
    }
    override fun onPause() {
        video?.pause()
        player?.pause()
        super.onPause()
    }
    override fun onDestroy() {
        player?.release()
        video?.stopPlayback()
        worker.shutdownNow()
        super.onDestroy()
    }
    private fun dp(n: Int): Int = (resources.displayMetrics.density * n + 0.5f).toInt()
}
