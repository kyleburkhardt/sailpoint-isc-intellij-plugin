package com.sailpoint.intellij.transform

import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.sailpoint.intellij.transform.ui.IscSample
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
        val shown = texts(panel.component)
        assertTrue(shown.toString(), "Differs from ISC" in shown)
        assertTrue(shown.toString(), "Preview: \"abc\"" in shown)
        assertTrue(shown.toString(), "ISC · Jane Doe: \"abd\"" in shown)
        assertTrue(shown.toString(), "A warning" in shown)
    }

    @Test
    fun `an ISC result fills the test values from the identity it ran on`() {
        val json = """{"name":"T","type":"firstValid","attributes":{"values":[
            {"type":"identityAttribute","attributes":{"name":"nickname"}},
            {"type":"identityAttribute","attributes":{"name":"firstname"}}]}}"""
        val panel = TransformFormPanel(nameEditable = true) {}
        panel.iscTest = IscTestActions(run = {}, setUp = {}, current = { JsonParser.parseString(json).toString() })
        panel.setModel(JsonParser.parseString(json).asJsonObject)
        val sample = IscSample(
            input = null,
            values = mapOf(
                NeededInput(NeedKind.IDENTITY_ATTRIBUTE, "nickname") to null,
                NeededInput(NeedKind.IDENTITY_ATTRIBUTE, "firstname") to "Alex",
            ),
            notes = emptyList(),
        )
        val setup = IscTestSetup("id", "Alex Brown", "displayName")
        panel.showIscOutcome(IscTestOutcome.Done(setup, "Alex", null, emptyList(), JsonParser.parseString(json).toString(), sample))
        val shown = texts(panel.component)
        assertTrue(shown.toString(), "Matches ISC" in shown)
        assertTrue(shown.toString(), "Preview: \"Alex\"" in shown)
        assertTrue(shown.toString(), "ISC · Alex Brown: \"Alex\"" in shown)
        assertTrue(shown.toString(), "Alex" in shown)
    }

    @Test
    fun `a value read only inside a referenced transform gets a field of its own`() {
        val shared = JsonParser.parseString(
            """{"name":"Dept","type":"upper","attributes":{"input":
                {"type":"accountAttribute","attributes":{"sourceName":"HR","attributeName":"dept"}}}}""",
        ).asJsonObject
        val tenant = object : com.sailpoint.intellij.transform.ui.TenantNames {
            override fun sources(onLoaded: (List<String>) -> Unit) = Unit
            override fun accountAttributes(source: String, onLoaded: (List<String>) -> Unit) = Unit
            override fun identityAttributes(onLoaded: (List<String>) -> Unit) = Unit
            override fun transform(name: String, onLoaded: (com.google.gson.JsonObject?) -> Unit) = onLoaded(shared.takeIf { name == "Dept" })
        }
        val panel = TransformFormPanel(nameEditable = true, tenant = tenant) {}
        panel.setModel(JsonParser.parseString("""{"name":"T","type":"reference","attributes":{"id":"Dept"}}""").asJsonObject)
        assertTrue(labels(panel.component).toString() + texts(panel.component), labels(panel.component).any { "'dept' on HR" in it })
        val field = fields(panel.component).single { it.emptyText.text == "test value" }
        field.text = "sales"
        assertTrue(texts(panel.component).toString(), "\"SALES\"" in texts(panel.component))
    }

    @Test
    fun `a referenced transform that changes in the tenant is previewed again`() {
        var shared = JsonParser.parseString("""{"name":"Case","type":"upper"}""").asJsonObject
        val listeners = mutableListOf<(String) -> Unit>()
        val tenant = object : com.sailpoint.intellij.transform.ui.TenantNames {
            override fun sources(onLoaded: (List<String>) -> Unit) = Unit
            override fun accountAttributes(source: String, onLoaded: (List<String>) -> Unit) = Unit
            override fun identityAttributes(onLoaded: (List<String>) -> Unit) = Unit
            override fun transform(name: String, onLoaded: (com.google.gson.JsonObject?) -> Unit) = onLoaded(shared)
            override fun onTransformChanged(listener: (String) -> Unit): () -> Unit {
                listeners += listener
                return { listeners -= listener }
            }
        }
        val panel = TransformFormPanel(nameEditable = true, tenant = tenant) {}
        panel.setModel(JsonParser.parseString("""{"name":"T","type":"reference","attributes":{"id":"Case","input":"Abc"}}""").asJsonObject)
        assertTrue(texts(panel.component).toString(), "\"ABC\"" in texts(panel.component))
        shared = JsonParser.parseString("""{"name":"Case","type":"lower"}""").asJsonObject
        listeners.toList().forEach { it("Case") }
        assertTrue(texts(panel.component).toString(), "\"abc\"" in texts(panel.component))
        panel.dispose()
        assertTrue(listeners.isEmpty())
    }

    private fun fields(c: java.awt.Component): List<com.intellij.ui.components.JBTextField> = when (c) {
        is com.intellij.ui.components.JBTextField -> listOf(c)
        is java.awt.Container -> c.components.flatMap(::fields)
        else -> emptyList()
    }

    private fun labels(c: java.awt.Component): List<String> = when (c) {
        is javax.swing.JLabel -> listOf(c.text.orEmpty())
        is java.awt.Container -> c.components.flatMap(::labels)
        else -> emptyList()
    }

    private fun texts(c: java.awt.Component): List<String> = when (c) {
        is javax.swing.text.JTextComponent -> listOf(c.text)
        is java.awt.Container -> c.components.flatMap(::texts)
        else -> emptyList()
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
