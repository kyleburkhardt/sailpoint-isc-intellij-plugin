package com.sailpoint.intellij.api

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceKindTest {
    private fun transform(json: String) = ResourceKind.TRANSFORMS.toItem("t", JsonParser.parseString(json).asJsonObject)

    @Test
    fun `SailPoint's internal transforms are read-only and the tenant's own are not`() {
        assertTrue(transform("""{"id":"1","name":"ToUpper","type":"upper","internal":true}""").readOnly)
        assertFalse(transform("""{"id":"2","name":"Mine","type":"upper","internal":false}""").readOnly)
        assertFalse(transform("""{"id":"3","name":"Old","type":"upper"}""").readOnly)
    }

    @Test
    fun `read-only items sort after the rest`() {
        val items = listOf(
            transform("""{"id":"1","name":"Alpha","internal":true}"""),
            transform("""{"id":"2","name":"zeta"}"""),
            transform("""{"id":"3","name":"beta"}"""),
        )
        assertEquals(listOf("beta", "zeta", "Alpha"), items.sortedWith(compareBy({ it.readOnly }, { it.name.lowercase() })).map { it.name })
    }
}
