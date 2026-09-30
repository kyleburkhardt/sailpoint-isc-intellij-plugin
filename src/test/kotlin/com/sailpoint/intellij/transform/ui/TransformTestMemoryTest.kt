package com.sailpoint.intellij.transform.ui

import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.sailpoint.intellij.api.ResourceKind
import com.sailpoint.intellij.editor.IscVirtualFile
import com.sailpoint.intellij.transform.NeedKind
import com.sailpoint.intellij.transform.NeededInput
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test

class TransformTestMemoryTest {

    private fun transform(id: String?, name: String) = IscVirtualFile(
        tenantId = "tenant",
        kind = ResourceKind.TRANSFORMS,
        owner = null,
        remoteId = id,
        remote = JsonParser.parseString("""{"id":"${id.orEmpty()}","name":"$name","type":"lower"}""").asJsonObject,
    )

    @Test
    fun `test values and the ISC setup are remembered per transform`() {
        val memory = TransformTestMemory()
        val file = transform("t1", "Lower")
        val email = NeededInput(NeedKind.IDENTITY_ATTRIBUTE, "email")
        val start = NeededInput(NeedKind.ACCOUNT_ATTRIBUTE, "startDate", "HR")
        val values = TestValues(input = "ABC", values = mapOf(email to "jane@example.com", start to ""), absent = setOf(start))
        val setup = IscTestSetup("id-1", "Jane Doe", "displayName", "HR", "name")

        memory.remember(file, values)
        memory.remember(file, setup)

        assertEquals(values, memory.values(file))
        assertEquals(setup, memory.setup(file))
        assertNull(memory.values(transform("t2", "Other")))
        assertNull(memory.setup(transform("t2", "Other")))
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
