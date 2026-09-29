package com.sailpoint.intellij.transform

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The operations are checked against the examples in SailPoint's own documentation, so the preview and ISC agree on
 * what a transform produces.
 */
class TransformEvaluatorTest {

    private fun transform(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    private fun output(json: String, input: String? = null, context: EvalContext = EvalContext(input = input)): EvalResult =
        evaluate(transform(json), context).result

    private fun assertValue(expected: String?, json: String, input: String? = null) =
        assertEquals(expected, (output(json, input) as EvalResult.Value).text)

    private fun failure(json: String, input: String? = null): String =
        (output(json, input) as? EvalResult.Failure)?.message ?: error("Expected a failure from $json")

    @Test
    fun `upper lower and trim reshape the incoming value`() {
        assertValue("ABC", """{"type":"upper"}""", "abc")
        assertValue("abc", """{"type":"lower"}""", "ABC")
        assertValue("Vice President", """{"type":"trim"}""", " Vice President")
        assertValue(null, """{"type":"upper"}""", null)
    }

    @Test
    fun `trim takes an explicit input from a nested transform`() {
        assertValue(
            "Austin, Texas",
            """{"type":"trim","attributes":{"input":{"type":"static","attributes":{"value":"Austin, Texas "}}}}""",
        )
    }

    @Test
    fun `concat joins values in order`() {
        assertValue(
            "John Smith (Contractor)",
            """{"type":"concat","attributes":{"values":["John"," ","Smith"," (Contractor)"]}}""",
        )
    }

    @Test
    fun `join puts the separator between values and defaults to a comma`() {
        assertValue("John Smith", """{"type":"join","attributes":{"values":["John","Smith"],"separator":" "}}""")
        assertValue("John,Smith", """{"type":"join","attributes":{"values":["John","Smith"]}}""")
    }

    @Test
    fun `substring takes the documented slices`() {
        assertValue("cd", """{"type":"substring","attributes":{"begin":2,"end":4}}""", "abcdef")
        assertValue("cde", """{"type":"substring","attributes":{"begin":1,"end":3,"beginOffset":1,"endOffset":2}}""", "abcdef")
        // -1 means the very start, and it ignores the offset.
        assertValue("abcdef", """{"type":"substring","attributes":{"begin":-1,"beginOffset":3}}""", "abcdef")
        assertTrue(failure("""{"type":"substring","attributes":{"begin":9}}""", "abcdef").contains("outside"))
    }

    @Test
    fun `replace uses regular expressions`() {
        assertValue(
            "Working with SailPoint Human Fabric is fun",
            """{"type":"replace","attributes":{"regex":"IIQ","replacement":"SailPoint Human Fabric"}}""",
            "Working with IIQ is fun",
        )
        assertValue(
            "Thequickbrownfoxjumpedoverlazydogs",
            """{"type":"replace","attributes":{"input":"The quick brown fox jumped over 10 lazy dogs","regex":"[^a-zA-Z]","replacement":""}}""",
        )
        assertTrue(failure("""{"type":"replace","attributes":{"regex":"[","replacement":""}}""", "x").contains("valid regular expression"))
    }

    @Test
    fun `split returns the nth piece`() {
        assertValue("123", """{"type":"split","attributes":{"delimiter":":","index":1}}""", "abc:123")
        assertValue(
            "fox",
            """{"type":"split","attributes":{"input":"The quick brown fox jumped over 10 lazy dogs","delimiter":" ","index":3,"throws":true}}""",
        )
        assertTrue(failure("""{"type":"split","attributes":{"delimiter":":","index":5}}""", "abc:123").contains("past the end"))
        assertValue(null, """{"type":"split","attributes":{"delimiter":":","index":5,"throws":false}}""", "abc:123")
    }

    @Test
    fun `lookup maps the input through its table`() {
        val table = """{"type":"lookup","attributes":{"table":{"512":"Austin","281":"Houston","default":"Unknown Area"}}}"""
        assertValue("Austin", table, "512")
        assertValue("Unknown Area", table, "999")
        val noDefault = """{"type":"lookup","attributes":{"table":{"512":"Austin"}}}"""
        assertTrue(failure(noDefault, "999").contains("no \"default\" entry"))
    }

    @Test
    fun `first valid returns the first value that has something in it`() {
        assertValue(
            "employee-1",
            """{"type":"firstValid","attributes":{"values":["",{"type":"static","attributes":{"value":"employee-1"}},"last"]}}""",
        )
    }

    @Test
    fun `first valid asks for a tenant value rather than skipping past it`() {
        val result = output("""{"type":"firstValid","attributes":{"values":[{"type":"identityAttribute","attributes":{"name":"uid"}},"fallback"]}}""")
        assertEquals(NeededInput(NeedKind.IDENTITY_ATTRIBUTE, "uid"), (result as EvalResult.Needs).need)
    }

    @Test
    fun `conditional compares the two sides of its expression`() {
        val json = """
            {"type":"conditional","attributes":{
              "expression":"${'$'}department eq Science",
              "positiveCondition":"${'$'}scienceBuilding",
              "negativeCondition":"${'$'}adminBuilding",
              "department":{"type":"static","attributes":{"value":"Science"}},
              "scienceBuilding":{"type":"static","attributes":{"value":"Building S"}},
              "adminBuilding":{"type":"static","attributes":{"value":"Building A"}}}}
        """.trimIndent()
        assertValue("Building S", json)
        assertValue("Building A", json.replace("\"value\":\"Science\"", "\"value\":\"Sales\""))
    }

    @Test
    fun `conditional only understands eq`() {
        val json = """{"type":"conditional","attributes":{"expression":"a ne b","positiveCondition":"y","negativeCondition":"n"}}"""
        assertTrue(failure(json).contains("value eq value"))
    }

    @Test
    fun `static returns its value and resolves variables`() {
        assertValue("Employee", """{"type":"static","attributes":{"value":"Employee"}}""")
        assertValue(
            "Full-Time",
            """{"type":"static","attributes":{"value":"#if(${'$'}workerType=='Employee')Full-Time#{else}Contingent#end",
               "workerType":{"type":"static","attributes":{"value":"Employee"}}}}""",
        )
        assertValue(
            "Contingent",
            """{"type":"static","attributes":{"value":"#if(${'$'}workerType=='Employee')Full-Time#{else}Contingent#end",
               "workerType":{"type":"static","attributes":{"value":"Contractor"}}}}""",
        )
        assertValue(
            "jane@example.com",
            """{"type":"static","attributes":{"value":"${'$'}{user}@example.com","user":"jane"}}""",
        )
    }

    @Test
    fun `static says which variable is missing instead of printing it`() {
        assertTrue(failure("""{"type":"static","attributes":{"value":"${'$'}nobody"}}""").contains("isn't defined"))
    }

    @Test
    fun `static says so when a template is beyond the preview`() {
        val json = """{"type":"static","attributes":{"value":"#foreach(${'$'}x in ${'$'}list)a#end"}}"""
        assertTrue(failure(json).contains("#foreach"))
    }

    @Test
    fun `an identity attribute is asked for, then used once supplied`() {
        val json = """{"type":"identityAttribute","attributes":{"name":"email"}}"""
        assertEquals(NeededInput(NeedKind.IDENTITY_ATTRIBUTE, "email"), (output(json) as EvalResult.Needs).need)
        val supplied = EvalContext(identityAttributes = mapOf("email" to "jane@example.com"))
        assertEquals("jane@example.com", (output(json, context = supplied) as EvalResult.Value).text)
    }

    @Test
    fun `an account attribute is asked for by source, then used once supplied`() {
        val json = """{"type":"lower","attributes":{"input":
            {"type":"accountAttribute","attributes":{"sourceName":"Workday","attributeName":"DEPARTMENT"}}}}"""
        assertEquals(NeededInput(NeedKind.ACCOUNT_ATTRIBUTE, "DEPARTMENT", "Workday"), (output(json) as EvalResult.Needs).need)
        assertEquals(listOf(NeededInput(NeedKind.ACCOUNT_ATTRIBUTE, "DEPARTMENT", "Workday")), neededInputs(transform(json)))
        val supplied = EvalContext(accountAttributes = mapOf("Workday" to mapOf("DEPARTMENT" to "Engineering")))
        assertEquals("engineering", (output(json, context = supplied) as EvalResult.Value).text)
    }

    @Test
    fun `an account attribute can name its source by application name`() {
        val json = """{"type":"accountAttribute","attributes":{"applicationName":"AD [source]","attributeName":"mail"}}"""
        val supplied = EvalContext(accountAttributes = mapOf("AD [source]" to mapOf("mail" to "a@b.c")))
        assertEquals("a@b.c", (output(json, context = supplied) as EvalResult.Value).text)
    }

    @Test
    fun `a reference identity attribute is asked for by uid`() {
        val json = """{"type":"getReferenceIdentityAttribute","attributes":{"uid":"manager","attributeName":"email"}}"""
        assertEquals(NeededInput(NeedKind.REFERENCE_IDENTITY_ATTRIBUTE, "email", "manager"), (output(json) as EvalResult.Needs).need)
        val supplied = EvalContext(referenceAttributes = mapOf("manager" to mapOf("email" to "boss@example.com")))
        assertEquals("boss@example.com", (output(json, context = supplied) as EvalResult.Value).text)
    }

    @Test
    fun `uuid and the random generators make values of the documented shape`() {
        val seeded = EvalContext(random = kotlin.random.Random(7))
        val uuid = (output("""{"type":"uuid"}""", context = seeded) as EvalResult.Value).text.orEmpty()
        assertTrue(uuid, Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(uuid))

        val alpha = (output("""{"type":"randomAlphaNumeric"}""", context = seeded) as EvalResult.Value).text.orEmpty()
        assertTrue(alpha, Regex("[A-Za-z0-9]{32}").matches(alpha))
        val digits = (output("""{"type":"randomNumeric","attributes":{"length":"5"}}""", context = seeded) as EvalResult.Value).text.orEmpty()
        assertTrue(digits, Regex("[0-9]{5}").matches(digits))
        assertTrue(failure("""{"type":"randomNumeric","attributes":{"length":"451"}}""").contains("450"))

        // The same seed gives the same value, so a preview doesn't change while you type.
        assertEquals(
            output("""{"type":"uuid"}""", context = EvalContext(random = kotlin.random.Random(1))),
            output("""{"type":"uuid"}""", context = EvalContext(random = kotlin.random.Random(1))),
        )
    }

    @Test
    fun `an operation that isn't previewed yet says so`() {
        assertTrue(failure("""{"type":"dateMath","attributes":{"expression":"now"}}""").contains("isn't previewed yet"))
        assertTrue(failure("""{"type":"nonsense"}""").contains("isn't a transform operation"))
    }

    @Test
    fun `every nested transform reports its own result`() {
        val json = """
            {"type":"upper","attributes":{"input":{"type":"concat","attributes":{
              "values":[{"type":"lower","attributes":{"input":"AB"}},"-cd"]}}}}
        """.trimIndent()
        val trace = evaluate(transform(json))
        assertEquals("AB-CD", trace.result.textOrNull)
        assertEquals("ab", trace.at("input.values[0]")?.result?.textOrNull)
        assertEquals("ab-cd", trace.at("input")?.result?.textOrNull)
        assertEquals("AB-CD", trace.at("")?.result?.textOrNull)
    }

    @Test
    fun `the tenant values a transform reads are listed up front`() {
        val json = """
            {"type":"concat","attributes":{"values":[
              {"type":"identityAttribute","attributes":{"name":"firstname"}},
              {"type":"accountAttribute","attributes":{"sourceName":"HR Source","attributeName":"empType"}},
              {"type":"identityAttribute","attributes":{"name":"firstname"}}]}}
        """.trimIndent()
        assertEquals(
            listOf(
                NeededInput(NeedKind.IDENTITY_ATTRIBUTE, "firstname"),
                NeededInput(NeedKind.ACCOUNT_ATTRIBUTE, "empType", "HR Source"),
            ),
            neededInputs(transform(json)),
        )
    }

    @Test
    fun `only a transform that works on the incoming value reads it`() {
        fun reads(json: String) = readsImplicitInput(transform(json))

        assertTrue(reads("""{"type":"lower","attributes":{}}"""))
        // An explicit input at the start of the chain replaces it...
        assertFalse(reads("""{"type":"upper","attributes":{"input":{"type":"trim","attributes":{"input":"  a "}}}}"""))
        assertFalse(reads("""{"type":"lower","attributes":{"input":{"type":"identityAttribute","attributes":{"name":"department"}}}}"""))
        // ...but only there: the last step of the chain still gets it.
        assertTrue(reads("""{"type":"upper","attributes":{"input":{"type":"trim","attributes":{}}}}"""))
        // Operations that don't work on an input don't read it, but what they nest might.
        assertFalse(reads("""{"type":"static","attributes":{"value":"x"}}"""))
        assertFalse(reads("""{"type":"concat","attributes":{"values":["a",{"type":"identityAttribute","attributes":{"name":"b"}}]}}"""))
        assertTrue(reads("""{"type":"concat","attributes":{"values":["a",{"type":"upper","attributes":{}}]}}"""))
        // Nested under an explicit input, a step gets that input rather than ISC's.
        assertFalse(reads("""{"type":"conditional","attributes":{"input":"x","expression":"${'$'}v eq x","v":{"type":"lower","attributes":{}},
            "positiveCondition":"y","negativeCondition":"n"}}"""))
        // What the preview can't see into might use it.
        assertTrue(reads("""{"type":"reference","attributes":{"id":"Other"}}"""))
        assertTrue(reads("""{"type":"notAnOperation","attributes":{}}"""))
    }
}
