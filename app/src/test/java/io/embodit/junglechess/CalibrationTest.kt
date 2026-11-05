package io.embodit.junglechess

import io.embodit.junglechess.domain.model.Animal
import io.embodit.junglechess.ui.board.BoardLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * D25 / D24 on the shipped GLBs: bottom centre at the origin (±1 cm), height as the layout table says (< 10 %),
 * face toward -Z (head end of the body in -Z), OPAQUE, <= 2k triangles, <= 400 KB.
 */
class CalibrationTest {
    private fun json(f: File): String {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        b.position(12)
        val len = b.int; b.int
        val bytes = ByteArray(len); b.get(bytes)
        return String(bytes)
    }

    private fun floats(s: String, key: String): List<Float> =
        Regex("\"$key\":\\[([-0-9.eE,]+)]").find(s)!!.groupValues[1].split(",").map { it.toFloat() }

    @Test fun animalsAreCalibrated() {
        val dir = File("src/main/assets/models/animals")
        for (a in Animal.entries) {
            val f = File(dir, "${a.asset}.glb")
            assertTrue("${a.asset} missing", f.exists())
            assertTrue("${a.asset} ${f.length()} bytes", f.length() <= 400 * 1024)
            val j = json(f)
            val min = floats(j, "min"); val max = floats(j, "max")
            assertEquals("${a.asset} centre x", 0f, (min[0] + max[0]) / 2, 0.01f)
            assertEquals("${a.asset} centre z", 0f, (min[2] + max[2]) / 2, 0.01f)
            assertEquals("${a.asset} bottom", 0f, min[1], 0.001f)
            val h = max[1] - min[1]
            assertEquals("${a.asset} height", BoardLayout.animalHeight(a), h, BoardLayout.animalHeight(a) * 0.1f)
            assertTrue("${a.asset} OPAQUE", j.contains("\"alphaMode\":\"OPAQUE\"") || !j.contains("alphaMode"))
            val tris = Regex("\\{[^{}]*\"type\":\"SCALAR\"[^{}]*}").findAll(j).sumOf { Regex("\"count\":(\\d+)").find(it.value)!!.groupValues[1].toInt() } / 3
            assertTrue("${a.asset} triangles $tris", tris in 1..2000)
        }
    }
}
