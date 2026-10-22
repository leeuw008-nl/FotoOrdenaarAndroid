
package nl.fotoordenaar

import android.app.AlertDialog
import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.exifinterface.media.ExifInterface
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

    private fun readExif(uri: Uri): ExifInterface? = try {
        contentResolver.openInputStream(uri)?.use {
            ExifInterface(it)
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
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes()
            } ?: return null
            // XMP is XML embedded in JPEG/HEIC metadata. Search only the common
            // decimal-degree XMP fields; do not treat zero coordinates as a location.
            val xmp = String(bytes, Charsets.ISO_8859_1)
            fun field(name: String): String? {
                val patterns = listOf(
                    Regex("""(?:exif:)?$name\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
                    Regex("""<[^>]*$name[^>]*>\s*([^<]+)\s*</[^>]+>""", RegexOption.IGNORE_CASE)
                )
                return patterns.firstNotNullOfOrNull { it.find(xmp)?.groupValues?.getOrNull(1)?.trim() }
            }
            fun decimal(value: String?): Double? {
                if (value.isNullOrBlank()) return null
                val cleaned = value.trim().replace(",", ".")
                val match = Regex("""[-+]?[0-9]+(?:\.[0-9]+)?""").find(cleaned) ?: return null
                return match.value.toDoubleOrNull()
            }
            var lat = decimal(field("GPSLatitude")) ?: return null
            var lon = decimal(field("GPSLongitude")) ?: return null
            val latRef = field("GPSLatitudeRef")
            val lonRef = field("GPSLongitudeRef")
            if (latRef.equals("S", true)) lat = -kotlin.math.abs(lat)
            if (latRef.equals("N", true)) lat = kotlin.math.abs(lat)
            if (lonRef.equals("W", true)) lon = -kotlin.math.abs(lon)
            if (lonRef.equals("E", true)) lon = kotlin.math.abs(lon)
            if (lat == 0.0 && lon == 0.0) return null
            if (lat in -90.0..90.0 && lon in -180.0..180.0) doubleArrayOf(lat, lon) else null
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
            val parts = value.trim().split("/")
            return try {
                if (parts.size == 2) {
                    val numerator = parts[0].trim().toDouble()
                    val denominator = parts[1].trim().toDouble()
                    if (denominator == 0.0) null else numerator / denominator
                } else {
                    value.trim().toDouble()
                }
            } catch (_: Exception) {
                null
            }
        }

        fun coordinate(raw: String?, ref: String?): Double? {
            if (raw.isNullOrBlank()) return null
            val values = raw.split(",").mapNotNull { rational(it) }
            if (values.size < 3) return null
            var degrees = values[0] + values[1] / 60.0 + values[2] / 3600.0
            when (ref?.trim()?.uppercase(Locale.US)) {
                "S", "W" -> degrees = -degrees
                "N", "E" -> Unit
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
                latitude in -90.0..90.0 && longitude in -180.0..180.0
            ) {
                return doubleArrayOf(latitude, longitude)
            }
        } catch (_: Exception) {
            // Probeer hieronder de standaardmethode.
        }

        return try {
            val result = FloatArray(2)
            if (exif.getLatLong(result) &&
                result[0].isFinite() && result[1].isFinite()
            ) {
                doubleArrayOf(result[0].toDouble(), result[1].toDouble())
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun gpsDiagnostic(exif: ExifInterface?, coords: DoubleArray?): String {
        if (exif == null) return "GPS-diagnose: EXIF kon niet worden gelezen"

        fun short(tag: String): String =
            exif.getAttribute(tag)?.take(70) ?: "(leeg)"

        val raw = "lat=${short(ExifInterface.TAG_GPS_LATITUDE)}, " +
            "latRef=${short(ExifInterface.TAG_GPS_LATITUDE_REF)}, " +
            "lon=${short(ExifInterface.TAG_GPS_LONGITUDE)}, " +
            "lonRef=${short(ExifInterface.TAG_GPS_LONGITUDE_REF)}"

        return if (coords == null) {
            "GPS-diagnose: coördinaten niet verwerkt; $raw"
        } else {
            "GPS-diagnose: coördinaten gelezen (${"%.5f".format(Locale.US, coords[0])}, " +
                "${"%.5f".format(Locale.US, coords[1])}); $raw"
        }
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
            val date = captureDate(exif)
            val day = dateFolder(date) ?: "DATUM_ONBEKEND"
            val coords = xmpGpsCoordinates(uri) ?: gpsCoordinates(exif)
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
                gpsDiagnostic = gpsDiagnostic(exif, coords),
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

        status.text = "Foto's analyseren…"
        worker.execute {
            val plans = plan()
            runOnUiThread {
                val ok = plans.count { it.issue == null }
                val bad = plans.size - ok
                val sample = plans.take(50).joinToString("\n") {
                    "${it.name}\n  → ${it.dateFolder}/" +
                        "${it.locationFolder}/${it.renamedName}\n  ${it.gpsText}" +
                        (if (it.locationFolder == "Onbekende locatie") "\n  ${it.gpsDiagnostic}" else "") +
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
