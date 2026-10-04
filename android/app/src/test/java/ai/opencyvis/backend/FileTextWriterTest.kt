package ai.opencyvis.backend

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [FileTextWriter.parse] — the filename validation that confines all
 * writes to Download/OpenCyvis. MediaStore IO itself is verified on-device.
 */
class FileTextWriterTest {

    // --- parse(): valid names ────────────────────────────────────────────────

    @Test
    fun `parses simple filename at root`() {
        val p = FileTextWriter.parse("report.txt")!!
        assertEquals("report.txt", p.displayName)
        assertEquals("Download/OpenCyvis", p.relativePath)
        assertEquals("Download/OpenCyvis/", p.relativePathStored)
    }

    @Test
    fun `parses subdirectory path`() {
        val p = FileTextWriter.parse("notes/2026/log.txt")!!
        assertEquals("log.txt", p.displayName)
        assertEquals("Download/OpenCyvis/notes/2026", p.relativePath)
    }

    @Test
    fun `parses names with spaces and dots`() {
        val p = FileTextWriter.parse("my report v1.2.txt")!!
        assertEquals("my report v1.2.txt", p.displayName)
        assertEquals("Download/OpenCyvis", p.relativePath)
    }

    @Test
    fun `allows max subdirectory depth`() {
        val p = FileTextWriter.parse("a/b/c/d/e/f.txt")!!  // 5 levels below root
        assertEquals("f.txt", p.displayName)
        assertEquals("Download/OpenCyvis/a/b/c/d/e", p.relativePath)
    }

    @Test
    fun `trims surrounding whitespace`() {
        val p = FileTextWriter.parse("  report.txt  ")!!
        assertEquals("report.txt", p.displayName)
    }

    // --- parse(): rejections ─────────────────────────────────────────────────

    @Test
    fun `rejects absolute paths`() {
        assertNull(FileTextWriter.parse("/sdcard/Download/report.txt"))
        assertNull(FileTextWriter.parse("/report.txt"))
    }

    @Test
    fun `rejects traversal`() {
        assertNull(FileTextWriter.parse("../escape.txt"))
        assertNull(FileTextWriter.parse("notes/../../escape.txt"))
        assertNull(FileTextWriter.parse(".."))
    }

    @Test
    fun `rejects backslashes and trailing slashes`() {
        assertNull(FileTextWriter.parse("a\\b.txt"))
        assertNull(FileTextWriter.parse("notes/"))
        assertNull(FileTextWriter.parse("/"))
    }

    @Test
    fun `rejects blank names`() {
        assertNull(FileTextWriter.parse(""))
        assertNull(FileTextWriter.parse("   "))
    }

    @Test
    fun `rejects dot components`() {
        assertNull(FileTextWriter.parse("./x.txt"))
        assertNull(FileTextWriter.parse("a/./b.txt"))
    }

    @Test
    fun `rejects too deep nesting`() {
        assertNull(FileTextWriter.parse("a/b/c/d/e/f/g.txt"))  // 6 levels below root
    }

    @Test
    fun `rejects oversize components`() {
        assertNull(FileTextWriter.parse("a".repeat(97) + ".txt"))
    }

    @Test
    fun `rejects oversize total length`() {
        assertNull(FileTextWriter.parse("a".repeat(300)))
    }
}
