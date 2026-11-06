package com.open.glassesdownloader

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** Thumbnails read only our private files, never Android's personal gallery. */
class PrivateGalleryActivity : Activity() {
    private val worker = Executors.newFixedThreadPool(2)
    private val thumbs = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private var records: List<Pair<String, File>> = emptyList()
    private lateinit var grid: GridView
    private lateinit var count: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(17, 24, 32))
            setPadding(dp(12), dp(18), dp(12), dp(8))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(Button(this).apply { text = "Back"; setOnClickListener { finish() } })
        val heading = TextView(this).apply {
            text = "Private gallery"; textSize = 21f; setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0)
        }
        top.addView(heading)
        root.addView(top)
        count = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 13f }
        root.addView(count)
        grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = dp(6)
            verticalSpacing = dp(10)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(0, dp(12), 0, 0)
            clipToPadding = false
            onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, position, _ ->
                startActivity(Intent(this@PrivateGalleryActivity, PrivateViewerActivity::class.java)
                    .putExtra(PrivateViewerActivity.EXTRA_RELATIVE_NAME, records[position].first))
            }
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        records = PrivateMediaStorage.all(this)
        count.text = if (records.isEmpty())
            "No private media yet. Select Private gallery on the downloader screen."
        else "${records.size} local files · Tap one to view or export"
        grid.adapter = GalleryAdapter()
    }

    private inner class GalleryAdapter : BaseAdapter() {
        override fun getCount(): Int = records.size
        override fun getItem(position: Int): Any = records[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val (relativeName, file) = records[position]
            val card: LinearLayout
            val preview: ImageView
            val label: TextView
            if (convertView is LinearLayout && convertView.childCount == 2) {
                card = convertView
                preview = card.getChildAt(0) as ImageView
                label = card.getChildAt(1) as TextView
            } else {
                card = LinearLayout(this@PrivateGalleryActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.rgb(32, 42, 54))
                }
                preview = ImageView(this@PrivateGalleryActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Color.rgb(47, 62, 77))
                    setImageResource(android.R.drawable.ic_menu_gallery)
                }
                card.addView(preview, LinearLayout.LayoutParams(-1, dp(105)))
                label = TextView(this@PrivateGalleryActivity).apply {
                    setTextColor(Color.WHITE)
                    textSize = 10f
                    maxLines = 2
                    setPadding(dp(5), dp(5), dp(5), dp(6))
                }
                card.addView(label)
            }
            preview.tag = relativeName
            label.text = when {
                relativeName.startsWith("ARCHIVED/") -> "Earlier copy · ${file.name}"
                relativeName.lowercase(Locale.US).endsWith(".mp4") -> "▶ ${file.name}"
                else -> file.name
            }
            val cached = thumbs.get(relativeName)
            if (cached != null) {
                preview.setImageBitmap(cached)
            } else {
                preview.setImageResource(android.R.drawable.ic_menu_gallery)
                worker.execute {
                    val bitmap = try { createThumb(file) } catch (_: Exception) { null }
                    if (bitmap != null) {
                        thumbs.put(relativeName, bitmap)
                        runOnUiThread {
                            if (preview.tag == relativeName && !isFinishing) preview.setImageBitmap(bitmap)
                        }
                    }
                }
            }
            return card
        }
    }

    private fun createThumb(file: File): Bitmap? {
        return if (file.extension.equals("mp4", true)) {
            if (Build.VERSION.SDK_INT >= 29) ThumbnailUtils.createVideoThumbnail(file, Size(240, 240), null)
            else {
                @Suppress("DEPRECATION")
                ThumbnailUtils.createVideoThumbnail(file.path, android.provider.MediaStore.Video.Thumbnails.MINI_KIND)
            }
        } else if (file.extension.lowercase(Locale.US) in setOf("jpg", "jpeg", "png")) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            var sample = 1
            while (options.outWidth / sample > 240 || options.outHeight / sample > 240) sample *= 2
            options.inJustDecodeBounds = false
            options.inSampleSize = sample
            BitmapFactory.decodeFile(file.path, options)
        } else null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    override fun onDestroy() {
        worker.shutdownNow()
        thumbs.evictAll()
        super.onDestroy()
    }
}
