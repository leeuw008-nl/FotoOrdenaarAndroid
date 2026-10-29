
package nl.fotoordenaar

import android.app.AlertDialog
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.view.ViewGroup
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.content.res.ColorStateList
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ProgressBar
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ImageView
import android.widget.GridLayout
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.CheckBox
import android.util.LruCache
import android.webkit.WebView
import android.webkit.WebViewClient
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.widget.VideoView
import android.widget.MediaController
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.GpsDirectory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.ExifIFD0Directory
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var sourceButton: Button
    private lateinit var previewButton: Button
    private lateinit var moveButton: Button
    private var sourceTree: Uri? = null
    private var explorerOpen = false
    private var previewReady = false
    private var previewPlans: List<Plan>? = null
    private var moveRunning = false
    private val worker = Executors.newSingleThreadExecutor()
    // Foto- en metadataweergave mag niet wachten op het indexeren van albums.
    private val photoWorker = Executors.newFixedThreadPool(2)
    private val viewerWorker = Executors.newFixedThreadPool(2)
    private val galleryThumbWorker = Executors.newFixedThreadPool(4)
    // Albumkaart scant veel bestanden; mag GPS-uitlezing van een geopende foto niet blokkeren.
    private val albumMapWorker = Executors.newSingleThreadExecutor()
    private val dimensionWorker = Executors.newSingleThreadExecutor()
    // Tijdelijke albumindex en miniaturen blijven beschikbaar bij opnieuw openen.
    // Android mag de bitmapcache automatisch begrenzen op maximaal 32 MiB.
    private val albumCache = mutableMapOf<String, List<ExplorerEntry>>()
    // Miniaturen worden tevens op schijf gecachet, zodat geheugenverlies geen
    // lege Gallery oplevert. Originele foto's blijven ongewijzigd.
    private fun thumbnailFile(uri: Uri): File {
        val key = MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val folder = File(cacheDir, "gallery_thumbnails")
        if (!folder.exists()) folder.mkdirs()
        return File(folder, "$key.jpg")
    }

    private fun galleryThumbnail(uri: Uri): Bitmap? {
        val file = thumbnailFile(uri)
        if (file.isFile) {
            val cached = try { BitmapFactory.decodeFile(file.absolutePath) } catch (_: Exception) { null }
            if (cached != null) return cached
            file.delete()
        }
        val source = loadSampledBitmap(uri, 350) ?: return null
        // Bewaar een kleine thumbnail, niet de mogelijk veel grotere decode-bitmap.
        val ratio = minOf(1f, 350f / maxOf(source.width, source.height).coerceAtLeast(1))
        val thumb = if (ratio < 1f) Bitmap.createScaledBitmap(source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1), true) else source
        try {
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (!temp.renameTo(file)) temp.delete()
        } catch (_: Exception) { /* Miniatuur blijft in geheugen beschikbaar. */ }
        return thumb
    }
    private val thumbnailCache = object : LruCache<String, Bitmap>(32 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private var homeView: View? = null
    private val screenStack = mutableListOf<View>()

    // Bewaar coördinaten die tijdens de fotoanalyse al betrouwbaar zijn bepaald.
    // Daardoor hoeven Kaart en Metadata ze na verplaatsen niet opnieuw te decoderen.
    private val gpsCache by lazy { getSharedPreferences("photo_gps_cache", MODE_PRIVATE) }

    private fun cachedGpsCoordinates(name: String): DoubleArray? {
        val raw = gpsCache.getString(name, null) ?: return null
        val parts = raw.split(",")
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        return if (lat in -90.0..90.0 && lon in -180.0..180.0 && (lat != 0.0 || lon != 0.0))
            doubleArrayOf(lat, lon) else null
    }

    private fun cacheGpsCoordinates(name: String, coords: DoubleArray?) {
        if (coords == null || coords.size < 2) return
        if (!coords[0].isFinite() || !coords[1].isFinite()) return
        if (coords[0] !in -90.0..90.0 || coords[1] !in -180.0..180.0) return
        if (coords[0] == 0.0 && coords[1] == 0.0) return
        gpsCache.edit().putString(name, "${coords[0]},${coords[1]}").apply()
    }

    private val requestReadImages =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) requestMediaLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
            } else {
                status.text = "Fototoegang ontbreekt. GPS-locaties zijn mogelijk niet leesbaar."
            }
        }

    private val requestMediaLocation =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                status.text = "Toegang tot originele GPS-metadata verleend."
            } else {
                status.text = "GPS-toestemming ontbreekt. Android kan locatiegegevens afschermen."
            }
        }

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                sourceTree = uri
                getSharedPreferences("foto_verkenner", MODE_PRIVATE)
                    .edit().putString("selected_tree", uri.toString()).apply()
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {}
                status.text = "Fotomap geselecteerd. Tik op Verkenner openen."
                showExplorer(organizedOnly = false)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sourceTree = savedInstanceState?.getString("sourceTree")?.let(Uri::parse)
            ?: getSharedPreferences("foto_verkenner", MODE_PRIVATE)
                .getString("selected_tree", null)?.let(Uri::parse)
        if (sourceTree != null && contentResolver.persistedUriPermissions.none {
                it.uri == sourceTree && it.isReadPermission
            }) {
            sourceTree = null
            getSharedPreferences("foto_verkenner", MODE_PRIVATE)
                .edit().remove("selected_tree").apply()
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 24, 20, 20)
        }
        root.addView(TextView(this).apply {
            text = "Foto-verkenner"
            textSize = 24f
        })
        status = TextView(this).apply {
            text = "Kies een fotomap om albums, galerij, metadata en kaart te bekijken."
            textSize = 15f
            setPadding(4, 20, 4, 20)
        }
        root.addView(status)
        sourceButton = Button(this).apply {
            text = "Fotomap kiezen"
            setOnClickListener {
                val existing = sourceTree
                if (existing != null) showExplorer(organizedOnly = false)
                else pickFolder.launch(null)
            }
        }
        root.addView(sourceButton)
        previewButton = Button(this)
        moveButton = Button(this)
        root.addView(Button(this).apply {
            text = "Verkenner openen"
            setOnClickListener { showExplorer(organizedOnly = false) }
        })
        homeView = root
        setContentView(root)
        sourceTree?.let { status.text = "Eerder gekozen fotomap beschikbaar. Geen nieuwe maptoestemming nodig." }
        // Hoogstens eenmaal vragen, nooit bij openen van albums of foto's.
        val permissionPrefs = getSharedPreferences("foto_verkenner", MODE_PRIVATE)
        if (!permissionPrefs.getBoolean("media_permissions_asked", false)) {
            permissionPrefs.edit().putBoolean("media_permissions_asked", true).apply()
            val imagePermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
                else Manifest.permission.READ_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT >= 29 &&
                ContextCompat.checkSelfPermission(this, imagePermission) != PackageManager.PERMISSION_GRANTED) {
                requestReadImages.launch(imagePermission)
            } else if (Build.VERSION.SDK_INT >= 29 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestMediaLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        sourceTree?.let { outState.putString("sourceTree", it.toString()) }
        outState.putBoolean("explorerOpen", explorerOpen)
        super.onSaveInstanceState(outState)
    }

    private data class ExplorerEntry(
        val uri: Uri,
        val name: String,
        val isDirectory: Boolean,
        val sizeBytes: Long?
    )

    private fun explorerEntries(parent: Uri): List<ExplorerEntry> {
        val parentDoc = if (DocumentsContract.isTreeUri(parent) &&
            !DocumentsContract.isDocumentUri(this, parent)
        ) rootDocumentUri(parent) else parent
        val id = DocumentsContract.getDocumentId(parentDoc)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentDoc, id)
        val entries = mutableListOf<ExplorerEntry>()
        contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            ),
            null, null,
            "${DocumentsContract.Document.COLUMN_DISPLAY_NAME} COLLATE NOCASE ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                val childId = cursor.getString(idCol) ?: continue
                val name = cursor.getString(nameCol) ?: "(naamloos)"
                val mime = cursor.getString(mimeCol) ?: ""
                entries += ExplorerEntry(
                    DocumentsContract.buildDocumentUriUsingTree(parentDoc, childId),
                    name,
                    mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else null
                )
            }
        }
        return entries
    }

    private fun isImageEntry(entry: ExplorerEntry): Boolean {
        val n = entry.name.lowercase(Locale.ROOT)
        return !entry.isDirectory && (n.endsWith(".jpg") || n.endsWith(".jpeg") ||
            n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".heic") ||
            n.endsWith(".heif") || n.endsWith(".gif") || n.endsWith(".bmp") || isVideo(n))
    }

    private fun loadSampledBitmap(uri: Uri, maxDimension: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                var sample = 1
                while (bounds.outWidth / sample > maxDimension * 2 ||
                    bounds.outHeight / sample > maxDimension * 2) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun openFullScreen(title: String, body: View, dark: Boolean = false) {
        val current = if (screenStack.isEmpty()) homeView else screenStack.lastOrNull()
        if (current != null && (screenStack.isEmpty() || current !== body)) {
            if (screenStack.isEmpty() || screenStack.last() !== current) screenStack.add(current)
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (dark) Color.BLACK else Color.WHITE)
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 2, 8, 2)
            setBackgroundColor(if (dark) Color.BLACK else Color.rgb(235, 239, 243))
        }
        toolbar.addView(Button(this).apply {
            text = "‹ Terug"
            setOnClickListener { closeFullScreen() }
        })
        toolbar.addView(TextView(this).apply {
            text = title
            textSize = 17f
            maxLines = 2
            setPadding(8, 4, 4, 4)
            setTextColor(if (dark) Color.WHITE else Color.BLACK)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        page.addView(toolbar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        page.addView(body, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        screenStack.add(page)
        setContentView(page)
    }

    private fun setPhotoToolbarOverlay(enabled: Boolean) {
        val page = screenStack.lastOrNull() as? LinearLayout ?: return
        if (enabled) {
            if (page.childCount != 2) return
            val toolbar = page.getChildAt(0)
            val body = page.getChildAt(1)
            page.removeAllViews()
            val overlay = FrameLayout(this)
            overlay.addView(body, FrameLayout.LayoutParams(-1, -1))
            overlay.addView(toolbar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            toolbar.setBackgroundColor(Color.argb(145, 0, 0, 0))
            page.addView(overlay, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            if (page.childCount != 1) return
            val overlay = page.getChildAt(0) as? FrameLayout ?: return
            if (overlay.childCount != 2) return
            val body = overlay.getChildAt(0)
            val toolbar = overlay.getChildAt(1)
            overlay.removeAllViews()
            page.removeAllViews()
            toolbar.setBackgroundColor(Color.BLACK)
            page.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
            page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    private fun closeFullScreen() {
        if (screenStack.isNotEmpty()) screenStack.removeAt(screenStack.lastIndex)
        val previous = screenStack.lastOrNull() ?: homeView
        if (previous != null) setContentView(previous)
        if (screenStack.isEmpty()) explorerOpen = false
    }

    @Deprecated("Use system back navigation")
    override fun onBackPressed() {
        if (screenStack.isNotEmpty()) closeFullScreen() else super.onBackPressed()
    }

    private fun isVideo(name: String): Boolean =
        name.matches(Regex("(?i).+\\.(mp4|mov|m4v|3gp|webm|mkv)$"))

    private fun isVideoEntry(entry: ExplorerEntry): Boolean =
        !entry.isDirectory && isVideo(entry.name)

    private fun videoFrame(uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                retriever.setDataSource(fd.fileDescriptor)
                val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime()
                frame?.let {
                    val scale = minOf(1f, 350f / maxOf(it.width, it.height).coerceAtLeast(1))
                    if (scale < 1f) Bitmap.createScaledBitmap(it,
                        (it.width * scale).toInt().coerceAtLeast(1),
                        (it.height * scale).toInt().coerceAtLeast(1), true) else it
                }
            }
        } catch (_: Exception) { null }
        finally { try { retriever.release() } catch (_: Exception) {} }
    }

    private fun openMedia(entry: ExplorerEntry, album: List<ExplorerEntry>? = null, position: Int = 0, fromGallery: Boolean = false) {
        if (isVideoEntry(entry)) openVideo(entry, album, position, fromGallery)
        else showPhotoOnly(entry.uri, entry.name, album, position, fromGallery = fromGallery)
    }

    private fun openVideo(entry: ExplorerEntry, album: List<ExplorerEntry>? = null, position: Int = 0, fromGallery: Boolean = false) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val player = VideoView(this)
        var videoPixelWidth = 0
        var videoPixelHeight = 0
        val videoFrame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        videoFrame.addView(player, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        fun fitVideoToFrame() {
            val w = videoPixelWidth
            val h = videoPixelHeight
            val fw = videoFrame.width
            val fh = videoFrame.height
            if (w > 0 && h > 0 && fw > 0 && fh > 0) {
                val scale = minOf(fw.toFloat() / w, fh.toFloat() / h)
                val newWidth = (w * scale).toInt().coerceAtLeast(1)
                val newHeight = (h * scale).toInt().coerceAtLeast(1)
                if (player.layoutParams.width != newWidth || player.layoutParams.height != newHeight)
                    player.layoutParams = FrameLayout.LayoutParams(newWidth, newHeight, Gravity.CENTER)
            }
        }
        videoFrame.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)
                fitVideoToFrame()
        }
        // Centreer staande video's en behoud de oorspronkelijke beeldverhouding.
        player.setOnPreparedListener { media ->
            val width = media.videoWidth
            val height = media.videoHeight
            videoPixelWidth = width
            videoPixelHeight = height
            videoFrame.post { fitVideoToFrame() }
            player.start()
        }
        root.addView(videoFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        var navigationBar: LinearLayout? = null
        if (album != null && album.size > 1) {
            val navigation = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            navigationBar = navigation
            navigation.addView(Button(this).apply {
                text = "◀ Vorige"
                isEnabled = position > 0
                setOnClickListener {
                    closeFullScreen()
                    openMedia(album[position - 1], album, position - 1, fromGallery)
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            navigation.addView(TextView(this).apply {
                text = "${position + 1} / ${album.size}"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            navigation.addView(Button(this).apply {
                text = "Volgende ▶"
                isEnabled = position < album.lastIndex
                setOnClickListener {
                    closeFullScreen()
                    openMedia(album[position + 1], album, position + 1, fromGallery)
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            root.addView(navigation)
        }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val metadataButton = Button(this).apply { text = "● Metadata" }
        val mapButton = Button(this).apply { text = "Kaart" }
        tabs.addView(metadataButton, LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(mapButton, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(tabs)
        val tabBody = FrameLayout(this)
        root.addView(tabBody, LinearLayout.LayoutParams(-1, 0, 1f))
        val metadata = TextView(this).apply {
            text = "Videometadata laden…"
            textSize = 15f
            setPadding(18, 18, 18, 18)
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
        }
        var coords: DoubleArray? = null
        var metadataLoaded = false
        var showingMap = false
        fun selectTab(map: Boolean) {
            showingMap = map
            metadataButton.text = if (map) "Metadata" else "● Metadata"
            mapButton.text = if (map) "● Kaart" else "Kaart"
            tabBody.removeAllViews()
            if (!map) {
                (metadata.parent as? ViewGroup)?.removeView(metadata)
                tabBody.addView(metadata, FrameLayout.LayoutParams(-1, -1))
            } else {
                val position = coords
                val view: View = if (!metadataLoaded) TextView(this).apply {
                    text = "GPS-locatie lezen…"
                    setTextColor(Color.BLACK)
                    setBackgroundColor(Color.WHITE)
                    setPadding(20, 20, 20, 20)
                } else if (position == null) TextView(this).apply {
                    text = "Geen leesbare GPS-coördinaten gevonden.\nControleer of de app toegang tot originele videolocaties heeft."
                    setTextColor(Color.BLACK)
                    setBackgroundColor(Color.WHITE)
                    textSize = 15f
                    setPadding(20, 20, 20, 20)
                } else WebView(this).apply {
                    setBackgroundColor(Color.WHITE)
                    settings.javaScriptEnabled = true
                    webViewClient = WebViewClient()
                    val lat = position[0]
                    val lon = position[1]
                    val delta = 0.008
                    loadUrl("https://www.openstreetmap.org/export/embed.html?bbox=" +
                        "${lon-delta}%2C${lat-delta}%2C${lon+delta}%2C${lat+delta}" +
                        "&layer=mapnik&marker=${lat}%2C${lon}")
                }
                tabBody.addView(view, FrameLayout.LayoutParams(-1, -1))
            }
        }
        metadataButton.setOnClickListener { selectTab(false) }
        mapButton.setOnClickListener { selectTab(true) }
        selectTab(false)
        val galleryButton = TextView(this).apply {
            text = "▦"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(155, 0, 0, 0))
            contentDescription = "Terug naar Gallery"
            setOnClickListener { closeFullScreen() }
        }
        var fullscreen = false
        var videoToolbar: LinearLayout? = null
        fun updateVideoLayout() {
            tabs.visibility = if (fullscreen) View.GONE else View.VISIBLE
            tabBody.visibility = if (fullscreen) View.GONE else View.VISIBLE
            navigationBar?.visibility = if (fullscreen) View.GONE else View.VISIBLE
            videoToolbar?.visibility = if (fullscreen) View.GONE else View.VISIBLE
            window.decorView.systemUiVisibility = if (fullscreen)
                (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                else 0
            videoFrame.post { fitVideoToFrame() }
        }
        val fullscreenButton = TextView(this).apply {
            text = "⛶"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(155, 0, 0, 0))
            contentDescription = "Video beeldvullend weergeven"
            setOnClickListener {
                fullscreen = !fullscreen
                updateVideoLayout()
                text = if (fullscreen) "⤡" else "⛶"
            }
        }
        openFullScreen(entry.name, root, dark = true)
        val page = screenStack.lastOrNull() as? LinearLayout
        val toolbar = page?.getChildAt(0) as? LinearLayout
        videoToolbar = toolbar
        val iconSize = (46 * resources.displayMetrics.density).toInt()
        val floatingExit = TextView(this).apply {
            text = "⤡"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            contentDescription = "Fullscreen verlaten"
            visibility = View.GONE
            setOnClickListener {
                fullscreen = false
                updateVideoLayout()
                visibility = View.GONE
                fullscreenButton.text = "⛶"
            }
        }
        videoFrame.addView(floatingExit, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.TOP or Gravity.END))
        if (album != null && album.size > 1) {
            val navOverlay = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = View.GONE
                setBackgroundColor(Color.argb(120, 0, 0, 0))
            }
            val prev = Button(this).apply {
                text = "◀"
                isEnabled = position > 0
                setOnClickListener {
                    closeFullScreen()
                    openMedia(album[position - 1], album, position - 1, fromGallery)
                }
            }
            val next = Button(this).apply {
                text = "▶"
                isEnabled = position < album.lastIndex
                setOnClickListener {
                    closeFullScreen()
                    openMedia(album[position + 1], album, position + 1, fromGallery)
                }
            }
            navOverlay.addView(prev)
            navOverlay.addView(TextView(this).apply {
                text = "${position + 1} / ${album.size}"
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(0, -2, 1f))
            navOverlay.addView(next)
            videoFrame.addView(navOverlay, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            videoFrame.setOnClickListener { navOverlay.visibility = if (navOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        fullscreenButton.setOnClickListener {
            fullscreen = !fullscreen
            floatingExit.visibility = if (fullscreen) View.VISIBLE else View.GONE
            fullscreenButton.text = if (fullscreen) "⤡" else "⛶"
            updateVideoLayout()
        }
        toolbar?.addView(galleryButton, LinearLayout.LayoutParams(iconSize, iconSize))
        toolbar?.addView(fullscreenButton, LinearLayout.LayoutParams(iconSize, iconSize))
        val controller = MediaController(this)
        controller.setAnchorView(player)
        player.setMediaController(controller)
        // Afmetingen en afspelen worden hierboven bij OnPrepared ingesteld.
        player.setOnErrorListener { _, _, _ ->
            metadata.text = "Video kan niet worden afgespeeld: ${entry.name}"
            true
        }
        player.setVideoURI(entry.uri)
        photoWorker.execute {
            val (date, gps) = videoMetadata(entry.uri)
            val gpsDiagnostic = if (gps == null) {
                val permission = Build.VERSION.SDK_INT < 29 ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (!permission) "Android-toestemming voor originele medialocaties ontbreekt."
                else "Geen GPS-locatie gevonden in de door Android ontsloten videometadata."
            } else ""
            val dateText = date?.let {
                SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault()).format(it)
            } ?: "niet gevonden"
            val containerTags = videoContainerTags(entry.uri)
            val extraDetails = containerTags.joinToString("\\n") { (name, value) -> "$name: $value" }
            val mediaInfoReport = nativeMediaInfo(entry.uri)
            val details = videoTechnicalInfo(entry.uri) + "\n\nMediaInfoLib:\n" + mediaInfoReport + if (extraDetails.isNotEmpty()) "\\n\\nMP4 / QuickTime metadata:\\n$extraDetails" else ""
            val gpsText = gps?.let {
                "${"%.5f".format(Locale.US, it[0])}, ${"%.5f".format(Locale.US, it[1])}"
            } ?: "niet gevonden"
            runOnUiThread {
                if (root.parent != null) {
                    coords = gps
                    metadataLoaded = true
                    metadata.text = "Video: ${entry.name}\nOpnamedatum: $dateText\nGPS: $gpsText\n$gpsDiagnostic\n\n$details"
                    if (showingMap) selectTab(true)
                }
            }
        }
    }

    private fun videoTechnicalInfo(uri: Uri): String {
        val r = MediaMetadataRetriever()
        return try {
            contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                r.setDataSource(fd.fileDescriptor)
                val lines = mutableListOf<String>()
                fun field(label: String, key: Int, suffix: String = "") {
                    r.extractMetadata(key)?.takeIf { it.isNotBlank() }?.let {
                        lines.add("$label: $it$suffix")
                    }
                }
                field("Breedte", MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, " px")
                field("Hoogte", MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, " px")
                field("Rotatie", MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, "°")
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let {
                    lines.add("Duur: ${it / 60000}m ${(it / 1000) % 60}s")
                }
                field("Videobitrate", MediaMetadataRetriever.METADATA_KEY_BITRATE, " bit/s")
                field("MIME-type", MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                field("Aantal tracks", MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS)
                field("Framerate", MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE, " fps")
                field("Opnamedatum (ruw)", MediaMetadataRetriever.METADATA_KEY_DATE)
                field("Auteur", MediaMetadataRetriever.METADATA_KEY_AUTHOR)
                field("Album", MediaMetadataRetriever.METADATA_KEY_ALBUM)
                field("Titel", MediaMetadataRetriever.METADATA_KEY_TITLE)
                field("Artiest", MediaMetadataRetriever.METADATA_KEY_ARTIST)
                field("Genre", MediaMetadataRetriever.METADATA_KEY_GENRE)
                field("Componist", MediaMetadataRetriever.METADATA_KEY_COMPOSER)
                field("Schijfnummer", MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                field("Encoder", MediaMetadataRetriever.METADATA_KEY_WRITER)
                field("Opnamelocatie (ruw)", MediaMetadataRetriever.METADATA_KEY_LOCATION)
                if (lines.isEmpty()) "Geen aanvullende technische metadata gevonden." else lines.joinToString("\n")
            } ?: "Bestand niet leesbaar."
        } catch (e: Exception) { "Technische metadata niet beschikbaar: ${e.message}" }
        finally { try { r.release() } catch (_: Exception) {} }
    }

    // ISO 6709: +52.12345+006.12345/ (soms met hoogte of ISO-prefix).
    private fun parseVideoGps(raw: String?): DoubleArray? {
        if (raw.isNullOrBlank()) return null
        // MediaInfo: "52.5219°N 6.4245°E" (ook Z/W, komma als decimaalteken).
        val decimal = Regex("""(\d{1,2}(?:[.,]\d+)?)\s*°?\s*([NS])\s*[,;/ ]+\s*(\d{1,3}(?:[.,]\d+)?)\s*°?\s*([EW])""",
            RegexOption.IGNORE_CASE).find(raw)
        if (decimal != null) {
            val lat = decimal.groupValues[1].replace(',', '.').toDoubleOrNull()
            val lon = decimal.groupValues[3].replace(',', '.').toDoubleOrNull()
            if (lat != null && lon != null) {
                val signedLat = if (decimal.groupValues[2].equals("S", true)) -lat else lat
                val signedLon = if (decimal.groupValues[4].equals("W", true)) -lon else lon
                if (signedLat in -90.0..90.0 && signedLon in -180.0..180.0 &&
                    (signedLat != 0.0 || signedLon != 0.0)) return doubleArrayOf(signedLat, signedLon)
            }
        }
        // ISO 6709 in MP4/QuickTime: "+52.5219+006.4245/".
        val iso = Regex("""([+-]\d{1,2}(?:\.\d+)?)([+-]\d{1,3}(?:\.\d+)?)""").find(raw)
        if (iso != null) {
            val lat = iso.groupValues[1].toDoubleOrNull()
            val lon = iso.groupValues[2].toDoubleOrNull()
            if (lat != null && lon != null && lat in -90.0..90.0 &&
                lon in -180.0..180.0 && (lat != 0.0 || lon != 0.0))
                return doubleArrayOf(lat, lon)
        }
        return null
    }

    private fun nativeMediaInfo(uri: Uri): String {
        return try {
            contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                // De native MediaInfo-reader kan de descriptor zelf sluiten.
                // Sluit dezelfde fd daarom niet nogmaals via adoptFd().
                val fd = android.os.ParcelFileDescriptor.dup(descriptor.fileDescriptor).detachFd()
                net.mediaarea.mediainfo.lib.MediaInfoUtil.getMediaInfo(
                    fd, displayName(uri), "Text") ?: ""
            } ?: ""
        } catch (e: Exception) { "MediaInfo-fout: ${e.message}" }
    }

    private fun nativeMediaInfoGps(uri: Uri): DoubleArray? {
        val report = nativeMediaInfo(uri)
        for (line in report.lineSequence()) {
            if (line.contains("location", true) || line.contains("coordinates", true) ||
                line.contains("gps", true) || line.contains("ISO6709", true)) {
                parseVideoGps(line)?.let { return it }
            }
        }
        return null
    }

    private fun videoContainerTags(uri: Uri): List<Pair<String, String>> {
        // Metadata-extractor ondersteunt QuickTime- en MP4-atomen, waaronder
        // locatievelden die Android MediaMetadataRetriever vaak niet teruggeeft.
        return try {
            contentResolver.openInputStream(uri)?.buffered()?.use { stream ->
                val metadata = com.drew.imaging.ImageMetadataReader.readMetadata(stream)
                metadata.directories.flatMap { directory ->
                    directory.tags.mapNotNull { tag ->
                        val value = tag.description?.trim()
                        if (value.isNullOrBlank()) null
                        else Pair("${directory.name} / ${tag.tagName}", value)
                    }
                }.take(180)
            } ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun videoContainerGps(uri: Uri): DoubleArray? {
        val tags = videoContainerTags(uri)
        for ((name, value) in tags) {
            if (name.contains("location", true) || name.contains("gps", true) ||
                name.contains("coordinates", true) || name.contains("ISO6709", true)) {
                parseVideoGps(value)?.let { return it }
            }
        }
        return null
    }

    private fun videoMetadata(uri: Uri): Pair<Date?, DoubleArray?> {
        // De originele MediaStore-URI kan locatiegegevens bevatten die in een
        // gewone content-URI door Android worden afgeschermd.
        val original = try { originalMediaUri(uri) } catch (_: Exception) { uri }
        val candidates = listOf(original, uri).distinct()
        var foundDate: Date? = null
        var foundGps: DoubleArray? = null
        for (candidate in candidates) {
            val retriever = MediaMetadataRetriever()
            try {
                contentResolver.openFileDescriptor(candidate, "r")?.use { descriptor ->
                    retriever.setDataSource(descriptor.fileDescriptor)
                    val rawDate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                    if (foundDate == null) {
                        foundDate = rawDate?.let { raw ->
                            listOf("yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'",
                                "yyyyMMdd'T'HHmmss.SSSZ", "yyyyMMdd'T'HHmmssZ")
                                .firstNotNullOfOrNull { pattern ->
                                    try {
                                        SimpleDateFormat(pattern, Locale.US).apply {
                                            timeZone = java.util.TimeZone.getTimeZone("UTC")
                                            isLenient = false
                                        }.parse(raw)
                                    } catch (_: Exception) { null }
                                }
                        }
                    }
                    if (foundGps == null) {
                        foundGps = parseVideoGps(
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))
                    }
                }
            } catch (_: Exception) {
                // Probeer de oorspronkelijke SAF-URI wanneer MediaStore is afgeschermd.
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
            if (foundDate != null && foundGps != null) break
        }
        return Pair(foundDate, foundGps ?: nativeMediaInfoGps(uri) ?: videoContainerGps(uri))
    }

    private fun showFullscreenPhoto(uri: Uri, name: String, album: List<ExplorerEntry>? = null, position: Int = 0, startPhotoOnly: Boolean = false, fromGallery: Boolean = false) {
        if (isVideo(name)) openVideo(ExplorerEntry(uri, name, false, null), album, position, fromGallery)
        else showPhotoOnly(uri, name, album, position, startPhotoOnly, fromGallery)
    }

    private fun showPhotoOnly(uri: Uri, name: String, album: List<ExplorerEntry>? = null, position: Int = 0, startPhotoOnly: Boolean = false, fromGallery: Boolean = false) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        var photoOnlyState = startPhotoOnly
        val image = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setImageDrawable(null)
            contentDescription = "Foto laden…"
        }
        // Zoom uitsluitend als visuele transformatie op de ImageView:
        // de bestaande FIT_CENTER, metadata, GPS en kaart blijven ongemoeid.
        var zoomFactor = 1f
        var maxZoomFactor = 1f
        var gestureX = 0f
        var gestureY = 0f
        var previousX = 0f
        var previousY = 0f
        var pinched = false
        fun limitPan() {
            val maxX = (image.width * (zoomFactor - 1f) / 2f).coerceAtLeast(0f)
            val maxY = (image.height * (zoomFactor - 1f) / 2f).coerceAtLeast(0f)
            image.translationX = image.translationX.coerceIn(-maxX, maxX)
            image.translationY = image.translationY.coerceIn(-maxY, maxY)
        }
        fun resetPhotoZoom() {
            zoomFactor = 1f
            image.scaleX = 1f
            image.scaleY = 1f
            image.translationX = 0f
            image.translationY = 0f
        }
        val pinchDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (!photoOnlyState) return false
                    zoomFactor = (zoomFactor * detector.scaleFactor).coerceIn(1f, maxZoomFactor)
                    image.scaleX = zoomFactor
                    image.scaleY = zoomFactor
                    limitPan()
                    return true
                }
            })
        image.setOnTouchListener { _, event ->
            if (photoOnlyState) pinchDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureX = event.x
                    gestureY = event.y
                    previousX = event.x
                    previousY = event.y
                    pinched = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> pinched = true
                MotionEvent.ACTION_MOVE -> {
                    if (photoOnlyState && event.pointerCount == 1 &&
                        zoomFactor > 1.01f && !pinchDetector.isInProgress) {
                        image.translationX += event.x - previousX
                        image.translationY += event.y - previousY
                        limitPan()
                    }
                    previousX = event.x
                    previousY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    if (!pinched && (!photoOnlyState || zoomFactor <= 1.01f) &&
                        album != null && album.size > 1) {
                        val delta = event.x - gestureX
                        val next = (position + if (delta < 0f) 1 else -1).coerceIn(0, album.lastIndex)
                        if (kotlin.math.abs(delta) > 75 * resources.displayMetrics.density && next != position) {
                            closeFullScreen()
                            showFullscreenPhoto(album[next].uri, album[next].name, album, next,
                                startPhotoOnly = photoOnlyState, fromGallery = fromGallery)
                        }
                    }
                }
            }
            true
        }
        val photoFrame = FrameLayout(this)
        photoFrame.addView(image, FrameLayout.LayoutParams(-1, -1))
        root.addView(photoFrame, LinearLayout.LayoutParams(-1, 0, 0.9f))
        if (album != null && album.size > 1) {
            val navigation = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            navigation.addView(Button(this).apply {
                text = "◀ Vorige"
                isEnabled = position > 0
                setOnClickListener {
                    closeFullScreen()
                    showFullscreenPhoto(album[position - 1].uri, album[position - 1].name, album, position - 1, startPhotoOnly = photoOnlyState, fromGallery = fromGallery)
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            navigation.addView(TextView(this).apply {
                text = "${position + 1} / ${album.size}"
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(0, -2, 1f))
            navigation.addView(Button(this).apply {
                text = "Volgende ▶"
                isEnabled = position < album.lastIndex
                setOnClickListener {
                    closeFullScreen()
                    showFullscreenPhoto(album[position + 1].uri, album[position + 1].name, album, position + 1, startPhotoOnly = photoOnlyState, fromGallery = fromGallery)
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            root.addView(navigation)
        }
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(235, 239, 243))
        }
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 10, 12, 10)
            setBackgroundColor(Color.WHITE)
        }
        val metadataScroll = ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            isFillViewport = true
            addView(details)
        }
        val metadataLabel = TextView(this).apply {
            text = "Metadata laden…"
            textSize = 14f
            setTextIsSelectable(true)
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
        }
        details.addView(metadataLabel)
        // Dezelfde OSM-tabopbouw als de werkende FotoOrdenaar.
        val mapHolder = FrameLayout(this)
        val tabBody = FrameLayout(this)
        var mapLoaded = false
        fun showMap(coords: DoubleArray?, diagnostic: String = "") {
            val mapView: View = if (coords == null) {
                TextView(this).apply {
                    text = "Geen leesbare GPS-coördinaten gevonden.\n" + diagnostic +
                        "\nControleer of de app toegang tot originele fotolocaties heeft."
                    textSize = 15f
                    setTextColor(Color.BLACK)
                    setBackgroundColor(Color.WHITE)
                    setPadding(20, 20, 20, 20)
                }
            } else WebView(this).apply {
                setBackgroundColor(Color.WHITE)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = true
                webViewClient = WebViewClient()
                val lat = coords[0]
                val lon = coords[1]
                val delta = 0.008
                loadUrl("https://www.openstreetmap.org/export/embed.html?bbox=" +
                    "${lon-delta}%2C${lat-delta}%2C${lon+delta}%2C${lat+delta}" +
                    "&layer=mapnik&marker=${lat}%2C${lon}")
            }
            mapHolder.removeAllViews()
            mapHolder.addView(mapView, FrameLayout.LayoutParams(-1, -1))
        }
        fun selectTab(map: Boolean) {
            tabBody.removeAllViews()
            tabBody.addView(if (map) mapHolder else metadataScroll,
                ViewGroup.LayoutParams(-1, -1))
            (tabs.getChildAt(0) as Button).text = if (!map) "● Metadata" else "Metadata"
            (tabs.getChildAt(1) as Button).text = if (map) "● Kaart" else "Kaart"
            if (map && !mapLoaded) {
                mapLoaded = true
                mapHolder.addView(TextView(this).apply {
                    text = "GPS-locatie lezen…"
                    setPadding(20,20,20,20)
                })
                photoWorker.execute {
                    val cached = cachedGpsCoordinates(name)
                    val exifCoords = try { gpsCoordinates(readExif(uri)) } catch (_: Exception) { null }
                    val altCoords = if (cached == null && exifCoords == null)
                        alternateGpsCoordinates(uri) else null
                    val xmpCoords = if (cached == null && exifCoords == null && altCoords == null)
                        xmpGpsCoordinates(uri) else null
                    val coords = cached ?: exifCoords ?: altCoords ?: xmpCoords
                    val permission = Build.VERSION.SDK_INT < 29 ||
                        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
                    val info = if (!permission) "Android-toestemming 'locatiegegevens van media' ontbreekt.\n"
                        else "EXIF, alternatieve metadata en XMP zijn gecontroleerd.\n"
                    runOnUiThread {
                        if (mapHolder.parent != null) showMap(coords, info)
                    }
                }
            }
        }
        tabs.addView(Button(this).apply {
            text = "Metadata"
            setOnClickListener { selectTab(false) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabs.addView(Button(this).apply {
            text = "Kaart"
            setOnClickListener { selectTab(true) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(tabs)
        root.addView(tabBody, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.1f
        ))
        selectTab(false)
        val galleryButton = TextView(this).apply {
            text = "▦"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(155, 0, 0, 0))
            contentDescription = "Open Gallery van dit album"
        }
        val fullscreenButton = TextView(this).apply {
            text = if (photoOnlyState) "⤡" else "⛶"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(155, 0, 0, 0))
            contentDescription = "Beeldvullende foto aan of uit"
        }
        val iconSize = (46 * resources.displayMetrics.density).toInt()
        fun applyPhotoMode() {
            tabs.visibility = if (photoOnlyState) View.GONE else View.VISIBLE
            tabBody.visibility = if (photoOnlyState) View.GONE else View.VISIBLE
            for (index in 1 until root.childCount) {
                val child = root.getChildAt(index)
                if (child !== tabs && child !== tabBody) child.visibility =
                    if (photoOnlyState) View.GONE else View.VISIBLE
            }
            val params = photoFrame.layoutParams as LinearLayout.LayoutParams
            params.weight = if (photoOnlyState) 1f else 0.9f
            photoFrame.layoutParams = params
            resetPhotoZoom()
            fullscreenButton.text = if (photoOnlyState) "⤡" else "⛶"
            setPhotoToolbarOverlay(photoOnlyState)
        }
        galleryButton.setOnClickListener {
            if (fromGallery) {
                // De bestaande Gallery is nog aanwezig op de navigatiestapel.
                // Keer ernaar terug in plaats van een tweede grid met dezelfde foto's te maken.
                closeFullScreen()
                return@setOnClickListener
            }
            val photos = album ?: listOf(ExplorerEntry(uri, name, false, null))
            val loading = TextView(this).apply {
                text = "Gallery laden…"
                setPadding(24, 24, 24, 24)
            }
            openFullScreen("Gallery", loading)
            renderPhotoGrid(photos, loading)
        }
        fullscreenButton.setOnClickListener {
            photoOnlyState = !photoOnlyState
            applyPhotoMode()
        }
        openFullScreen(name, root, dark = true)
        // Gebruik de bestaande bovenste balk: bestandsnaam staat daar al.
        // Plaats beide knoppen rechts, zonder extra regel onder de foto.
        val photoPage = screenStack.lastOrNull() as? LinearLayout
        val photoToolbar = photoPage?.getChildAt(0) as? LinearLayout
        photoToolbar?.addView(galleryButton, LinearLayout.LayoutParams(iconSize, iconSize))
        photoToolbar?.addView(fullscreenButton, LinearLayout.LayoutParams(iconSize, iconSize))
        applyPhotoMode()
        if (album != null) {
            photoWorker.execute {
                val cached = cachedGpsCoordinates(name)
                val exifCoords = if (cached == null) try { gpsCoordinates(readExif(uri)) } catch (_: Exception) { null } else null
                val altCoords = if (cached == null && exifCoords == null) alternateGpsCoordinates(uri) else null
                val xmpCoords = if (cached == null && exifCoords == null && altCoords == null) xmpGpsCoordinates(uri) else null
                val coords = cached ?: exifCoords ?: altCoords ?: xmpCoords
                runOnUiThread {
                    if (root.parent != null && coords != null && !photoOnlyState) {
                        mapLoaded = true
                        selectTab(true)
                        showMap(coords)
                    }
                }
            }
        }
        viewerWorker.execute {
            val bitmap = try { loadSampledBitmap(uri, 2600) } catch (_: Exception) { null }
            runOnUiThread {
                if (!isFinishing && image.parent != null) {
                    image.setImageBitmap(bitmap)
                    if (bitmap != null) {
                        image.post {
                            val width = image.width.toFloat()
                            val height = image.height.toFloat()
                            if (width > 0f && height > 0f) {
                                val fit = minOf(width / bitmap.width, height / bitmap.height)
                                // 100% = één bronbitmap-pixel per schermpixel.
                                maxZoomFactor = (1f / fit).coerceAtLeast(1f)
                            }
                        }
                    }
                    image.contentDescription = if (bitmap == null) "Foto kon niet worden geladen" else name
                }
            }
        }
        photoWorker.execute {
            val metadata = try {
                buildPhotoMetadata(uri, name).ifBlank { "Geen metadata gevonden." }
            } catch (e: Exception) { "Metadata uitlezen mislukt: ${e.message}" }
            runOnUiThread { if (!isFinishing) metadataLabel.text = metadata }
        }
    }

    private fun buildPhotoMetadata(uri: Uri, name: String): String {
        val out = StringBuilder()
        out.append("Bestand: ").append(name).append("\n")
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME),
                null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        out.append("Bestandsgrootte: ").append(formatFileSize(cursor.getLong(sizeIndex))).append("\n")
                    }
                }
            }
        } catch (_: Exception) {}
        var metadataFound = false
        try {
            contentResolver.openInputStream(originalMediaUri(uri))?.use { input ->
                val metadata = ImageMetadataReader.readMetadata(input)
                out.append("\nMetadata\n")
                for (directory in metadata.directories) {
                    for (tag in directory.tags) {
                        metadataFound = true
                        out.append(directory.name).append(" — ")
                            .append(tag.tagName).append(": ")
                            .append(tag.description ?: "(leeg)").append("\n")
                    }
                }
            }
        } catch (e: Exception) {
            out.append("\nBibliotheekmetadata niet beschikbaar: ").append(e.message ?: e.javaClass.simpleName).append("\n")
        }
        // Toon ook altijd de belangrijkste EXIF-velden via Androids eigen decoder.
        try {
            contentResolver.openInputStream(originalMediaUri(uri))?.use { input ->
                val exif = ExifInterface(input)
                val tags = listOf(
                    ExifInterface.TAG_DATETIME_ORIGINAL to "Opnamedatum",
                    ExifInterface.TAG_DATETIME to "Datum",
                    ExifInterface.TAG_MAKE to "Cameramerk",
                    ExifInterface.TAG_MODEL to "Cameramodel",
                    ExifInterface.TAG_IMAGE_WIDTH to "Breedte",
                    ExifInterface.TAG_IMAGE_LENGTH to "Hoogte",
                    ExifInterface.TAG_F_NUMBER to "Diafragma",
                    ExifInterface.TAG_EXPOSURE_TIME to "Belichtingstijd",
                    ExifInterface.TAG_ISO_SPEED to "ISO",
                    ExifInterface.TAG_FOCAL_LENGTH to "Brandpuntsafstand",
                    ExifInterface.TAG_ORIENTATION to "Oriëntatie",
                    ExifInterface.TAG_GPS_LATITUDE to "GPS-breedtegraad",
                    ExifInterface.TAG_GPS_LONGITUDE to "GPS-lengtegraad"
                )
                var fallbackFound = false
                out.append("\nBelangrijke fotogegevens\n")
                for ((tag, label) in tags) {
                    val value = exif.getAttribute(tag)
                    if (!value.isNullOrBlank()) {
                        fallbackFound = true
                        out.append(label).append(": ").append(value).append("\n")
                    }
                }
                if (!fallbackFound && !metadataFound) out.append("Geen uitleesbare EXIF-metadata gevonden.\n")
            }
        } catch (e: Exception) {
            if (!metadataFound) out.append("Ook EXIF uitlezen is mislukt: ").append(e.message ?: e.javaClass.simpleName).append("\n")
        }
        try {
            val exif = readExif(uri)
            val coords = cachedGpsCoordinates(name) ?: gpsCoordinates(exif) ?: alternateGpsCoordinates(uri) ?: xmpGpsCoordinates(uri)
            if (coords != null) {
                out.append("\nGPS-decimaal: ").append("%.6f".format(Locale.US, coords[0]))
                    .append(", ").append("%.6f".format(Locale.US, coords[1])).append("\n")
            } else {
                out.append("\nGPS: geen coördinaten gevonden in XMP, EXIF of metadata-bibliotheek.\n")
            }
        } catch (e: Exception) {
            out.append("\nGPS uitlezen mislukt: ").append(e.javaClass.simpleName).append("\n")
        }
        return out.toString()
    }

    private fun showFolderGallery(folderUri: Uri, folderName: String) {
        val cacheKey = folderUri.toString()
        val loading = TextView(this).apply { text = "Gallery laden…"; textSize = 16f; setPadding(24,24,24,24) }
        openFullScreen(folderName, loading)
        val cached = albumCache[folderUri.toString()]
        if (cached != null) {
            // Het nieuwe Gallery-scherm moet eerst gekoppeld zijn voordat
            // de miniaturen worden geplaatst (ook bij een cache-hit).
            loading.post {
                if (loading.parent != null) renderPhotoGrid(cached, loading)
            }
            return
        }
        photoWorker.execute {
        val photos = mutableListOf<ExplorerEntry>()
        val visited = HashSet<String>()
        val pending = java.util.ArrayDeque<Uri>()
        pending.add(folderUri)
        while (!pending.isEmpty()) {
            val parent = pending.removeLast()
            if (!visited.add(parent.toString())) continue
            val entries = try { explorerEntries(parent) } catch (_: Exception) { emptyList() }
            for (entry in entries) {
                if (isImageEntry(entry)) photos.add(entry)
                else if (entry.isDirectory) pending.add(entry.uri)
            }
        }
        runOnUiThread {
        if (loading.parent == null) return@runOnUiThread
        albumCache[folderUri.toString()] = photos.toList()
        renderPhotoGrid(photos, loading)
        }
        }
    }

    private fun showAlbumMap(photos: List<ExplorerEntry>, holder: FrameLayout) {
        holder.removeAllViews()
        val status = TextView(this).apply {
            text = "GPS-locaties zoeken… 0 / ${photos.size}"
            setPadding(18, 18, 18, 18)
        }
        holder.addView(status)
        // Apart van het renderen van foto's: de bestaande GPS-methoden blijven ongewijzigd.
        albumMapWorker.execute {
            val pins = mutableListOf<Triple<Int, Double, Double>>()
            photos.forEachIndexed { index, photo ->
                val coords = try {
                    if (isVideoEntry(photo)) {
                        // Video's nooit via de foto-EXIF/XMP-scanners openen:
                        // die kunnen een volledig videobestand in het geheugen laden.
                        videoMetadata(photo.uri).second
                    } else {
                        cachedGpsCoordinates(photo.name)
                            ?: gpsCoordinates(readExif(photo.uri))
                            ?: alternateGpsCoordinates(photo.uri)
                            ?: xmpGpsCoordinates(photo.uri)
                    }
                } catch (_: Exception) { null }
                if (coords != null && coords.size >= 2 && coords[0].isFinite() && coords[1].isFinite() &&
                    coords[0] in -90.0..90.0 && coords[1] in -180.0..180.0 &&
                    (coords[0] != 0.0 || coords[1] != 0.0)) {
                    pins.add(Triple(index, coords[0], coords[1]))
                }
                if ((index + 1) % 20 == 0 || index == photos.lastIndex) {
                    runOnUiThread {
                        if (status.parent != null) status.text =
                            "GPS-locaties zoeken… ${index + 1} / ${photos.size}"
                    }
                }
            }
            runOnUiThread {
                if (holder.parent == null) return@runOnUiThread
                holder.removeAllViews()
                if (pins.isEmpty()) {
                    holder.addView(TextView(this).apply {
                        text = "Geen GPS-coördinaten gevonden in dit album."
                        setPadding(18, 18, 18, 18)
                    })
                    return@runOnUiThread
                }
                val points = pins.joinToString(",") { (index, lat, lon) ->
                    "[${index},${lat},${lon}]"
                }
                val html = """
                    <!doctype html><html><head><meta name="viewport"
                    content="width=device-width, initial-scale=1, maximum-scale=1">
                    <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
                    <style>html,body,#map{height:100%;margin:0} .leaflet-popup-content{font:15px sans-serif}</style>
                    </head><body><div id="map"></div>
                    <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
                    <script>
                    const points=[$points];
                    const map=L.map('map');
                    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
                      {maxZoom:19,attribution:'&copy; OpenStreetMap contributors'}).addTo(map);
                    const bounds=[];
                    points.forEach(p=>{
                      const marker=L.marker([p[1],p[2]]).addTo(map);
                      marker.bindPopup('<a href="fotoalbum://open/'+p[0]+'">Open foto '+(p[0]+1)+'</a>');
                      bounds.push([p[1],p[2]]);
                    });
                    map.fitBounds(bounds,{padding:[30,30],maxZoom:15});
                    </script></body></html>
                """.trimIndent()
                val web = WebView(this).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            if (url?.startsWith("fotoalbum://open/") == true) {
                                val index = url.substringAfterLast('/').toIntOrNull()
                                if (index != null && index in photos.indices) {
                                    showFullscreenPhoto(photos[index].uri, photos[index].name,
                                        photos, index, fromGallery = true)
                                }
                                return true
                            }
                            return false
                        }
                    }
                    loadDataWithBaseURL("https://unpkg.com/", html, "text/html", "UTF-8", null)
                }
                holder.addView(web, FrameLayout.LayoutParams(-1, -1))
            }
        }
    }

    private fun renderPhotoGrid(photos: List<ExplorerEntry>, loading: TextView) {
        val density = resources.displayMetrics.density
        val screenWidthPx = resources.displayMetrics.widthPixels
        val screenWidthDp = (screenWidthPx / density).toInt()
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val columns = when {
            isLandscape && screenWidthDp >= 700 -> 6
            isLandscape -> 5
            screenWidthDp >= 600 -> 4
            else -> 3
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
        }
        val grid = GridLayout(this).apply {
            columnCount = columns
            useDefaultMargins = false
            setPadding(4, 4, 4, 4)
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }
        scroll.addView(grid, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        if (photos.isEmpty()) {
            grid.addView(TextView(this).apply {
                text = "Geen foto's in deze map of onderliggende mappen gevonden."
                textSize = 14f
                setPadding(12, 12, 12, 12)
            })
        } else {
            // Houd de kolommen compact in landscape; gebruik de volledige schermbreedte.
            val horizontalPaddingPx = (8 * density).toInt()
            val availableWidthPx = (screenWidthPx - horizontalPaddingPx).coerceAtLeast(1)
            val cellWidthPx = availableWidthPx / columns
            for (photo in photos) {
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(2, 3, 2, 3)
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = cellWidthPx
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED)
                    }
                }
                val thumb = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        cellWidthPx - (8 * density).toInt()
                    )
                    contentDescription = photo.name
                    setOnClickListener { showFullscreenPhoto(photo.uri, photo.name, photos, photos.indexOf(photo), fromGallery = true) }
                }
                tile.addView(thumb)
                tile.addView(TextView(this).apply {
                    text = photo.name
                    textSize = 10f
                    maxLines = 2
                    setPadding(2, 2, 2, 4)
                })
                grid.addView(tile)
                // Nieuwe Gallery krijgt een eigen thumbnail-wachtrij. Eerdere albums
                // mogen de nieuwe thumbnails niet blokkeren.
                val thumbKey = photo.uri.toString()
                val cachedThumb = synchronized(thumbnailCache) { thumbnailCache.get(thumbKey) }
                if (cachedThumb != null && !cachedThumb.isRecycled) {
                    // Direct instellen: ImageView bewaart de drawable ook voordat
                    // het nieuwe Gallery-scherm aan het venster hangt.
                    thumb.setImageBitmap(cachedThumb)
                } else {
                    galleryThumbWorker.execute {
                        val bitmap = try { if (isVideo(photo.name)) videoFrame(photo.uri) else galleryThumbnail(photo.uri) } catch (_: Exception) { null }
                        if (bitmap != null) synchronized(thumbnailCache) {
                            thumbnailCache.put(thumbKey, bitmap)
                        }
                        runOnUiThread {
                            if (!isFinishing && bitmap != null && !bitmap.isRecycled) {
                                // Niet wachten op View.post(): bij snel teruggaan
                                // en opnieuw openen kan die callback verloren gaan.
                                thumb.setImageBitmap(bitmap)
                            }
                        }
                    }
                }
            }
        }
        val galleryBody = FrameLayout(this)
        galleryBody.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        val mapBody = FrameLayout(this)
        val mapToggle = CheckBox(this).apply {
            text = "Kaartweergave (alle GPS-spelden)"
            setPadding(12, 2, 12, 2)
        }
        val galleryPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(mapToggle, LinearLayout.LayoutParams(-1, -2))
            addView(galleryBody, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        var mapStarted = false
        mapToggle.setOnCheckedChangeListener { _, checked ->
            galleryBody.visibility = if (checked) View.GONE else View.VISIBLE
            if (checked) {
                if (mapBody.parent == null) galleryPage.addView(mapBody,
                    LinearLayout.LayoutParams(-1, 0, 1f))
                mapBody.visibility = View.VISIBLE
                if (!mapStarted) {
                    mapStarted = true
                    showAlbumMap(photos, mapBody)
                }
            } else mapBody.visibility = View.GONE
        }
        val holder = loading.parent as? ViewGroup
        holder?.let { val index = it.indexOfChild(loading); it.removeView(loading); it.addView(galleryPage, index, ViewGroup.LayoutParams(-1,-1)) }
    }

    private fun showPhotoFromFolder(uri: Uri, name: String, folderUri: Uri) {
        val loading = TextView(this).apply {
            text = "Album laden voor bladeren…"
            setPadding(20, 20, 20, 20)
        }
        openFullScreen(name, loading)
        photoWorker.execute {
            val photos = try { explorerEntries(folderUri).filter { isImageEntry(it) }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }) }
                catch (_: Exception) { emptyList() }
            val index = photos.indexOfFirst { it.uri == uri }.coerceAtLeast(0)
            runOnUiThread {
                if (loading.parent != null) {
                    closeFullScreen()
                    if (photos.isNotEmpty()) showFullscreenPhoto(photos[index].uri, photos[index].name, photos, index)
                    else showFullscreenPhoto(uri, name)
                }
            }
        }
    }

    private fun showSearchGallery(rootUri: Uri, term: String) {
        val loading = TextView(this).apply { text = "Zoeken naar $term…"; setPadding(20,20,20,20) }
        openFullScreen("Zoekalbum: $term", loading)
        photoWorker.execute {
            val matches = mutableListOf<ExplorerEntry>()
            val pending = java.util.ArrayDeque<Uri>()
            val visited = HashSet<String>()
            pending.add(rootUri)
            while (!pending.isEmpty()) {
                val uri = pending.removeLast()
                if (!visited.add(uri.toString())) continue
                val entries = try { explorerEntries(uri) } catch (_: Exception) { emptyList() }
                for (entry in entries) {
                    if (entry.isDirectory) pending.add(entry.uri)
                    else if (isImageEntry(entry) && entry.name.contains(term, ignoreCase = true)) matches.add(entry)
                }
            }
            matches.sortBy { it.name.lowercase(Locale.ROOT) }
            runOnUiThread {
                if (loading.parent != null) renderPhotoGrid(matches, loading)
            }
        }
    }

    private fun showExplorer(organizedOnly: Boolean) {
        val tree = sourceTree ?: run {
            status.text = "Kies eerst een fotomap."
            return
        }
        val rootUri = rootDocumentUri(tree)
        val currentFolder = TextView(this).apply {
            text = "Huidige map: ${tree.lastPathSegment ?: "Fotomap"}"
            textSize = 15f
            setPadding(12, 10, 12, 10)
            setTextColor(Color.rgb(15, 69, 122))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 8, 10, 8)
        }
        val scroll = ScrollView(this).apply { addView(content) }
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(currentFolder)
        val allButton = Button(this).apply { text = "Alles uitklappen" }
        root.addView(allButton)
        val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val searchInput = EditText(this).apply { hint = "Zoek op fotonaam"; setSingleLine(true) }
        searchRow.addView(searchInput, LinearLayout.LayoutParams(0, -2, 1f))
        searchRow.addView(Button(this).apply {
            text = "Zoeken"
            setOnClickListener {
                val term = searchInput.text.toString().trim()
                if (term.isBlank()) { searchInput.error = "Zoekterm invullen" }
                else showSearchGallery(rootUri, term)
            }
        })
        root.addView(searchRow)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val cache = java.util.concurrent.ConcurrentHashMap<String, List<ExplorerEntry>>()
        val counts = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()
        val labels = mutableMapOf<String, TextView>()
        val opened = mutableSetOf<String>()
        var expandAll = false
        var generation = 0

        fun sorted(entries: List<ExplorerEntry>): List<ExplorerEntry> =
            entries.sortedWith(compareByDescending<ExplorerEntry> { it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })

        fun countTree() {
            val stack = java.util.ArrayDeque<Pair<Uri, Boolean>>()
            val visited = HashSet<String>()
            stack.add(rootUri to false)
            while (!stack.isEmpty()) {
                val (uri, done) = stack.removeLast()
                val key = uri.toString()
                if (done) {
                    var dirs = 0
                    var photos = 0
                    for (entry in cache[key].orEmpty()) {
                        if (entry.isDirectory) {
                            dirs++
                            counts[entry.uri.toString()]?.let {
                                dirs += it.first
                                photos += it.second
                            }
                        } else if (isImageEntry(entry)) photos++
                    }
                    counts[key] = dirs to photos
                } else if (visited.add(key)) {
                    val entries = try { sorted(explorerEntries(uri)) } catch (_: Exception) { emptyList() }
                    cache[key] = entries
                    stack.add(uri to true)
                    for (entry in entries) if (entry.isDirectory) stack.add(entry.uri to false)
                }
            }
        }

        fun caption(entry: ExplorerEntry, expanded: Boolean): String {
            val c = counts[entry.uri.toString()]
            val total = if (c == null) "(tellen…)" else "(${c.first} subalbums, ${c.second} foto's)"
            return "📁  ${entry.name} $total"
        }

        fun addLevel(parent: Uri, target: LinearLayout, depth: Int, all: Boolean) {
            val entries = cache[parent.toString()]
            if (entries == null) {
                target.addView(TextView(this).apply {
                    text = "Map wordt geladen…"
                    setPadding(12, 8, 8, 8)
                })
                val requested = generation
                worker.execute {
                    val found = try { sorted(explorerEntries(parent)) } catch (_: Exception) { emptyList() }
                    cache[parent.toString()] = found
                    runOnUiThread {
                        if (generation == requested) {
                            target.removeAllViews()
                            addLevel(parent, target, depth, all)
                        }
                    }
                }
                return
            }
            if (entries.isEmpty()) target.addView(TextView(this).apply {
                text = "(lege map)"
                setPadding(12, 8, 8, 8)
            })
            for (entry in entries) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(6, 2, 4, 2)
                }
                val label = TextView(this).apply {
                    textSize = 13f
                    setTextColor(Color.rgb(15, 69, 122))
                    setPadding(6, 10, 6, 10)
                    setBackgroundColor(Color.rgb(239, 245, 251))
                }
                val arrow = if (entry.isDirectory) TextView(this).apply {
                    text = if (all) "▾" else "▸"
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setTextColor(Color.rgb(15, 69, 122))
                    setBackgroundColor(Color.rgb(225, 237, 250))
                    contentDescription = "Album uit- of inklappen"
                } else null
                if (arrow != null) row.addView(arrow, LinearLayout.LayoutParams(
                    (30 * resources.displayMetrics.density).toInt(),
                    (20 * resources.displayMetrics.density).toInt()
                ))
                row.addView(label, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ))
                target.addView(row)
                if (entry.isDirectory) {
                    val key = entry.uri.toString()
                    val children = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(18, 0, 0, 0)
                        visibility = if (all) View.VISIBLE else View.GONE
                    }
                    target.addView(children)
                    if (all) opened.add(key)
                    labels[key] = label
                    label.tag = entry.name
                    label.text = caption(entry, all)
                    val toggleAlbum = {
                        currentFolder.text = "Huidige map: ${entry.name}"
                        val expanded = children.visibility != View.VISIBLE
                        children.visibility = if (expanded) View.VISIBLE else View.GONE
                        if (expanded) {
                            opened.add(key)
                            if (children.childCount == 0) addLevel(entry.uri, children, depth + 1, false)
                        } else {
                            opened.remove(key)
                            children.removeAllViews()
                        }
                        label.text = caption(entry, expanded)
                        arrow?.text = if (expanded) "▾" else "▸"
                    }
                    arrow?.setOnClickListener { toggleAlbum() }
                    // Zoals in FotoOrdenaar: tik op de albumnaam voor de galerij.
                    // De enkele en dubbele pijl blijven uitsluitend voor de boomstructuur.
                    label.setOnClickListener {
                        currentFolder.text = "Huidige map: ${entry.name}"
                        showFolderGallery(entry.uri, entry.name)
                    }
                    label.setOnLongClickListener {
                        currentFolder.text = "Huidige map: ${entry.name}"
                        showFolderGallery(entry.uri, entry.name)
                        true
                    }
                    val subtreeArrow = TextView(this).apply {
                        text = "▾▾"
                        textSize = 14f
                        gravity = Gravity.CENTER
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.rgb(39, 121, 149))
                        contentDescription = "Alle subalbums vanaf hier uitklappen"
                        setOnClickListener {
                            currentFolder.text = "Huidige map: ${entry.name}"
                            if (children.visibility == View.VISIBLE && tag == true) {
                                children.visibility = View.GONE
                                children.removeAllViews()
                                opened.remove(key)
                                arrow?.text = "▸"
                                tag = false
                            } else {
                                children.removeAllViews()
                                children.visibility = View.VISIBLE
                                opened.add(key)
                                arrow?.text = "▾"
                                addLevel(entry.uri, children, depth + 1, true)
                                tag = true
                            }
                        }
                    }
                    row.addView(subtreeArrow, 1, LinearLayout.LayoutParams(
                        (30 * resources.displayMetrics.density).toInt(),
                        (20 * resources.displayMetrics.density).toInt()
                    ))
                    if (all) addLevel(entry.uri, children, depth + 1, true)
                } else {
                    val sizeText = entry.sizeBytes?.let { formatFileSize(it) } ?: "grootte onbekend"
                    label.text = if (isImageEntry(entry))
                        "🖼  ${entry.name}  —  $sizeText  —  afmetingen laden…  ›"
                    else "📄  ${entry.name}  —  $sizeText"
                    if (isImageEntry(entry)) {
                        dimensionWorker.execute {
                            val dimensions = imageDimensions(entry.uri) ?: "afmetingen onbekend"
                            runOnUiThread {
                                if (label.parent != null) label.text =
                                    "🖼  ${entry.name}  —  $sizeText  —  $dimensions  ›"
                            }
                        }
                        label.setOnClickListener {
                            currentFolder.text = "Huidige map: ${entry.name}"
                            showPhotoFromFolder(entry.uri, entry.name, parent)
                        }
                    }
                }
            }
        }

        fun refresh(all: Boolean) {
            generation++
            labels.clear()
            opened.clear()
            content.removeAllViews()
            addLevel(rootUri, content, 0, all)
        }
        allButton.setOnClickListener {
            expandAll = if (opened.isNotEmpty() || expandAll) false else true
            allButton.text = if (expandAll) "Alles inklappen" else "Alles uitklappen"
            if (!expandAll) { currentFolder.text = "Huidige map: hoofdalbum"; scroll.smoothScrollTo(0, 0) }
            refresh(expandAll)
        }
        content.addView(TextView(this).apply { text = "Mappen laden…" })
        explorerOpen = true
        openFullScreen("Foto-verkenner", root)
        // De boom en de totaalaantallen worden buiten de UI-thread ingelezen.
        dimensionWorker.execute {
            cache[rootUri.toString()] = try {
                sorted(explorerEntries(rootUri))
            } catch (_: Exception) { emptyList() }
            runOnUiThread { refresh(false) }
            worker.execute { countTree()
            runOnUiThread {
                for ((key, label) in labels) {
                    val entryName = label.tag as? String ?: continue
                    val c = counts[key] ?: continue
                    val isExpanded = opened.contains(key)
                    label.text = "📁  $entryName (${c.first} subalbums, ${c.second} foto's)"
                }
            }
            }
        }
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    private fun imageDimensions(uri: Uri): String? = try {
        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val exif = ExifInterface(pfd.fileDescriptor)
            val width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            val height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            if (width > 0 && height > 0) "${width} × ${height} px" else null
        }
    } catch (_: Exception) {
        null
    }

    private data class Plan(
        val source: Uri,
        val name: String,
        val dateFolder: String,
        val locationFolder: String,
        val renamedName: String,
        val gpsText: String,
        val gpsDiagnostic: String,
        val coords: DoubleArray? = null,
        val issue: String? = null
    )

    private fun children(tree: Uri): List<Uri> {
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val result = mutableListOf<Uri>()

        contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID
            )
            val nameCol = c.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )
            val mimeCol = c.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )

            while (c.moveToNext()) {
                val name = c.getString(nameCol) ?: ""
                val mime = c.getString(mimeCol) ?: ""
                if (mime.startsWith("image/") ||
                    name.matches(
                        Regex("(?i).+\\.(jpg|jpeg|heic|heif|png|webp|tif|tiff|dng)$")
                    )
                ) {
                    result += DocumentsContract.buildDocumentUriUsingTree(
                        tree, c.getString(idCol)
                    )
                }
            }
        }
        return result
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use {
            if (it.moveToFirst()) return it.getString(0) ?: "foto"
        }
        return "foto"
    }

    // SAF-URI's can expose a redacted image. Resolve the selected image to a unique
    // MediaStore row where possible, then explicitly request its original bytes.
    private fun originalMediaUri(uri: Uri): Uri {
        if (Build.VERSION.SDK_INT < 29) return uri
        if (uri.authority == MediaStore.AUTHORITY) {
            return try { MediaStore.setRequireOriginal(uri) } catch (_: Exception) { uri }
        }

        val name = displayName(uri)
        val size = try {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0).takeIf { it >= 0L } else null
            }
        } catch (_: Exception) { null }

        // Android's document picker commonly returns a SAF URI such as
        // primary:DCIM/Camera/photo.jpg. Use that relative path to disambiguate
        // duplicate filenames in MediaStore instead of relying on name alone.
        val documentPath = try {
            if (uri.authority == "com.android.externalstorage.documents") {
                DocumentsContract.getDocumentId(uri)
                    .substringAfter(':', "")
                    .replace('\\', '/')
                    .takeIf { it.isNotBlank() }
            } else null
        } catch (_: Exception) { null }
        val relativePath = documentPath?.substringBeforeLast('/', "")
            ?.let { if (it.isBlank()) "" else "$it/" }

        return try {
            val matches = mutableListOf<Uri>()
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.RELATIVE_PATH
            )
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
                arrayOf(name),
                null
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val pathCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                while (c.moveToNext()) {
                    val sizeMatches = size == null || c.getLong(sizeCol) == size
                    val candidatePath = c.getString(pathCol)?.replace('\\', '/') ?: ""
                    val pathMatches = relativePath == null ||
                        candidatePath.trimEnd('/') == relativePath.trimEnd('/')
                    if (sizeMatches && pathMatches) {
                        matches += Uri.withAppendedPath(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            c.getLong(idCol).toString()
                        )
                    }
                }
            }
            if (matches.size == 1) MediaStore.setRequireOriginal(matches[0]) else uri
        } catch (_: Exception) {
            uri
        }
    }

    private fun readExif(uri: Uri): ExifInterface? {
        // Eerst de expliciet geselecteerde SAF-foto proberen; originele
        // MediaStore-toegang kan zonder extra permissie SecurityException geven.
        val candidates = listOf(uri, originalMediaUri(uri)).distinct()
        for (candidate in candidates) {
            try {
                contentResolver.openFileDescriptor(candidate, "r")?.use { pfd ->
                    return ExifInterface(pfd.fileDescriptor)
                }
            } catch (_: Exception) {}
            try {
                contentResolver.openInputStream(candidate)?.use { return ExifInterface(it) }
            } catch (_: Exception) {}
        }
        return null
    }

    // Independent fallback parser: Drew Noakes metadata-extractor.
    private fun alternateGpsCoordinates(uri: Uri): DoubleArray? = try {
        contentResolver.openInputStream(originalMediaUri(uri))?.use { input ->
            val metadata = ImageMetadataReader.readMetadata(input)
            val gps = metadata.getFirstDirectoryOfType(GpsDirectory::class.java)?.geoLocation
            if (gps != null && gps.latitude.isFinite() && gps.longitude.isFinite() &&
                gps.latitude in -90.0..90.0 && gps.longitude in -180.0..180.0 &&
                (gps.latitude != 0.0 || gps.longitude != 0.0)
            ) doubleArrayOf(gps.latitude, gps.longitude) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun alternateCaptureDate(uri: Uri): Date? = try {
        contentResolver.openInputStream(uri)?.use { input ->
            val metadata = ImageMetadataReader.readMetadata(input)
            metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)?.dateOriginal
                ?: metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
                    ?.getDate(ExifIFD0Directory.TAG_DATETIME)
        }
    } catch (_: Exception) {
        null
    }

    private fun safePart(value: String): String =
        value.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .take(80)
            .ifBlank { "Onbekend" }

    private fun captureDate(exif: ExifInterface?): Date? {
        val raw = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif?.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
            ?: exif?.getAttribute(ExifInterface.TAG_DATETIME)

        if (raw.isNullOrBlank()) return null

        return listOf(
            "yyyy:MM:dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss"
        ).firstNotNullOfOrNull { pattern ->
            try {
                SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                }.parse(raw)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun dateFolder(date: Date?): String? =
        date?.let {
            SimpleDateFormat("yyyyMMdd", Locale.US).format(it)
        }

    private fun renamedFileName(name: String, date: Date): String {
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0) name.substring(dot).lowercase(Locale.US) else ""
        val stamp = SimpleDateFormat(
            "yyyyMMddHHmmss", Locale.US
        ).format(date)
        return stamp + ext
    }

    private fun xmpGpsCoordinates(uri: Uri): DoubleArray? {
        return try {
            val bytes = contentResolver.openInputStream(originalMediaUri(uri))?.use { input ->
                // Read the full JPEG/XMP packet; a single read() may return only
                // a partial buffer and miss the GPS fields near the end.
                input.readBytes()
            } ?: return null
            // XMP kan in JPEG's als XML/RDF zijn ingebed. Lees zowel de gangbare
            // velden als veelvoorkomende varianten met prefixen uit verschillende namespaces.
            // GPS-velden als GPSPosition en accepteer decimale graden én DMS-notatie.
            val xmp = String(bytes, Charsets.ISO_8859_1)

            fun field(vararg names: String): String? {
                for (name in names) {
                    val escaped = Regex.escape(name)
                    val patterns = listOf(
                        Regex("""(?:[\w.-]+:)?$escaped\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
                        Regex("""<(?:[\w.-]+:)?$escaped(?:\s[^>]*)?>\s*([^<]+)\s*</(?:[\w.-]+:)?$escaped\s*>""", RegexOption.IGNORE_CASE)
                    )
                    for (pattern in patterns) {
                        val value = pattern.find(xmp)?.groupValues?.getOrNull(1)?.trim()
                        if (!value.isNullOrBlank()) return value
                    }
                }
                return null
            }

            fun parseCoordinate(raw: String?, positive: Char, negative: Char): Double? {
                if (raw.isNullOrBlank()) return null
                val value = raw.trim()
                val ref = value.lastOrNull()?.uppercaseChar()
                val signFromRef = when (ref) {
                    positive -> 1.0
                    negative -> -1.0
                    else -> 1.0
                }
                // XMP-exif schrijft coördinaten vaak als graden, minuten en optioneel
                // seconden, bijvoorbeeld "52, 30.5N". Rationalen uit EXIF zijn ook geldig.
                val numbers = Regex("""[-+]?\d+(?:\.\d+)?(?:\s*/\s*\d+(?:\.\d+)?)?""")
                    .findAll(value)
                    .mapNotNull { token ->
                        val parts = token.value.split("/")
                        try {
                            val numerator = parts[0].trim().toDouble()
                            if (parts.size == 1) numerator
                            else {
                                val denominator = parts[1].trim().toDouble()
                                if (denominator == 0.0) null else numerator / denominator
                            }
                        } catch (_: Exception) { null }
                    }.toList()
                if (numbers.isEmpty()) return null

                val magnitude = when {
                    numbers.size >= 3 -> kotlin.math.abs(numbers[0]) +
                        kotlin.math.abs(numbers[1]) / 60.0 +
                        kotlin.math.abs(numbers[2]) / 3600.0
                    numbers.size == 2 && ref in listOf(positive, negative) ->
                        kotlin.math.abs(numbers[0]) + kotlin.math.abs(numbers[1]) / 60.0
                    else -> numbers[0]
                }
                val explicitNegative = value.startsWith("-")
                return magnitude * if (explicitNegative) -1.0 else signFromRef
            }

            var lat = parseCoordinate(field("GPSLatitude"), 'N', 'S')
            var lon = parseCoordinate(field("GPSLongitude"), 'E', 'W')
            val latRef = field("GPSLatitudeRef")?.trim()?.uppercase(Locale.US)
            val lonRef = field("GPSLongitudeRef")?.trim()?.uppercase(Locale.US)
            if (lat != null && latRef == "S") lat = -kotlin.math.abs(lat)
            if (lat != null && latRef == "N") lat = kotlin.math.abs(lat)
            if (lon != null && lonRef == "W") lon = -kotlin.math.abs(lon)
            if (lon != null && lonRef == "E") lon = kotlin.math.abs(lon)

            // Sommige camera's bewaren de positie als één tekstveld, bijvoorbeeld
            // "52,30.5N 006,12.4E".
            if (lat == null || lon == null) {
                val position = field("GPSPosition", "GPSCoordinates")
                if (!position.isNullOrBlank()) {
                    val pair = Regex(
                        """([-+]?\d+(?:\s*,\s*\d+(?:\.\d+)?){0,2}\s*[NS])\s*[,; ]+\s*([-+]?\d+(?:\s*,\s*\d+(?:\.\d+)?){0,2}\s*[EW])""",
                        RegexOption.IGNORE_CASE
                    ).find(position)
                    if (pair != null) {
                        lat = parseCoordinate(pair.groupValues[1], 'N', 'S')
                        lon = parseCoordinate(pair.groupValues[2], 'E', 'W')
                    }
                }
            }

            if (lat != null && lon != null &&
                (lat != 0.0 || lon != 0.0) &&
                lat in -90.0..90.0 && lon in -180.0..180.0
            ) doubleArrayOf(lat, lon) else null
        } catch (_: Exception) {
            null
        }
    }
    private fun gpsCoordinates(exif: ExifInterface?): DoubleArray? {
        if (exif == null) return null

        // Lees eerst de ruwe GPS-tags. Sommige foto's bevatten geldige GPS-tags
        // die getLatLong() niet correct teruggeeft.
        fun rational(value: String?): Double? {
            if (value.isNullOrBlank()) return null
            // Accepteer zowel "52/1" als "52 / 1", plus losse decimale waarden.
            val cleaned = value.trim().replace(Regex("\\s+"), "")
            return try {
                val slash = cleaned.indexOf('/')
                if (slash >= 0) {
                    val numerator = cleaned.substring(0, slash).toDouble()
                    val denominator = cleaned.substring(slash + 1).toDouble()
                    if (denominator == 0.0) null else numerator / denominator
                } else {
                    cleaned.toDouble()
                }
            } catch (_: Exception) {
                null
            }
        }

        fun coordinate(raw: String?, ref: String?): Double? {
            if (raw.isNullOrBlank()) return null
            val values = raw.split(',').mapNotNull { rational(it) }
            if (values.size < 3) return null
            var degrees = kotlin.math.abs(values[0]) +
                kotlin.math.abs(values[1]) / 60.0 +
                kotlin.math.abs(values[2]) / 3600.0
            // Sommige bestanden geven de Ref-tag met extra spaties of kleine letters.
            // Als de Ref ontbreekt, laat getLatLong() hieronder nog een kans krijgen.
            when (ref?.trim()?.firstOrNull()?.uppercaseChar()) {
                'S', 'W' -> degrees = -degrees
                'N', 'E' -> Unit
                else -> return null
            }
            return degrees
        }

        try {
            val latitude = coordinate(
                exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE),
                exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF)
            )
            val longitude = coordinate(
                exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE),
                exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF)
            )
            if (latitude != null && longitude != null &&
                latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
                (latitude != 0.0 || longitude != 0.0)
            ) {
                return doubleArrayOf(latitude, longitude)
            }
        } catch (_: Exception) {
            // Probeer hieronder de standaardmethode.
        }

        return try {
            val result = FloatArray(2)
            if (exif.getLatLong(result) &&
                result[0].isFinite() && result[1].isFinite() &&
                (result[0] != 0f || result[1] != 0f)
            ) {
                doubleArrayOf(result[0].toDouble(), result[1].toDouble())
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun uriAccessDiagnostic(uri: Uri): String {
        val original = originalMediaUri(uri)
        val readPermission = when {
            Build.VERSION.SDK_INT >= 33 ->
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            Build.VERSION.SDK_INT >= 23 ->
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            else -> true
        }
        val locationPermission = Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        val mapping = when {
            uri.authority == MediaStore.AUTHORITY -> "bron is al MediaStore"
            original != uri -> "unieke MediaStore-match gevonden"
            else -> "geen unieke MediaStore-match; SAF-URI behouden"
        }
        return "Toegangsdiagnose: bron-authority=${uri.authority ?: "(leeg)"}; " +
            "origineel-authority=${original.authority ?: "(leeg)"}; MediaStore-koppeling=${mapping}; " +
            "leesmachtiging=${if (readPermission) "ja" else "nee"}; " +
            "ACCESS_MEDIA_LOCATION=${if (locationPermission) "ja" else "nee"}"
    }

    private fun gpsDiagnostic(uri: Uri, exif: ExifInterface?, coords: DoubleArray?): String {
        fun short(tag: String): String =
            exif?.getAttribute(tag)?.take(70) ?: "(leeg)"

        val raw = "EXIF lat=${short(ExifInterface.TAG_GPS_LATITUDE)}, " +
            "latRef=${short(ExifInterface.TAG_GPS_LATITUDE_REF)}, " +
            "lon=${short(ExifInterface.TAG_GPS_LONGITUDE)}, " +
            "lonRef=${short(ExifInterface.TAG_GPS_LONGITUDE_REF)}"

        val xmpInfo = try {
            val bytes = contentResolver.openInputStream(originalMediaUri(uri))?.use { input ->
                val buffer = ByteArray(1024 * 1024)
                val count = input.read(buffer)
                if (count <= 0) ByteArray(0) else buffer.copyOf(count)
            }
            if (bytes == null) {
                "XMP-diagnose: bestand kon niet worden geopend"
            } else {
                val xmp = String(bytes, Charsets.ISO_8859_1)
                fun context(term: String): String {
                    val index = xmp.indexOf(term, ignoreCase = true)
                    if (index < 0) return "(niet aangetroffen)"
                    val start = maxOf(0, index - 55)
                    val end = minOf(xmp.length, index + 145)
                    return xmp.substring(start, end).replace(Regex("\\s+"), " ").take(200)
                }
                "XMP-diagnose: bytes=${bytes.size}; GPSLatitude=${context("GPSLatitude")}; " +
                    "GPSLongitude=${context("GPSLongitude")}; GPSPosition=${context("GPSPosition")}"
            }
        } catch (e: Exception) {
            "XMP-diagnose: leesfout ${e.javaClass.simpleName}"
        }

        val result = if (coords == null) {
            "GPS-diagnose: coördinaten niet verwerkt; $raw"
        } else {
            "GPS-diagnose: coördinaten gelezen (${"%.5f".format(Locale.US, coords[0])}, " +
                "${"%.5f".format(Locale.US, coords[1])}); $raw"
        }
        return "${uriAccessDiagnostic(uri)}\n$result\n$xmpInfo"
    }

    private fun locationFolder(exif: ExifInterface?, coordsOverride: DoubleArray? = null): String {
        val coords = coordsOverride ?: gpsCoordinates(exif) ?: return "Onbekende locatie"

        return try {
            val geocoder = Geocoder(this, Locale("nl", "NL"))
            val addresses: List<Address> =
                if (Build.VERSION.SDK_INT >= 33) {
                    var found: List<Address> = emptyList()
                    val latch = CountDownLatch(1)
                    geocoder.getFromLocation(coords[0], coords[1], 1) {
                        found = it ?: emptyList()
                        latch.countDown()
                    }
                    latch.await(12, TimeUnit.SECONDS)
                    found
                } else {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(coords[0], coords[1], 1)
                        ?: emptyList()
                }

            val address = addresses.firstOrNull()
            if (address == null) {
                return gpsFolder(coords)
            }

            val gemeente = safePart(
                address.subAdminArea
                    ?: address.locality
                    ?: address.adminArea
                    ?: "Onbekende gemeente"
            )
            val plaats = safePart(
                address.locality
                    ?: address.subLocality
                    ?: address.featureName
                    ?: "Onbekende plaats"
            )
            val locatie = safePart(
                address.thoroughfare
                    ?: address.featureName
                    ?: address.subLocality
                    ?: "Locatie onbekend"
            )

            "${gemeente}_${plaats}_${locatie}"
        } catch (_: Exception) {
            gpsFolder(coords)
        }
    }

    private fun gpsFolder(coords: DoubleArray): String =
        "GPS_${"%.5f".format(Locale.US, coords[0])}_" +
            "${"%.5f".format(Locale.US, coords[1])}"

    private fun rootDocumentUri(tree: Uri): Uri {
        val id = DocumentsContract.getTreeDocumentId(tree)
        return DocumentsContract.buildDocumentUriUsingTree(tree, id)
    }

    private fun findChildFolder(parent: Uri, name: String): Uri? {
        val id = DocumentsContract.getDocumentId(parent)
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(parent, id)

        contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name &&
                    c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                ) {
                    return DocumentsContract.buildDocumentUriUsingTree(
                        parent, c.getString(0)
                    )
                }
            }
        }
        return null
    }

    private fun createFolder(parent: Uri, name: String): Uri {
        // A document URI created from a tree also contains "/tree/".
        // Only convert the original tree URI to its root document; do not
        // accidentally redirect nested location folders back to the album root.
        val actualParent =
            if (DocumentsContract.isTreeUri(parent) &&
                !DocumentsContract.isDocumentUri(this, parent)
            ) rootDocumentUri(parent)
            else parent

        findChildFolder(actualParent, name)?.let { return it }

        return DocumentsContract.createDocument(
            contentResolver,
            actualParent,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name
        ) ?: throw IllegalStateException("Kon map niet aanmaken: $name")
    }

    private fun findChildFile(parent: Uri, name: String): Uri? {
        val id = DocumentsContract.getDocumentId(parent)
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(parent, id)

        contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(
                        parent, c.getString(0)
                    )
                }
            }
        }
        return null
    }

    private fun plan(onProgress: (Int) -> Unit = {}): List<Plan> {
        val tree = sourceTree ?: return emptyList()
        val plans = mutableListOf<Plan>()
        val uris = children(tree)

        for ((index, uri) in uris.withIndex()) {
            val exif = readExif(uri)
            val name = displayName(uri)
            val date = captureDate(exif) ?: alternateCaptureDate(uri)
            val day = dateFolder(date) ?: "DATUM_ONBEKEND"
            val coords = gpsCoordinates(exif) ?: alternateGpsCoordinates(uri) ?: xmpGpsCoordinates(uri)
            val gpsText = if (coords == null) {
                "GPS niet gevonden in EXIF"
            } else {
                "GPS ${"%.5f".format(Locale.US, coords[0])}, " +
                    "${"%.5f".format(Locale.US, coords[1])}"
            }

            plans += Plan(
                source = uri,
                name = name,
                dateFolder = day,
                locationFolder = locationFolder(exif, coords),
                renamedName = date?.let { renamedFileName(name, it) } ?: name,
                gpsText = gpsText,
                gpsDiagnostic = gpsDiagnostic(uri, exif, coords),
                coords = coords,
                issue = if (date == null) {
                    "Geen bruikbare EXIF-opnamedatum"
                } else null
            )
            onProgress(if (uris.isEmpty()) 100 else ((index + 1) * 100 / uris.size))
        }
        return plans
    }

    private fun setButtonProgress(button: Button, percent: Int, completed: Boolean = false) {
        val base = GradientDrawable().apply {
            setColor(Color.rgb(238, 238, 238))
            cornerRadius = 18f
        }
        val fill = ClipDrawable(ColorDrawable(Color.rgb(200, 230, 201)), Gravity.START, ClipDrawable.HORIZONTAL)
        val layers = LayerDrawable(arrayOf(base, fill)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
        button.backgroundTintList = null
        button.background = layers
        layers.findDrawableByLayerId(android.R.id.progress).level =
            if (completed) 10000 else percent.coerceIn(0, 100) * 100
        button.invalidate()
    }

    private fun previewFiles() {
        if (sourceTree == null) {
            status.text = "Kies eerst een bronmap."
            return
        }
        previewReady = false
        setButtonProgress(previewButton, 0)
        previewButton.text = "2. FOTO'S ANALYSEREN — 0%"
        setButtonProgress(moveButton, 0)
        moveButton.text = "3. Foto's verplaatsen"
        status.text = "Foto's analyseren…"
        worker.execute {
            val plans = plan { percent ->
                runOnUiThread {
                    setButtonProgress(previewButton, percent)
                    previewButton.text = "2. FOTO'S ANALYSEREN — $percent%"
                }
            }
            runOnUiThread {
                val ok = plans.count { it.issue == null }
                val bad = plans.size - ok
                val sample = plans.take(50).joinToString("\n") {
                    "${it.name}\n  → ${it.dateFolder}/" +
                        "${it.locationFolder}/${it.renamedName}\n  ${it.gpsText}" +
                        "\n  ${it.gpsDiagnostic}" +
                        (it.issue?.let { e -> "\n  OVERGESLAGEN: $e" } ?: "")
                }
                previewPlans = plans
                previewReady = true
                setButtonProgress(previewButton, 100, completed = true)
                previewButton.text = "2. FOTO'S ANALYSEREN — GEREED (${plans.size})"
                status.text =
                    "Voorbeeld gereed\nTotaal: ${plans.size}" +
                    "\nMet datum: $ok" +
                    "\nZonder bruikbare datum: $bad\n\n" +
                    sample.ifBlank { "Geen foto's gevonden." }
            }
        }
    }

    private fun confirmAndRun() {
        if (sourceTree == null) {
            status.text = "Kies eerst een bronmap."
            return
        }

        val tree = sourceTree ?: return
        val sourceName = try {
            contentResolver.query(tree, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) { null } ?: "Geselecteerde bronmap"
        val message = TextView(this).apply {
            textSize = 16f
            setPadding(48, 24, 48, 8)
            text = SpannableString("Bronmap: $sourceName\\n\\nFoto's worden verplaatst naar Datummap/Locatiemap/Bestandsnaam. Maak eerst een reservekopie. Doorgaan?\\n\\nBronmap openen")
            movementMethod = LinkMovementMethod.getInstance()
            val s = text as SpannableString
            val label = "Bronmap openen"
            val start = s.indexOf(label)
            if (start >= 0) s.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(rootDocumentUri(tree), "vnd.android.document/directory").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    } catch (_: Exception) {
                        status.text = "De bestandsbeheerder kan deze bronmaplink niet rechtstreeks openen. Bronmap: $sourceName"
                    }
                }
            }, start, start + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        AlertDialog.Builder(this)
            .setTitle("Foto's verplaatsen?")
            .setView(message)
            .setNegativeButton("Annuleren", null)
            .setPositiveButton("Verplaatsen") { _, _ -> moveFiles() }
            .show()
    }

    private fun moveFiles() {
        val tree = sourceTree ?: return
        moveRunning = true
        setButtonProgress(moveButton, 0)
        moveButton.text = "3. Foto's verplaatsen — 0%"
        status.text = "Verwerken gestart… Laat de app open."

        worker.execute {
            val plans = previewPlans ?: plan()
            var moved = 0
            var skipped = 0
            var errors = 0
            val log = StringBuilder()

            for ((index, p) in plans.withIndex()) {
                if (p.issue != null) {
                    skipped++
                    log.append("Overgeslagen: ${p.name} — ${p.issue}\n")
                } else {
                var target: Uri? = null
                try {
                    val day = createFolder(tree, p.dateFolder)
                    val location = createFolder(day, p.locationFolder)

                    var targetName = p.renamedName
                    if (findChildFile(location, targetName) != null) {
                        val dot = targetName.lastIndexOf('.')
                        val stem = if (dot > 0) targetName.substring(0, dot)
                            else targetName
                        val ext = if (dot > 0) targetName.substring(dot) else ""
                        targetName = "${stem}_${System.currentTimeMillis()}$ext"
                    }

                    target = DocumentsContract.createDocument(
                        contentResolver,
                        location,
                        contentResolver.getType(p.source)
                            ?: "application/octet-stream",
                        targetName
                    ) ?: throw IllegalStateException("Kon doelbestand niet maken")

                    contentResolver.openInputStream(p.source).use { input ->
                        contentResolver.openOutputStream(target, "w").use { output ->
                            if (input == null || output == null) {
                                throw IllegalStateException("Bestand niet toegankelijk")
                            }
                            input.copyTo(output)
                            output.flush()
                        }
                    }

                    if (!DocumentsContract.deleteDocument(contentResolver, p.source)) {
                        throw IllegalStateException(
                            "Kopie gemaakt, maar origineel niet verwijderd"
                        )
                    }
                    // Bewaar dezelfde gevonden coördinaten onder de uiteindelijke bestandsnaam,
                    // zodat Kaart en Metadata ze na verplaatsen rechtstreeks kunnen gebruiken.
                    cacheGpsCoordinates(targetName, p.coords)
                    cacheGpsCoordinates(p.name, p.coords)
                    moved++
                } catch (e: Exception) {
                    try {
                        target?.let { DocumentsContract.deleteDocument(contentResolver, it) }
                    } catch (_: Exception) {
                    }
                    errors++
                    log.append("Fout: ${p.name} — ${e.message}\n")
                }
                }
                val percent = if (plans.isEmpty()) 100 else ((index + 1) * 100 / plans.size)
                runOnUiThread {
                    setButtonProgress(moveButton, percent, completed = percent == 100)
                    moveButton.text = "3. Foto's verplaatsen — ${percent}%"
                    status.text = "Foto's verplaatsen: ${percent}%"
                }
            }

            runOnUiThread {
                setButtonProgress(moveButton, 100, completed = true)
                moveButton.text = "3. FOTO'S VERPLAATSEN — GEREED ($moved verplaatst, $skipped overgeslagen, $errors fouten)"
                status.text =
                    "Klaar\nVerplaatst: $moved\nOvergeslagen: $skipped" +
                    "\nFouten: $errors\n\n${log.toString().take(5000)}"

                val albumMessage = TextView(this).apply {
                    textSize = 16f
                    setPadding(48, 24, 48, 8)
                    val linkText = SpannableString(
                        "Verplaatst: $moved\nOvergeslagen: $skipped\nFouten: $errors\n\nAlbum openen"
                    )
                    val label = "Album openen"
                    val start = linkText.indexOf(label)
                    if (start >= 0) linkText.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            try {
                                startActivity(
                                    Intent(Intent.ACTION_VIEW)
                                        .setDataAndType(rootDocumentUri(tree), "vnd.android.document/directory")
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                )
                            } catch (_: Exception) {
                                status.text = "Albumlink kon niet rechtstreeks worden geopend. Open de gekozen bronmap in Bestanden."
                            }
                        }
                    }, start, start + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text = linkText
                    movementMethod = LinkMovementMethod.getInstance()
                }
                AlertDialog.Builder(this)
                    .setTitle("FotoOrdenaar klaar")
                    .setView(albumMessage)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
