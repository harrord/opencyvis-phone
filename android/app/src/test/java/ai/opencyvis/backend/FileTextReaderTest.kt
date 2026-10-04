package ai.opencyvis.backend

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [FileTextReader] — the shared text-file reading logic used by both
 * PrivilegedService (shell uid) and SystemBackend (system uid).
 *
 * Host JVM notes: /sdcard does not exist on the test machine, so canonical paths
 * resolve lexically. That still exercises traversal rejection correctly; the
 * successful-read path (real file IO) is verified on-device.
 */
class FileTextReaderTest {

    // --- Path allowlist --───────────────────────────────────────────────────

    @Test
    fun `allows files under Download and Documents`() {
        assertTrue(FileTextReader.isAllowed("/sdcard/Download/report.txt"))
        assertTrue(FileTextReader.isAllowed("/sdcard/Documents/notes.md"))
        assertTrue(FileTextReader.isAllowed("/storage/emulated/0/Download/report.txt"))
        assertTrue(FileTextReader.isAllowed("/storage/emulated/0/Documents/notes.md"))
        assertTrue(FileTextReader.isAllowed("/sdcard/Download/sub/dir/deep.txt"))
    }

    @Test
    fun `allows the allowlist root itself`() {
        assertTrue(FileTextReader.isAllowed("/sdcard/Download"))
        assertTrue(FileTextReader.isAllowed("/storage/emulated/0/Documents"))
    }

    @Test
    fun `rejects paths outside the allowlist`() {
        assertFalse(FileTextReader.isAllowed("/sdcard/DCIM/a.txt"))
        assertFalse(FileTextReader.isAllowed("/sdcard/Pictures/a.txt"))
        assertFalse(FileTextReader.isAllowed("/sdcard/root.txt"))
        assertFalse(FileTextReader.isAllowed("/sdcard/Android/data/com.other/a.txt"))
        assertFalse(FileTextReader.isAllowed("/data/data/com.other/databases/x.db"))
        assertFalse(FileTextReader.isAllowed("/system/etc/hosts"))
        // Prefix trickery that startsWith on the raw string would wrongly accept
        assertFalse(FileTextReader.isAllowed("/sdcard/Download_evil/a.txt"))
    }

    @Test
    fun `read rejects directory traversal`() {
        // Canonicalizes to /Android/data/com.xxx/a.txt — outside the allowlist
        val e = assertThrows(FileTextReader.FileReadException::class.java) {
            FileTextReader.read("/sdcard/Download/../../Android/data/com.xxx/a.txt")
        }
        assertTrue(e.message!!.contains("allowed directories"))

        // Canonicalizes to /sdcard/DCIM/secret.jpg
        assertThrows(FileTextReader.FileReadException::class.java) {
            FileTextReader.read("/sdcard/Download/../DCIM/secret.jpg")
        }
    }

    @Test
    fun `read rejects app-private and system paths`() {
        assertThrows(FileTextReader.FileReadException::class.java) {
            FileTextReader.read("/data/data/com.other/databases/x.db")
        }
        assertThrows(FileTextReader.FileReadException::class.java) {
            FileTextReader.read("/system/etc/hosts")
        }
    }

    @Test
    fun `read rejects empty and blank paths`() {
        assertThrows(FileTextReader.FileReadException::class.java) {
            FileTextReader.read("")
        }
        assertThrows(FileTextReader.FileReadException::class.java) {
            FileTextReader.read("   ")
        }
    }

    @Test
    fun `read reports missing file under allowed root with absolute path`() {
        val e = assertThrows(FileTextReader.FileReadException::class.java) {
            FileTextReader.read("/sdcard/Download/definitely-not-here-12345.txt")
        }
        assertTrue(e.message!!.contains("File not found"))
        // The error echoes the attempted path so the LLM can retry with another name
        assertTrue(e.message!!.contains("/sdcard/Download/definitely-not-here-12345.txt"))
    }

    // --- Binary rejection ───────────────────────────────────────────────────

    @Test
    fun `detects NUL bytes as binary within sniff window`() {
        assertTrue(FileTextReader.isBinary(byteArrayOf(0x41, 0x00, 0x42), 3))
        assertFalse(FileTextReader.isBinary("hello world".toByteArray(), 11))
    }

    @Test
    fun `NUL beyond first 512 bytes is not flagged`() {
        val bytes = ByteArray(600) { 0x41 } + byteArrayOf(0x00)
        assertFalse(FileTextReader.isBinary(bytes, bytes.size))
    }

    // --- Encoding ───────────────────────────────────────────────────────────

    @Test
    fun `decodes utf-8 content`() {
        val (text, encoding) =
            FileTextReader.decodeCapped("你好 world".toByteArray(Charsets.UTF_8), truncated = false)
        assertEquals("你好 world", text)
        assertEquals("UTF-8", encoding)
    }

    @Test
    fun `falls back to gb18030 for gbk content`() {
        val gbk = "中文内容 ABC".toByteArray(charset("GB18030"))
        val (text, encoding) = FileTextReader.decodeCapped(gbk, truncated = false)
        assertEquals("中文内容 ABC", text)
        assertEquals("GB18030", encoding)
    }

    @Test
    fun `truncated utf-8 drops the split character instead of corrupting decode`() {
        val bytes = "你好".toByteArray(Charsets.UTF_8)   // "好" = 3 bytes
        val cut = bytes.copyOf(5)                       // splits "好" in the middle
        val (text, encoding) = FileTextReader.decodeCapped(cut, truncated = true)
        assertEquals("你", text)
        assertEquals("UTF-8", encoding)
    }

    @Test
    fun `non-truncated malformed content is not silently repaired`() {
        val bytes = "ok".toByteArray() + byteArrayOf(0xFF.toByte())
        assertThrows(FileTextReader.FileReadException::class.java) {
            FileTextReader.decodeCapped(bytes, truncated = false)
        }
    }

    @Test
    fun `undecodable content throws with clear message`() {
        val e = assertThrows(FileTextReader.FileReadException::class.java) {
            FileTextReader.decodeCapped(byteArrayOf(0xFF.toByte()), truncated = false)
        }
        assertTrue(e.message!!.contains("decode"))
    }
}
