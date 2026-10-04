package ai.opencyvis.backend

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * Writes plain-text files into the app's dedicated shared-storage folder
 * (Download/OpenCyvis) via MediaStore.Downloads.
 *
 * Runs in the app process — no privileged backend involved. Android's scoped
 * storage enforces the security model for us:
 *  - New files are recorded with owner_package_name = this app.
 *  - Only files this app created can be overwritten; MediaProvider rejects
 *    writes to rows owned by other packages.
 *  - Without storage permissions, MediaStore queries return only rows this
 *    app owns — exactly the ownership rule we rely on for the overwrite check.
 *  - If the requested name is taken by a file this app did not create, the
 *    system writes the file under an auto-generated name (e.g. "report (1).txt");
 *    the actual name is read back and reported to the LLM.
 *
 * All writes are UTF-8 and capped at [MAX_CONTENT_BYTES].
 */
object FileTextWriter {

    /** App's exclusive folder under public Download. */
    const val ROOT_RELATIVE_PATH = "Download/OpenCyvis"

    const val MAX_CONTENT_BYTES = 262_144  // 256 KB

    /** Max directory levels below Download/OpenCyvis (guards absurd nesting). */
    private const val MAX_SUBDIR_DEPTH = 5

    private const val MAX_COMPONENT_LENGTH = 96
    private const val MAX_FILENAME_LENGTH = 256

    data class WriteResult(
        val ok: Boolean,
        val path: String,          // absolute /sdcard/... path actually written
        val displayName: String,   // final file name (may differ on rename)
        val overwritten: Boolean,  // replaced an existing file created by this app
        val renamed: Boolean,      // name was taken by a non-own file, system renamed
        val bytes: Int,
        val error: String? = null,
    )

    private fun fail(error: String) = WriteResult(
        ok = false, path = "", displayName = "", overwritten = false, renamed = false,
        bytes = 0, error = error
    )

    internal data class ParsedPath(val displayName: String, val relativePath: String) {
        /** RELATIVE_PATH is stored with a trailing slash on most devices. */
        val relativePathStored: String get() = "$relativePath/"
    }

    /**
     * Parses an LLM-supplied filename relative to Download/OpenCyvis.
     * Returns null for blank names, absolute paths, traversal, backslashes,
     * trailing slashes, or oversize components.
     */
    internal fun parse(filename: String): ParsedPath? {
        val trimmed = filename.trim()
        if (trimmed.isBlank() || trimmed.length > MAX_FILENAME_LENGTH) return null
        if (trimmed.startsWith("/") || trimmed.endsWith("/") || trimmed.contains("\\")) return null
        val parts = trimmed.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > MAX_SUBDIR_DEPTH + 1) return null
        if (parts.any { it == "." || it == ".." || it.length > MAX_COMPONENT_LENGTH }) return null
        val dirParts = parts.dropLast(1)
        val relativePath = (listOf("Download", "OpenCyvis") + dirParts).joinToString("/")
        return ParsedPath(parts.last(), relativePath)
    }

    fun write(context: Context, filename: String, content: String): WriteResult {
        val parsed = parse(filename) ?: return fail(
            "Invalid filename '$filename' — use a relative path under " +
                "$ROOT_RELATIVE_PATH, e.g. 'report.txt' or 'notes/2026.txt' " +
                "(no leading '/', no '..', no trailing '/')"
        )
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_CONTENT_BYTES) {
            return fail("Content too large (${bytes.size} bytes; limit is $MAX_CONTENT_BYTES)")
        }

        val resolver = context.contentResolver

        // 1. Overwrite in place if this app already owns a file with the same name.
        val ownUri = queryOwnRow(resolver, parsed)
        if (ownUri != null) {
            return try {
                resolver.openOutputStream(ownUri, "w")!!.use { it.write(bytes) }
                WriteResult(
                    ok = true,
                    path = "/sdcard/${parsed.relativePath}/${parsed.displayName}",
                    displayName = parsed.displayName,
                    overwritten = true,
                    renamed = false,
                    bytes = bytes.size,
                )
            } catch (e: Exception) {
                fail("Cannot overwrite ${parsed.displayName}: ${e.message}")
            }
        }

        // 2. Insert a new file; the system auto-renames if the name is taken
        //    by a file this app does not own.
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, parsed.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, parsed.relativePath)
        }
        return try {
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return fail("MediaStore rejected creating ${parsed.displayName}")
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            // Read back the actual name/dir — the system may have renamed the file.
            var actualName = parsed.displayName
            var actualDir = parsed.relativePath
            resolver.query(
                uri,
                arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH
                ),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    actualName = c.getString(0) ?: actualName
                    actualDir = (c.getString(1) ?: parsed.relativePathStored).trimEnd('/')
                }
            }
            WriteResult(
                ok = true,
                path = "/sdcard/$actualDir/$actualName",
                displayName = actualName,
                overwritten = false,
                renamed = actualName != parsed.displayName,
                bytes = bytes.size,
            )
        } catch (e: Exception) {
            fail("Cannot create ${parsed.displayName}: ${e.message}")
        }
    }

    /** Returns the Uri of an existing file with this exact name/dir owned by this app. */
    private fun queryOwnRow(resolver: android.content.ContentResolver, parsed: ParsedPath): Uri? {
        // Match both RELATIVE_PATH storage forms (with and without trailing slash).
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
            "(${MediaStore.MediaColumns.RELATIVE_PATH}=? OR ${MediaStore.MediaColumns.RELATIVE_PATH}=?)"
        val args = arrayOf(parsed.displayName, parsed.relativePathStored, parsed.relativePath)
        return try {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                selection, args, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)
                    )
                } else null
            }
        } catch (_: Exception) {
            // Query failure degrades to insert-with-auto-rename — the safe direction.
            null
        }
    }
}
