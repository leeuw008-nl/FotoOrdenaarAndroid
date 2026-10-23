
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
import android.widget.ProgressBar
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ImageView
import android.widget.GridLayout
import android.widget.FrameLayout
import android.webkit.WebView
import android.webkit.WebViewClient
import android.graphics.Bitmap
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
                requestMediaLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
            } else {
                status.text = "Toegang tot de fotobibliotheek is niet verleend. De app kan daardoor de originele GPS-metadata mogelijk niet vinden."
            }
        }

    private val requestMediaLocation =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                previewFiles()
            } else {
                status.text = "Toegang tot GPS-metadata is niet verleend. Android kan de locatiegegevens afschermen. Verleen de machtiging en probeer opnieuw."
            }
        }

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                sourceTree = uri
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                sourceButton.backgroundTintList = ColorStateList.valueOf(Color.rgb(200, 230, 201))
                previewReady = false
                previewPlans = null
                previewButton.backgroundTintList = null
                previewButton.text = "2. Voorbeeld bekijken"
                moveButton.backgroundTintList = null
                moveButton.text = "3. Foto's verplaatsen"
                status.text = "Bronmap gekozen.\n\nKies eerst ‘Voorbeeld bekijken’."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sourceTree = savedInstanceState?.getString("sourceTree")?.let(Uri::parse)
        explorerOpen = savedInstanceState?.getBoolean("explorerOpen") ?: false

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 24)
        }

        root.addView(TextView(this).apply {
            text = "FotoOrdenaar"
            textSize = 23f
        })

        status = TextView(this).apply {
            textSize = 14f
            text = "Kies de map met foto's. Begin altijd met ‘Voorbeeld bekijken’."
            setPadding(0, 24, 0, 24)
            // Sta toe dat de gebruiker tekst uit de preview kan selecteren en kopiëren.
            setTextIsSelectable(true)
        }

        val scroll = ScrollView(this).apply {
            addView(status)
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        sourceButton = Button(this).apply {
            text = "1. Bronmap kiezen"
            setOnClickListener { pickFolder.launch(null) }
        }
        root.addView(sourceButton)

        previewButton = Button(this).apply {
            text = "2. Voorbeeld bekijken"
            setOnClickListener { previewFiles() }
        }
        root.addView(previewButton)

        moveButton = Button(this).apply {
            text = "3. Foto's verplaatsen"
            setOnClickListener { confirmAndRun() }
        }
        root.addView(moveButton)

        root.addView(Button(this).apply {
            text = "Verkenner: resultaat bekijken"
            setOnClickListener { showExplorer(organizedOnly = true) }
        })

        homeView = root
        setContentView(root)
        sourceTree?.let {
            sourceButton.backgroundTintList = ColorStateList.valueOf(Color.rgb(200, 230, 201))
            status.text = "Eerder gekozen bronmap hersteld. Je kunt het resultaat opnieuw bekijken."
        }
        if (explorerOpen) {
            root.post { showExplorer(organizedOnly = true) }
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
            n.endsWith(".heif") || n.endsWith(".gif") || n.endsWith(".bmp"))
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

    private fun showFullscreenPhoto(uri: Uri, name: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val image = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            loadSampledBitmap(uri, 2600)?.let { setImageBitmap(it) }
        }
        root.addView(image, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.9f
        ))
        root.addView(TextView(this).apply {
            text = name
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(8, 5, 8, 5)
        })

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
        val metadataText = try {
            buildPhotoMetadata(uri, name).ifBlank { "Voor deze foto zijn geen metadata gevonden." }
        } catch (e: Exception) {
            "Metadata uitlezen is mislukt: ${e.javaClass.simpleName}: ${e.message ?: "geen details"}"
        }
        details.addView(TextView(this).apply {
            text = metadataText
            textSize = 14f
            setTextIsSelectable(true)
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
        })
        val coords = cachedGpsCoordinates(name) ?: try {
            val exif = readExif(uri)
            // Gebruik eerst opgeslagen coördinaten; probeer daarna de lichte EXIF-route
            // en de metadata-bibliotheek vóór de volledige XMP-scan.
            gpsCoordinates(exif) ?: alternateGpsCoordinates(uri) ?: xmpGpsCoordinates(uri)
        } catch (_: Exception) { null }

        val mapView = if (coords == null) {
            TextView(this).apply {
                text = "Deze foto bevat geen leesbare GPS-coördinaten. Er kan geen locatiespeld worden getoond.\n\nControleer in Metadata of GPS-breedtegraad en GPS-lengtegraad aanwezig zijn."
                textSize = 15f
                setTextColor(Color.BLACK)
                setBackgroundColor(Color.WHITE)
                setPadding(20, 20, 20, 20)
            }
        } else {
            WebView(this).apply {
                setBackgroundColor(Color.WHITE)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = true
                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView,
                        request: android.webkit.WebResourceRequest,
                        error: android.webkit.WebResourceError
                    ) {
                        if (request.isForMainFrame) {
                            view.loadDataWithBaseURL(
                                null,
                                "<html><body style='font-family:sans-serif;padding:20px;color:#222'>" +
                                    "<h3>Kaart niet geladen</h3><p>Open internetverbinding en probeer opnieuw.</p>" +
                                    "<p>GPS: ${coords[0]}, ${coords[1]}</p></body></html>",
                                "text/html", "UTF-8", null
                            )
                        }
                    }
                }
                val lat = coords[0]
                val lon = coords[1]
                val delta = 0.008
                val mapUrl = "https://www.openstreetmap.org/export/embed.html?bbox=" +
                    "${lon-delta}%2C${lat-delta}%2C${lon+delta}%2C${lat+delta}" +
                    "&layer=mapnik&marker=${lat}%2C${lon}"
                loadUrl(mapUrl)
            }
        }

        val tabBody = FrameLayout(this)
        fun selectTab(map: Boolean) {
            tabBody.removeAllViews()
            tabBody.addView(if (map) mapView else metadataScroll, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            tabs.getChildAt(0).isSelected = !map
            tabs.getChildAt(1).isSelected = map
            (tabs.getChildAt(0) as Button).text = if (!map) "● Metadata" else "Metadata"
            (tabs.getChildAt(1) as Button).text = if (map) "● Kaart" else "Kaart"
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
        openFullScreen(name, root, dark = true)
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
        val photos = mutableListOf<ExplorerEntry>()
        val visited = HashSet<String>()
        fun collect(parent: Uri, depth: Int) {
            if (depth > 20 || photos.size >= 1500) return
            val key = parent.toString()
            if (!visited.add(key)) return
            val entries = try { explorerEntries(parent) } catch (_: Exception) { emptyList() }
            for (entry in entries) {
                if (isImageEntry(entry)) photos.add(entry)
                else if (entry.isDirectory) collect(entry.uri, depth + 1)
                if (photos.size >= 1500) break
            }
        }
        collect(folderUri, 0)
        val scroll = ScrollView(this)
        val grid = GridLayout(this).apply {
            columnCount = 3
            useDefaultMargins = false
            setPadding(4, 4, 4, 4)
        }
        scroll.addView(grid)
        if (photos.isEmpty()) {
            grid.addView(TextView(this).apply {
                text = "Geen foto's in deze map of onderliggende mappen gevonden."
                textSize = 14f
                setPadding(12, 12, 12, 12)
            })
        } else {
            val density = resources.displayMetrics.density
            val screenWidthPx = resources.displayMetrics.widthPixels
            // Drie grote kolommen op een telefoon in portretstand. Elk item krijgt
            // een gelijk deel van de beschikbare breedte, zodat de grid niet links blijft hangen.
            val horizontalPaddingPx = (8 * density).toInt()
            val availableWidthPx = (screenWidthPx - horizontalPaddingPx).coerceAtLeast(1)
            val columns = when {
                screenWidthPx >= (900 * density).toInt() -> 5
                screenWidthPx >= (650 * density).toInt() -> 4
                else -> 3
            }
            grid.columnCount = columns
            val cellWidthPx = availableWidthPx / columns
            for (photo in photos) {
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(2, 3, 2, 3)
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    }
                }
                val thumb = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        cellWidthPx - (8 * density).toInt()
                    )
                    loadSampledBitmap(photo.uri, 600)?.let { setImageBitmap(it) }
                    contentDescription = photo.name
                    setOnClickListener { showFullscreenPhoto(photo.uri, photo.name) }
                }
                tile.addView(thumb)
                tile.addView(TextView(this).apply {
                    text = photo.name
                    textSize = 10f
                    maxLines = 2
                    setPadding(2, 2, 2, 4)
                })
                grid.addView(tile)
            }
        }
        openFullScreen("$folderName — ${photos.size} foto's", scroll)
    }

    private fun showExplorer(organizedOnly: Boolean) {
        val tree = sourceTree
        if (tree == null) {
            status.text = "Kies eerst een bronmap."
            return
        }
        val rootUri = rootDocumentUri(tree)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
        }
        val scroll = ScrollView(this).apply { addView(content) }

        var expandedAll = false
        lateinit var treeContent: LinearLayout

        fun addLevel(parent: Uri, target: LinearLayout, depth: Int, rootLevel: Boolean, expandAll: Boolean = false) {
            val entries = try {
                explorerEntries(parent)
            } catch (e: Exception) {
                target.addView(TextView(this).apply {
                    text = "Mapinhoud kon niet worden gelezen: ${e.message ?: "onbekende fout"}"
                    textSize = 12f
                    setPadding(depth * 24, 8, 8, 8)
                })
                return
            }
            val visibleEntries = if (organizedOnly && rootLevel) {
                entries.filter { it.isDirectory &&
                    (it.name.matches(Regex("\\d{8}")) || it.name == "DATUM_ONBEKEND")
                }
            } else entries

            if (visibleEntries.isEmpty()) {
                target.addView(TextView(this).apply {
                    text = if (rootLevel && organizedOnly) "Nog geen datum-/ordnermappen gevonden." else "(lege map)"
                    textSize = 12f
                    setPadding(depth * 24 + 24, 6, 8, 6)
                })
            }
            for (entry in visibleEntries) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(depth * 22, 2, 4, 2)
                }
                val label = TextView(this).apply {
                    textSize = 12.5f
                    text = if (entry.isDirectory) "▸  📁  ${entry.name}" else {
                        val dimensions = imageDimensions(entry.uri)
                        val sizeText = entry.sizeBytes?.let { formatFileSize(it) } ?: "grootte onbekend"
                        "     📄  ${entry.name}  —  ${dimensions ?: "afmetingen onbekend"}  —  $sizeText"
                    }
                    setPadding(4, 6, 4, 6)
                }
                row.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                target.addView(row)
                if (entry.isDirectory) {
                    val childrenView = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        visibility = if (expandAll) View.VISIBLE else View.GONE
                    }
                    target.addView(childrenView)
                    if (expandAll) {
                        addLevel(entry.uri, childrenView, depth + 1, false, true)
                        label.text = "▾  📂  ${entry.name}"
                    }
                    row.setOnClickListener { showFolderGallery(entry.uri, entry.name) }
                    row.setOnLongClickListener {
                        if (childrenView.visibility == View.VISIBLE) {
                            childrenView.visibility = View.GONE
                            label.text = "▸  📁  ${entry.name}"
                        } else {
                            if (childrenView.childCount == 0) addLevel(entry.uri, childrenView, depth + 1, false)
                            childrenView.visibility = View.VISIBLE
                            label.text = "▾  📂  ${entry.name}"
                        }
                        true
                    }
                } else if (isImageEntry(entry)) {
                    row.setOnClickListener { showFullscreenPhoto(entry.uri, entry.name) }
                }
            }
        }

        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val expandButton = Button(this).apply {
            text = "Alles uitklappen"
            setOnClickListener {
                expandedAll = !expandedAll
                text = if (expandedAll) "Alles inklappen" else "Alles uitklappen"
                treeContent.removeAllViews()
                addLevel(rootUri, treeContent, 0, true, expandedAll)
            }
        }
        controls.addView(expandButton)
        controls.addView(TextView(this).apply {
            text = "Tik op een map voor de galerij; houd een map lang ingedrukt om de boom uit of in te klappen."
            textSize = 12.5f
            setPadding(8, 4, 8, 8)
        })
        content.addView(controls)
        treeContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(treeContent)
        addLevel(rootUri, treeContent, 0, true)
        explorerOpen = true
        openFullScreen(if (organizedOnly) "Verkenner — geordende foto's" else "Verkenner — bronmap", scroll)
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
        val original = originalMediaUri(uri)
        return try {
            contentResolver.openFileDescriptor(original, "r")?.use { pfd ->
                ExifInterface(pfd.fileDescriptor)
            }
        } catch (_: Exception) {
            try {
                contentResolver.openInputStream(original)?.use { ExifInterface(it) }
            } catch (_: Exception) {
                null
            }
        }
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
        if (Build.VERSION.SDK_INT >= 29 && Build.VERSION.SDK_INT <= 32 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestReadImages.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestReadImages.launch(Manifest.permission.READ_MEDIA_IMAGES)
            return
        }
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestMediaLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
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
