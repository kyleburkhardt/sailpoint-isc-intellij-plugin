package com.sailpoint.intellij.transform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The catalog is read from the JSON schema, so these check the schema still describes every operation fully. */
class TransformCatalogTest {

    @Test
    fun `every operation in the schema has a display name and documentation`() {
        assertEquals(38, TransformCatalog.ops.size)
        TransformCatalog.ops.forEach { op ->
            assertTrue("${op.type} has no display name", op.label.isNotBlank() && op.label != op.type)
            assertTrue("${op.type} has no summary", op.summary.isNotBlank())
            assertTrue("${op.type} has no documentation link", op.docsUrl.endsWith("/").not())
        }
    }

    @Test
    fun `attribute shapes come through as the form needs them`() {
        val concat = requireNotNull(TransformCatalog["concat"])
        assertEquals(AttrKind.VALUE_LIST, concat.attribute("values")?.kind)
        assertTrue(concat.attribute("values")!!.required)

        val lookup = requireNotNull(TransformCatalog["lookup"])
        assertEquals(AttrKind.TABLE, lookup.attribute("table")?.kind)
        assertEquals(AttrKind.VALUE, lookup.attribute("input")?.kind)

        val split = requireNotNull(TransformCatalog["split"])
        assertEquals(AttrKind.INTEGER, split.attribute("index")?.kind)
        assertEquals(AttrKind.BOOLEAN, split.attribute("throws")?.kind)

        val compare = requireNotNull(TransformCatalog["dateCompare"])
        assertEquals(AttrKind.ENUM, compare.attribute("operator")?.kind)
        assertEquals(listOf("LT", "LTE", "GT", "GTE"), compare.attribute("operator")?.options)
    }

    @Test
    fun `attribute labels are readable`() {
        assertEquals("Account sort descending", TransformCatalog["accountAttribute"]?.attribute("accountSortDescending")?.label)
        assertEquals("Input format", TransformCatalog["dateFormat"]?.attribute("inputFormat")?.label)
    }

    @Test
    fun `join is part of the schema`() {
        val join = requireNotNull(TransformCatalog["join"]) { "join is missing from transform.schema.json" }
        assertEquals(AttrKind.VALUE_LIST, join.attribute("values")?.kind)
        assertEquals(AttrKind.TEXT, join.attribute("separator")?.kind)
    }

    @Test
    fun `a new transform starts with the attributes its type requires`() {
        val starter = TransformCatalog.starterAttributes("substring")
        assertEquals(setOf("begin"), starter.keySet())
        assertEquals(setOf("table"), TransformCatalog.starterAttributes("lookup").keySet())
        assertTrue(TransformCatalog.starterAttributes("trim").keySet().isEmpty())
    }
}
