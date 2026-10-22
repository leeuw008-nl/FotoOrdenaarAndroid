
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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
    private var sourceTree: Uri? = null
    private val worker = Executors.newSingleThreadExecutor()

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
                status.text = "Bronmap gekozen.\n\nKies eerst ‘Voorbeeld bekijken’."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 24)
        }

        root.addView(TextView(this).apply {
            text = "FotoOrdenaar"
            textSize = 26f
        })

        status = TextView(this).apply {
            textSize = 15f
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

        root.addView(Button(this).apply {
            text = "1. Bronmap kiezen"
            setOnClickListener { pickFolder.launch(null) }
        })

        root.addView(Button(this).apply {
            text = "2. Voorbeeld bekijken"
            setOnClickListener { previewFiles() }
        })

        root.addView(Button(this).apply {
            text = "3. Foto's verplaatsen"
            setOnClickListener { confirmAndRun() }
        })

        setContentView(root)
    }

    private data class Plan(
        val source: Uri,
        val name: String,
        val dateFolder: String,
        val locationFolder: String,
        val renamedName: String,
        val gpsText: String,
        val gpsDiagnostic: String,
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

        return try {
            val matches = mutableListOf<Uri>()
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.SIZE),
                "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
                arrayOf(name),
                null
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                while (c.moveToNext()) {
                    if (size == null || c.getLong(sizeCol) == size) {
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
                val buffer = ByteArray(1024 * 1024)
                val count = input.read(buffer)
                if (count <= 0) ByteArray(0) else buffer.copyOf(count)
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
        return "$result\n$xmpInfo"
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
        val actualParent =
            if (DocumentsContract.isTreeUri(parent)) rootDocumentUri(parent)
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

    private fun plan(): List<Plan> {
        val tree = sourceTree ?: return emptyList()
        val plans = mutableListOf<Plan>()

        for (uri in children(tree)) {
            val exif = readExif(uri)
            val name = displayName(uri)
            val date = captureDate(exif) ?: alternateCaptureDate(uri)
            val day = dateFolder(date) ?: "DATUM_ONBEKEND"
            val coords = xmpGpsCoordinates(uri) ?: gpsCoordinates(exif) ?: alternateGpsCoordinates(uri)
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
                issue = if (date == null) {
                    "Geen bruikbare EXIF-opnamedatum"
                } else null
            )
        }
        return plans
    }

    private fun previewFiles() {
        if (sourceTree == null) {
            status.text = "Kies eerst een bronmap."
            return
        }
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestMediaLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
            return
        }

        status.text = "Foto's analyseren…"
        worker.execute {
            val plans = plan()
            runOnUiThread {
                val ok = plans.count { it.issue == null }
                val bad = plans.size - ok
                val sample = plans.take(50).joinToString("\n") {
                    "${it.name}\n  → ${it.dateFolder}/" +
                        "${it.locationFolder}/${it.renamedName}\n  ${it.gpsText}" +
                        "\n  ${it.gpsDiagnostic}" +
                        (it.issue?.let { e -> "\n  OVERGESLAGEN: $e" } ?: "")
                }
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

        AlertDialog.Builder(this)
            .setTitle("Foto's verplaatsen?")
            .setMessage(
                "Foto's worden verplaatst naar Datummap/Locatiemap/" +
                    "Bestandsnaam. Maak eerst een reservekopie. Doorgaan?"
            )
            .setNegativeButton("Annuleren", null)
            .setPositiveButton("Verplaatsen") { _, _ -> moveFiles() }
            .show()
    }

    private fun moveFiles() {
        val tree = sourceTree ?: return
        status.text = "Verwerken gestart… Laat de app open."

        worker.execute {
            val plans = plan()
            var moved = 0
            var skipped = 0
            var errors = 0
            val log = StringBuilder()

            for (p in plans) {
                if (p.issue != null) {
                    skipped++
                    log.append("Overgeslagen: ${p.name} — ${p.issue}\n")
                    continue
                }

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

            runOnUiThread {
                status.text =
                    "Klaar\nVerplaatst: $moved\nOvergeslagen: $skipped" +
                    "\nFouten: $errors\n\n${log.toString().take(5000)}"

                AlertDialog.Builder(this)
                    .setTitle("FotoOrdenaar klaar")
                    .setMessage(
                        "Verplaatst: $moved\nOvergeslagen: $skipped\nFouten: $errors"
                    )
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
