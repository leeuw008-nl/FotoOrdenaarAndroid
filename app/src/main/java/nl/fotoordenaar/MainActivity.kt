package nl.fotoordenaar

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.exifinterface.media.ExifInterface
import android.location.Geocoder
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private var sourceTree: Uri? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            sourceTree = uri
            contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            status.text = "Bronmap gekozen:\n${uri}\n\nKies eerst ‘Voorbeeld bekijken’. Er worden dan nog geen foto's verplaatst."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 24)
        }
        val title = TextView(this).apply {
            text = "FotoOrdenaar"
            textSize = 26f
        }
        status = TextView(this).apply {
            textSize = 16f
            text = "Kies de map met foto's. De app verwerkt alleen foto's die direct in die map staan; aangemaakte submappen worden niet opnieuw ingelezen."
            setPadding(0, 24, 0, 24)
        }
        val choose = Button(this).apply {
            text = "1. Bronmap kiezen"
            setOnClickListener { pickFolder.launch(null) }
        }
        val preview = Button(this).apply {
            text = "2. Voorbeeld bekijken"
            setOnClickListener { previewFiles() }
        }
        val run = Button(this).apply {
            text = "3. Foto's verplaatsen"
            setOnClickListener { confirmAndRun() }
        }
        root.addView(title)
        root.addView(status, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(choose)
        root.addView(preview)
        root.addView(run)
        setContentView(root)
    }

    private data class Plan(
        val source: Uri, val name: String, val dateFolder: String,
        val locationFolder: String, val issue: String? = null
    )

    private fun children(tree: Uri): List<Uri> {
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val result = mutableListOf<Uri>()
        contentResolver.query(childrenUri, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        ), null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (c.moveToNext()) {
                val name = c.getString(nameCol) ?: ""
                val mime = c.getString(mimeCol) ?: ""
                if (mime.startsWith("image/") || name.matches(Regex("(?i).+\\.(jpg|jpeg|heic|heif|png|webp|tif|tiff|dng)$"))) {
                    result += DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(idCol))
                }
            }
        }
        return result
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0) ?: "foto"
        }
        return "foto"
    }

    private fun readExif(uri: Uri): ExifInterface? = try {
        contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
    } catch (_: Exception) { null }

    private fun safePart(s: String): String = s.trim()
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), " ")
        .take(80)
        .ifBlank { "Onbekend" }

    private fun dateFolder(exif: ExifInterface?, uri: Uri): String? {
        val raw = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif?.getAttribute(ExifInterface.TAG_DATETIME)
        if (!raw.isNullOrBlank()) {
            val parsed = listOf("yyyy:MM:dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss")
                .firstNotNullOfOrNull { pattern ->
                    try { SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(raw) }
                    catch (_: Exception) { null }
                }
            if (parsed != null) return SimpleDateFormat("yyyyMMdd", Locale.US).format(parsed)
        }
        // No fallback to file modified time: it is often the copy date, not the capture date.
        return null
    }

    private fun locationFolder(exif: ExifInterface?): String {
        if (exif == null) return "Onbekende locatie"
        val latLong = exif.latLong ?: return "Onbekende locatie"
        return try {
            val geocoder = Geocoder(this, Locale("nl", "NL"))
            val addresses = if (Build.VERSION.SDK_INT >= 33) {
                var found: List<android.location.Address> = emptyList()
                val latch = java.util.concurrent.CountDownLatch(1)
                geocoder.getFromLocation(latLong[0], latLong[1], 1) { list ->
                    found = list ?: emptyList()
                    latch.countDown()
                }
                latch.await(12, java.util.concurrent.TimeUnit.SECONDS)
                found
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(latLong[0], latLong[1], 1) ?: emptyList()
            }
            val a = addresses.firstOrNull() ?: return "GPS_${"%.5f".format(Locale.US, latLong[0])}_${"%.5f".format(Locale.US, latLong[1])}"
            val gemeente = safePart(a.subAdminArea ?: a.adminArea ?: "Onbekende gemeente")
            val plaats = safePart(a.locality ?: a.subLocality ?: a.featureName ?: "Onbekende plaats")
            val locatie = safePart(a.featureName ?: a.thoroughfare ?: a.subLocality ?: "Locatie onbekend")
            "${gemeente}_${plaats}_${locatie}"
        } catch (_: Exception) {
            val ll = exif.latLong!!
            "GPS_${"%.5f".format(Locale.US, ll[0])}_${"%.5f".format(Locale.US, ll[1])}"
        }
    }

    private fun rootDocumentUri(tree: Uri): Uri {
        val id = DocumentsContract.getTreeDocumentId(tree)
        return DocumentsContract.buildDocumentUriUsingTree(tree, id)
    }

    private fun createFolder(parent: Uri, name: String): Uri {
        val actualParent = if (DocumentsContract.isTreeUri(parent)) rootDocumentUri(parent) else parent
        val existing = findChildFolder(actualParent, name)
        if (existing != null) return existing
        val created = DocumentsContract.createDocument(contentResolver, actualParent, DocumentsContract.Document.MIME_TYPE_DIR, name)
        return created ?: throw IllegalStateException("Kon map niet aanmaken: $name")
    }

    private fun findChildFolder(parent: Uri, name: String): Uri? {
        val id = DocumentsContract.getDocumentId(parent)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, id)
        contentResolver.query(childrenUri, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        ), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name && c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    return DocumentsContract.buildDocumentUriUsingTree(parent, c.getString(0))
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
            val date = dateFolder(exif, uri)
            plans += Plan(uri, displayName(uri), date ?: "DATUM_ONBEKEND",
                locationFolder(exif), if (date == null) "Geen bruikbare EXIF-opnamedatum" else null)
        }
        return plans
    }

    private fun previewFiles() {
        val tree = sourceTree
        if (tree == null) {
            status.text = "Kies eerst een bronmap."
            return
        }
        status.text = "Foto's analyseren… Dit kan even duren bij veel foto's."
        worker.execute {
            val plans = plan()
            runOnUiThread {
                val ok = plans.count { it.issue == null }
                val bad = plans.size - ok
                val sample = plans.take(30).joinToString("\n") {
                    "${it.name}  →  ${it.dateFolder}/${it.locationFolder}" +
                        (it.issue?.let { e -> "  [OVERGESLAGEN: $e]" } ?: "")
                }
                status.text = "Voorbeeld gereed\nTotaal: ${plans.size}\nMet datum: $ok\nZonder bruikbare datum (worden niet verplaatst): $bad\n\n${sample.ifBlank { "Geen afbeeldingsbestanden gevonden." }}"
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
            .setMessage("De app verplaatst foto's met een leesbare EXIF-opnamedatum naar dag- en locatiemappen binnen de gekozen map. Bestanden zonder datum blijven staan. Maak bij voorkeur eerst een reservekopie. Doorgaan?")
            .setNegativeButton("Annuleren", null)
            .setPositiveButton("Verplaatsen") { _, _ -> moveFiles() }
            .show()
    }

    private fun moveFiles() {
        status.text = "Verwerken gestart… Laat de app open tot de melding klaar verschijnt."
        worker.execute {
            val tree = sourceTree!!
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
                try {
                    val day = createFolder(tree, p.dateFolder)
                    val location = createFolder(day, p.locationFolder)
                    var targetName = p.name
                    if (findChildFile(location, targetName) != null) {
                        val dot = p.name.lastIndexOf('.')
                        val stem = if (dot > 0) p.name.substring(0, dot) else p.name
                        val ext = if (dot > 0) p.name.substring(dot) else ""
                        targetName = "${stem}_${System.currentTimeMillis()}$ext"
                    }
                    val target = DocumentsContract.createDocument(contentResolver, location,
                        contentResolver.getType(p.source) ?: "application/octet-stream", targetName)
                        ?: throw IllegalStateException("Kon doelbestand niet maken")
                    try {
                        contentResolver.openInputStream(p.source).use { input ->
                            contentResolver.openOutputStream(target, "w").use { output ->
                                if (input == null || output == null) throw IllegalStateException("Bestand niet toegankelijk")
                                input.copyTo(output)
                                output.flush()
                            }
                        }
                        // Delete original only after successful byte-for-byte stream copy.
                        if (!DocumentsContract.deleteDocument(contentResolver, p.source)) {
                            throw IllegalStateException("Kopie gemaakt, maar origineel kon niet worden verwijderd")
                        }
                        moved++
                    } catch (e: Exception) {
                        try { DocumentsContract.deleteDocument(contentResolver, target) } catch (_: Exception) {}
                        throw e
                    }
                } catch (e: Exception) {
                    errors++
                    log.append("Fout: ${p.name} — ${e.message}\n")
                }
            }
            runOnUiThread {
                status.text = "Klaar\nVerplaatst: $moved\nOvergeslagen: $skipped\nFouten: $errors\n\n${log.toString().take(5000)}"
                AlertDialog.Builder(this)
                    .setTitle("FotoOrdenaar klaar")
                    .setMessage("Verplaatst: $moved\nOvergeslagen: $skipped\nFouten: $errors")
                    .setPositiveButton("OK", null).show()
            }
        }
    }

    private fun findChildFile(parent: Uri, name: String): Uri? {
        val id = DocumentsContract.getDocumentId(parent)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, id)
        contentResolver.query(childrenUri, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        ), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name)
                return DocumentsContract.buildDocumentUriUsingTree(parent, c.getString(0))
        }
        return null
    }
}
