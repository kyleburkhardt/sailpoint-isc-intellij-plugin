package com.sailpoint.intellij.transform

import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.sailpoint.intellij.transform.ui.IscTestActions
import com.sailpoint.intellij.transform.ui.IscTestOutcome
import com.sailpoint.intellij.transform.ui.IscTestSetup
import com.sailpoint.intellij.transform.ui.TransformFormPanel
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** The form is built from the schema at runtime, so this checks every operation can actually be rendered. */
class TransformFormPanelTest {

    @Test
    fun `a nested transform builds a form`() {
        val json = """
            {"name":"Test","type":"concat","attributes":{"values":[
              {"type":"lower","attributes":{"input":"AB"}},
              "-",
              {"type":"lookup","attributes":{"table":{"default":"x"}}}]}}
        """.trimIndent()
        val panel = TransformFormPanel(nameEditable = true) {}
        panel.setModel(JsonParser.parseString(json).asJsonObject)
        assertTrue(panel.component.componentCount > 0)
    }

    @Test
    fun `a chain of steps renders`() {
        val json = """
            {"name":"Surname","type":"upper","attributes":{"input":
              {"type":"split","attributes":{"delimiter":" ","index":1,"input":
                {"type":"trim"}}}}}
        """.trimIndent()
        val panel = TransformFormPanel(nameEditable = true) {}
        panel.setModel(JsonParser.parseString(json).asJsonObject)
        assertTrue(panel.component.componentCount > 0)
    }

    @Test
    fun `every operation renders`() {
        TransformCatalog.ops.forEach { op ->
            val transform = JsonParser.parseString("""{"name":"T","type":"${op.type}"}""").asJsonObject
            transform.add("attributes", TransformCatalog.starterAttributes(op.type))
            val panel = TransformFormPanel(nameEditable = false) {}
            panel.setModel(transform)
            assertNotNull("${op.type} did not render", panel.component)
        }
    }

    @Test
    fun `moving a step swaps what it does with its neighbour and keeps the chain`() {
        // Runs as: trim " A ", then lower, then upper.
        val json = """
            {"name":"T","type":"upper","attributes":{"input":
              {"type":"lower","attributes":{"input":
                {"type":"trim","attributes":{"input":" A "}}}}}}
        """.trimIndent()
        val panel = TransformFormPanel(nameEditable = true) {}
        panel.setModel(JsonParser.parseString(json).asJsonObject)

        // Upper moves up: trim, upper, lower.
        panel.moveStep("", up = true)
        assertEquals(
            """{"name":"T","type":"lower","attributes":{"input":{"type":"upper","attributes":{"input":{"type":"trim","attributes":{"input":" A "}}}}}}""",
            panel.model.toString(),
        )
        // Trim moves down: its explicit input stays first in the chain.
        panel.moveStep("input.input", up = false)
        assertEquals(
            """{"name":"T","type":"lower","attributes":{"input":{"type":"trim","attributes":{"input":{"type":"upper","attributes":{"input":" A "}}}}}}""",
            panel.model.toString(),
        )
        assertEquals("a", evaluate(panel.model).result.textOrNull)
    }

    @Test
    fun `an ISC result shows under the preview`() {
        val json = """{"name":"T","type":"lower","attributes":{"input":"ABC"}}"""
        val panel = TransformFormPanel(nameEditable = true) {}
        panel.iscTest = IscTestActions(run = {}, setUp = {}, current = { JsonParser.parseString(json).toString() })
        panel.setModel(JsonParser.parseString(json).asJsonObject)
        val setup = IscTestSetup("id", "Jane Doe", "displayName")
        panel.showIscOutcome(IscTestOutcome.Done(setup, "abd", "Jane", listOf("A warning"), JsonParser.parseString(json).toString()))
        fun texts(c: java.awt.Component): List<String> = when (c) {
            is javax.swing.text.JTextComponent -> listOf(c.text)
            is java.awt.Container -> c.components.flatMap(::texts)
            else -> emptyList()
        }
        val shown = texts(panel.component)
        assertTrue(shown.toString(), "ISC · Jane Doe: \"abd\"" in shown)
        assertTrue(shown.toString(), "The preview above differs from ISC." in shown)
        assertTrue(shown.toString(), "A warning" in shown)
    }

    companion object {
        private val fixture = IdeaTestFixtureFactory.getFixtureFactory().createBareFixture()

        @BeforeClass
        @JvmStatic
        fun startApplication() = fixture.setUp()

        @AfterClass
        @JvmStatic
        fun stopApplication() = fixture.tearDown()
    }
}
