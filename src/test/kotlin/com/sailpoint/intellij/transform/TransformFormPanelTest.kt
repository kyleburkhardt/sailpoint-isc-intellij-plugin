package com.sailpoint.intellij.transform

import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.sailpoint.intellij.transform.ui.TransformFormPanel
import org.junit.AfterClass
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
    fun `every operation renders`() {
        TransformCatalog.ops.forEach { op ->
            val transform = JsonParser.parseString("""{"name":"T","type":"${op.type}"}""").asJsonObject
            transform.add("attributes", TransformCatalog.starterAttributes(op.type))
            val panel = TransformFormPanel(nameEditable = false) {}
            panel.setModel(transform)
            assertNotNull("${op.type} did not render", panel.component)
        }
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
