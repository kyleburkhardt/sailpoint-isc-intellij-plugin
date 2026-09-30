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
    fun `a tenant value known to be empty gives nothing instead of asking for it`() {
        val json = """{"type":"firstValid","attributes":{"values":[
            {"type":"identityAttribute","attributes":{"name":"nickname"}},
            {"type":"identityAttribute","attributes":{"name":"firstname"}}]}}"""
        val context = EvalContext(
            identityAttributes = mapOf("firstname" to "Alex"),
            absent = setOf(NeededInput(NeedKind.IDENTITY_ATTRIBUTE, "nickname")),
        )
        assertEquals("Alex", (output(json, context = context) as EvalResult.Value).text)
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
        assertTrue(failure("""{"type":"notAnOp"}""").contains("isn't a transform operation"))
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

    @Test
    fun `the implicit input is made explicit exactly where it would have arrived`() {
        val input = transform("""{"type":"accountAttribute","attributes":{"sourceName":"HR","attributeName":"dept"}}""")
        fun given(json: String) = withImplicitInput(transform(json), input).toString()
        val dept = input.toString()

        // The first step of a chain gets it; the steps after it already have theirs.
        assertEquals(
            """{"type":"upper","attributes":{"input":{"type":"trim","attributes":{"input":$dept}}}}""",
            given("""{"type":"upper","attributes":{"input":{"type":"trim","attributes":{}}}}"""),
        )
        // A step with no attributes gets some.
        assertEquals("""{"type":"lower","attributes":{"input":$dept}}""", given("""{"type":"lower"}"""))
        // Inside something that doesn't read an input, the steps that do each get it.
        assertEquals(
            """{"type":"concat","attributes":{"values":[{"type":"upper","attributes":{"input":$dept}},"-"]}}""",
            given("""{"type":"concat","attributes":{"values":[{"type":"upper","attributes":{}},"-"]}}"""),
        )
        // Nothing reads it, so nothing changes, and the original is left alone.
        val static = transform("""{"type":"static","attributes":{"value":"x"}}""")
        assertEquals(static.toString(), withImplicitInput(static, input).toString())
        assertFalse(readsImplicitInput(withImplicitInput(transform("""{"type":"lower"}"""), input)))
    }
    @Test
    fun `dateFormat converts between patterns and named formats, as SailPoint's examples do`() {
        assertValue("1975-04-01", """{"type":"dateFormat","attributes":{"inputFormat":"M/d/yyyy","outputFormat":"yyyy-MM-dd"}}""", "4/1/1975")
        assertValue(
            "1974-08-02T02:30:32.190Z",
            """{"type":"dateFormat","attributes":{"inputFormat":"EPOCH_TIME_JAVA","outputFormat":"ISO8601"}}""",
            "144642632190",
        )
        assertValue("2025-01-14T00:00:00.000Z", """{"type":"dateFormat","attributes":{"inputFormat":"PEOPLE_SOFT"}}""", "01/14/2025")
        assertTrue(failure("""{"type":"dateFormat","attributes":{"inputFormat":"MM/dd/yyyy"}}""", "soon").contains("doesn't match"))
    }

    @Test
    fun `dateFormat round-trips Windows file times`() {
        val json = """{"type":"dateFormat","attributes":{"inputFormat":"ISO8601","outputFormat":"EPOCH_TIME_WIN32"}}"""
        assertValue("116444736000000000", json, "1970-01-01T00:00:00.000Z")
    }

    @Test
    fun `dateMath adds, subtracts and rounds from left to right`() {
        val math = { expression: String, roundUp: Boolean ->
            """{"type":"dateMath","attributes":{"expression":"$expression","roundUp":$roundUp}}"""
        }
        assertValue("2025-01-14T12:00Z", math("+12h/h", false), "2025-01-14T00:30:00.000Z")
        assertValue("2025-01-15T00:00Z", math("/d", true), "2025-01-14T08:00:00.000Z")
        assertValue("2025-01-13T20:00Z", math("-5h/h", false), "2025-01-14T01:30Z")
        // A month on from 31 January is 28 February, the last day there is.
        assertValue("2025-02-01T00:00Z", math("+1M/M", false), "2025-01-31T00:00Z")
        assertTrue(failure(math("/w", false), "2025-01-14T00:00Z").contains("week"))
        assertTrue(failure(math("+1d", false), "01/14/2025").contains("ISO8601"))
    }

    @Test
    fun `dateMath with now ignores the input`() {
        val context = EvalContext(input = "2000-01-01T00:00Z", now = java.time.Instant.parse("2025-06-01T10:15:30Z"))
        val json = """{"type":"dateMath","attributes":{"expression":"now-5d/d"}}"""
        assertEquals("2025-05-27T00:00Z", (output(json, context = context) as EvalResult.Value).text)
    }

    @Test
    fun `a reference runs the named transform on the value flowing in`() {
        val toEst = transform("""{"name":"UTC To EST","type":"dateMath","attributes":{"expression":"-5h"}}""")
        val json = """{"type":"reference","attributes":{"id":"UTC To EST","input":
            {"type":"dateFormat","attributes":{"inputFormat":"MM/dd/yyyy","outputFormat":"ISO8601","input":"01/14/2025"}}}}"""
        assertEquals(NeededInput(NeedKind.TRANSFORM, "UTC To EST"), (output(json) as EvalResult.Needs).need)
        val context = EvalContext(resolveTransform = { name -> toEst.takeIf { name == "UTC To EST" } })
        assertEquals("2025-01-13T19:00Z", (output(json, context = context) as EvalResult.Value).text)
    }

    @Test
    fun `a reference to itself stops instead of running forever`() {
        val loop = transform("""{"name":"Loop","type":"reference","attributes":{"id":"Loop"}}""")
        val result = output(loop.toString(), context = EvalContext(resolveTransform = { loop }))
        assertTrue(result.toString(), result is EvalResult.Failure)
    }
    @Test
    fun `indexOf and lastIndexOf find a substring, as SailPoint's examples do`() {
        assertValue("0", """{"type":"indexOf","attributes":{"substring":"admin_"}}""", "admin_jsmith")
        assertValue("1", """{"type":"indexOf","attributes":{"substring":"b"}}""", "abcabcabc")
        assertValue("7", """{"type":"lastIndexOf","attributes":{"substring":"b"}}""", "abcabcabc")
        assertValue("-1", """{"type":"indexOf","attributes":{"substring":"z"}}""", "abc")
    }

    @Test
    fun `leftPad and rightPad fill out to a length`() {
        assertValue("00001234", """{"type":"leftPad","attributes":{"padding":"0","length":"8"}}""", "1234")
        assertValue("xxx1234", """{"type":"leftPad","attributes":{"padding":"x","length":"7"}}""", "1234")
        assertValue("12340000", """{"type":"rightPad","attributes":{"padding":"0","length":"8"}}""", "1234")
        assertValue("  ab", """{"type":"leftPad","attributes":{"length":"4"}}""", "ab")
        assertValue("abcdef", """{"type":"leftPad","attributes":{"length":"3"}}""", "abcdef")
        assertValue("ababx", """{"type":"leftPad","attributes":{"padding":"ab","length":"5"}}""", "x")
        assertValue(null, """{"type":"rightPad","attributes":{"length":"8"}}""")
    }

    @Test
    fun `replaceAll applies each pattern in turn, as SailPoint's examples do`() {
        assertValue("512-777-1234", """{"type":"replaceAll","attributes":{"table":{"[.]":"-","[a-zA-z]":""}}}""", "ad512.777.1234")
        assertValue("Enrique Jose Pinon", """{"type":"replaceAll","attributes":{"table":{"-":" ","\"":"'","ñ":"n"}}}""", "Enrique Jose-Piñon")
        assertValue("same", """{"type":"replaceAll","attributes":{}}""", "same")
    }

    @Test
    fun `getEndOfString takes the last characters, as a type or through the utility rule`() {
        assertValue("1234", """{"type":"getEndOfString","attributes":{"numChars":"4"}}""", "abcd1234")
        assertValue(null, """{"type":"getEndOfString","attributes":{"numChars":"16"}}""", "This is a test.")
        val rule = """{"type":"rule","attributes":{"name":"Cloud Services Deployment Utility","operation":"getEndOfString","numChars":"4"}}"""
        assertValue("1234", rule, "abcd1234")
        assertTrue(neededInputs(transform(rule)).isEmpty())
    }

    @Test
    fun `the utility rule generates random strings from the characters asked for`() {
        val json = """{"type":"rule","attributes":{"name":"Cloud Services Deployment Utility","operation":"generateRandomString",
            "includeNumbers":"false","includeSpecialChars":"false","length":"16"}}"""
        val text = (output(json) as EvalResult.Value).text.orEmpty()
        assertEquals(16, text.length)
        assertTrue(text, text.all { it.isLetter() })
    }

    @Test
    fun `any other rule says it only runs in ISC`() {
        val json = """{"type":"rule","attributes":{"name":"My Custom Rule"}}"""
        assertEquals(NeededInput(NeedKind.RULE, "My Custom Rule"), (output(json) as EvalResult.Needs).need)
    }

    @Test
    fun `dateCompare picks a condition by comparing two dates`() {
        val compare = { first: String, operator: String, second: String ->
            """{"type":"dateCompare","attributes":{"firstDate":$first,"secondDate":$second,"operator":"$operator",
                "positiveCondition":"yes","negativeCondition":"no"}}"""
        }
        assertValue("yes", compare("\"2025-01-14T00:00:00.000Z\"", "lt", "\"2025-01-15T00:00Z\""))
        assertValue("no", compare("\"2025-01-14T00:00:00.000Z\"", "GT", "\"2025-01-15T00:00Z\""))
        assertValue("yes", compare("\"2025-01-14T00:00Z\"", "LTE", "\"2025-01-14T00:00:00.000Z\""))
        assertValue("no", compare("\"2025-01-14T00:00Z\"", "LT", "\"2025-01-14T00:00:00.000Z\""))
        // SailPoint's example: hired on or before the end of 1995 is "legacy".
        val legacy = compare(
            "\"1990-05-01T00:00:00.000Z\"",
            "lte",
            """{"type":"dateFormat","attributes":{"input":"12/31/1995","inputFormat":"M/d/yyyy","outputFormat":"ISO8601"}}""",
        )
        assertValue("yes", legacy)
    }

    @Test
    fun `dateCompare reads now as the current time and says when a date isn't one`() {
        val json = """{"type":"dateCompare","attributes":{"firstDate":"2025-01-01T00:00Z","secondDate":"now","operator":"LT",
            "positiveCondition":"started","negativeCondition":"not yet"}}"""
        val context = EvalContext(now = java.time.Instant.parse("2025-06-01T00:00:00Z"))
        assertEquals("started", (output(json, context = context) as EvalResult.Value).text)
        val bad = """{"type":"dateCompare","attributes":{"firstDate":"01/14/2025","secondDate":"now","operator":"LT",
            "positiveCondition":"a","negativeCondition":"b"}}"""
        assertTrue(failure(bad).contains("ISO8601"))
    }
    @Test
    fun `what a referenced transform reads is needed too, and a missing one is asked for`() {
        val shared = transform("""{"name":"Dept","type":"upper","attributes":{"input":
            {"type":"accountAttribute","attributes":{"sourceName":"HR","attributeName":"dept"}}}}""")
        val json = transform("""{"type":"concat","attributes":{"values":[
            {"type":"reference","attributes":{"id":"Dept"}},
            {"type":"reference","attributes":{"id":"Gone"}}]}}""")
        assertEquals(
            listOf(NeededInput(NeedKind.ACCOUNT_ATTRIBUTE, "dept", "HR"), NeededInput(NeedKind.TRANSFORM, "Gone")),
            neededInputs(json) { name -> shared.takeIf { name == "Dept" } },
        )
        assertEquals(
            listOf(NeededInput(NeedKind.TRANSFORM, "Dept"), NeededInput(NeedKind.TRANSFORM, "Gone")),
            neededInputs(json),
        )
    }

    @Test
    fun `transforms that reference each other are followed once`() {
        val a = transform("""{"name":"A","type":"reference","attributes":{"id":"B"}}""")
        val b = transform("""{"name":"B","type":"concat","attributes":{"values":[
            {"type":"identityAttribute","attributes":{"name":"uid"}},{"type":"reference","attributes":{"id":"A"}}]}}""")
        val needs = neededInputs(a) { name -> if (name == "A") a else b }
        assertEquals(listOf(NeededInput(NeedKind.IDENTITY_ATTRIBUTE, "uid")), needs)
    }
    @Test
    fun `base64 encodes and decodes, as SailPoint's example does`() {
        assertValue("MTIzNA==", """{"type":"base64Encode"}""", "1234")
        assertValue("1234", """{"type":"base64Decode"}""", "MTIzNA==")
        assertValue("Piñon", """{"type":"base64Decode"}""", "UGnDsW9u")
        assertTrue(failure("""{"type":"base64Decode"}""", "not base64!").contains("isn't base64"))
        assertValue(null, """{"type":"base64Encode"}""")
    }

    @Test
    fun `decomposeDiacriticalMarks strips accents, as SailPoint's examples do`() {
        assertValue("Aric", """{"type":"decomposeDiacriticalMarks"}""", "Āric")
        assertValue("Dubcek", """{"type":"decomposeDiacriticalMarks"}""", "Dubçek")
    }

    @Test
    fun `normalizeNames cases names, as SailPoint's examples do`() {
        val json = """{"type":"normalizeNames"}"""
        assertValue("John von Smith", json, "jOHN VON SmITh")
        assertValue("Dr. John D. O'Brien", json, "Dr. JOHN D. O'BRIEN")
        assertValue("Mary Smith-Jones", json, "MARY SMITH-JONES")
        assertValue("Ronald McDonald", json, "RONALD MCDONALD")
        assertValue("Angus MacDonald", json, "angus macdonald")
        assertValue("Jack Mack", json, "JACK MACK")
        assertValue("John Smith III", json, "john smith iii")
        assertValue("Maria de la Cruz", json, "MARIA DE LA CRUZ")
    }
    @Test
    fun `e164phone formats valid numbers and gives nothing for others, as SailPoint's examples do`() {
        assertValue("+17792842727", """{"type":"e164phone"}""", "779.284.2727")
        assertValue("+15127772222", """{"type":"e164phone"}""", "512-777-2222")
        assertValue("+61412345678", """{"type":"e164phone","attributes":{"defaultRegion":"AU"}}""", "0412345678")
        assertValue(null, """{"type":"e164phone"}""", "12")
        assertValue(null, """{"type":"e164phone"}""", "not a number")
        assertTrue(failure("""{"type":"e164phone","attributes":{"defaultRegion":"Narnia"}}""", "0412345678").contains("region"))
    }

    @Test
    fun `iso3166 reads names and codes and writes the format asked for, as SailPoint's examples do`() {
        assertValue("US", """{"type":"iso3166"}""", "United States of America")
        assertValue("724", """{"type":"iso3166","attributes":{"format":"numeric"}}""", "ES")
        assertValue("ESP", """{"type":"iso3166","attributes":{"format":"alpha3"}}""", "España")
        assertValue("ES", """{"type":"iso3166"}""", "spain")
        assertValue("ES", """{"type":"iso3166"}""", "724")
        assertValue("AF", """{"type":"iso3166"}""", "4")
        assertValue(null, """{"type":"iso3166"}""", "Atlantis")
    }

    @Test
    fun `rfc5646 converts through SailPoint's table`() {
        assertValue("es", """{"type":"rfc5646"}""", "Spanish")
        assertValue("es", """{"type":"rfc5646"}""", "SPA")
        assertValue("en", """{"type":"rfc5646"}""", "english")
        assertValue(null, """{"type":"rfc5646"}""", "Klingon")
    }

    @Test
    fun `displayName prefers the preferred name over the given name, as SailPoint's examples do`() {
        val json = """{"type":"displayName","attributes":{"input":"input"}}"""
        val names = { preferred: String? ->
            EvalContext(
                identityAttributes = listOfNotNull(preferred?.let { "preferredName" to it }, "firstname" to "Jonathan", "lastname" to "Doe").toMap(),
                absent = if (preferred == null) setOf(NeededInput(NeedKind.IDENTITY_ATTRIBUTE, "preferredName")) else emptySet(),
            )
        }
        assertEquals("John Doe", (output(json, context = names("John")) as EvalResult.Value).text)
        assertEquals("Jonathan Doe", (output(json, context = names(null)) as EvalResult.Value).text)
        assertEquals(
            listOf("preferredName", "firstname", "lastname").map { NeededInput(NeedKind.IDENTITY_ATTRIBUTE, it) },
            neededInputs(transform(json)),
        )
    }

    @Test
    fun `usernameGenerator gives the first pattern it can fill, with no counter`() {
        val json = """{"type":"usernameGenerator","attributes":{
            "patterns":["${'$'}fn.${'$'}mn.${'$'}ln","${'$'}fi${'$'}ln${'$'}{uniqueCounter}"],
            "fn":"john","mn":"","ln":"doe",
            "fi":{"type":"substring","attributes":{"input":"john","begin":0,"end":1}}}}"""
        assertValue("jdoe", json)
        val first = """{"type":"usernameGenerator","attributes":{"patterns":["${'$'}fn.${'$'}ln"],"fn":"adam","ln":"smith"}}"""
        assertValue("adam.smith", first)
        val none = """{"type":"usernameGenerator","attributes":{"patterns":["${'$'}fn.${'$'}ln"],"fn":"adam","ln":""}}"""
        assertTrue(failure(none).contains("No pattern"))
    }
}
