package ai.opencyvis.backend

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Reads plain-text files from allowlisted shared-storage directories.
 *
 * Shared by [PrivilegedService.readTextFile] (privileged process, shell/system uid) and
 * [SystemBackend.readTextFile] (system flavor, in-process) so validation and decoding
 * behave identically on both sides.
 *
 * Hard constraints (see read-file-capability-plan.md §8):
 *  - Path allowlist: only Download/Documents under shared storage, checked AFTER
 *    canonicalization so traversal (`../`) and symlink escapes are rejected.
 *  - Size cap: at most [DEFAULT_MAX_BYTES] bytes are read (Binder transaction limit and
 *    LLM token cost); the whole file is never loaded into memory.
 *  - Encoding: strict UTF-8 first, GB18030 (GBK superset) fallback; malformed input is
 *    never silently replaced with '?'.
 *  - Binary rejection: a NUL byte in the first 512 bytes means the file is not text.
 */
object FileTextReader {

    const val DEFAULT_MAX_BYTES = 65_536

    private const val BINARY_SNIFF_BYTES = 512

    private val ALLOWED_ROOTS = listOf(
        "/sdcard/Download",
        "/sdcard/Documents",
        "/storage/emulated/0/Download",
        "/storage/emulated/0/Documents",
    )

    /** Canonicalized allowlist roots; resolves the /sdcard symlink on device. */
    private val canonicalRoots: Set<String> by lazy {
        ALLOWED_ROOTS.mapNotNull { root ->
            try {
                File(root).canonicalPath
            } catch (_: IOException) {
                null
            }
        }.toSet()
    }

    data class Result(
        val text: String,
        val truncated: Boolean,
        val size: Long,
        val encoding: String,
    )

    class FileReadException(message: String) : Exception(message)

    fun read(path: String, maxBytes: Int = DEFAULT_MAX_BYTES): Result {
        val file = validateFile(path)
        val size = file.length()
        if (size == 0L) return Result("", false, 0L, "UTF-8")

        val cap = maxBytes.coerceIn(1, DEFAULT_MAX_BYTES)
        val buffer = ByteArray(cap)
        val read = try {
            var n = 0
            file.inputStream().use { input ->
                while (n < cap) {
                    val r = input.read(buffer, n, cap - n)
                    if (r < 0) break
                    n += r
                }
            }
            n
        } catch (e: IOException) {
            throw FileReadException("Cannot read file: ${e.message}")
        }

        if (isBinary(buffer, read)) {
            throw FileReadException(
                "Binary file detected (contains NUL bytes); only plain-text files are supported"
            )
        }

        val truncated = read.toLong() < size
        val (text, encoding) = decodeCapped(buffer.copyOf(read), truncated)
        return Result(text, truncated, size, encoding)
    }

    /** Validates the path against the allowlist and returns the canonical file. */
    private fun validateFile(path: String): File {
        if (path.isBlank()) throw FileReadException("Path is empty")
        val canonical = try {
            File(path).canonicalPath
        } catch (e: IOException) {
            throw FileReadException("Cannot resolve path: ${e.message}")
        }
        if (!isAllowed(canonical)) {
            throw FileReadException(
                "Path is outside the allowed directories " +
                    "(only Download and Documents under shared storage): $canonical"
            )
        }
        val file = File(canonical)
        if (!file.isFile) throw FileReadException("File not found: $canonical")
        return file
    }

    internal fun isAllowed(canonicalPath: String): Boolean {
        return canonicalRoots.any { root ->
            canonicalPath == root || canonicalPath.startsWith("$root/")
        }
    }

    /** True if the first [length] bytes of [buffer] look like a binary file. */
    internal fun isBinary(buffer: ByteArray, length: Int): Boolean {
        for (i in 0 until minOf(length, BINARY_SNIFF_BYTES)) {
            if (buffer[i] == 0.toByte()) return true
        }
        return false
    }

    /**
     * Decodes [bytes] as strict UTF-8 first, falling back to GB18030. When the buffer was
     * cut mid-file ([truncated]), up to 3 trailing bytes are dropped so a multi-byte
     * character split at the cap boundary does not corrupt the whole decode; non-truncated
     * input is always decoded strictly.
     */
    internal fun decodeCapped(bytes: ByteArray, truncated: Boolean): Pair<String, String> {
        val charsets = listOf(Charsets.UTF_8, Charset.forName("GB18030"))
        for (charset in charsets) {
            decodeStrict(bytes, charset)?.let { return it to charset.name() }
            if (truncated) {
                for (drop in 1..3) {
                    if (bytes.size <= drop) break
                    decodeStrict(bytes.copyOf(bytes.size - drop), charset)?.let {
                        return it to charset.name()
                    }
                }
            }
        }
        throw FileReadException(
            "Cannot decode file content (neither valid UTF-8 nor GBK; " +
                "it may be binary or use an unsupported encoding)"
        )
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? {
        return try {
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}
