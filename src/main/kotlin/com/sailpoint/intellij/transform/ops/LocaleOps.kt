package com.sailpoint.intellij.transform.ops

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.sailpoint.intellij.transform.EvalResult
import com.sailpoint.intellij.transform.Op
import com.sailpoint.intellij.transform.OpCall
import java.util.Locale

/** Operations that put phone numbers, countries and languages into standard forms. Anything unrecognized gives nothing. */
internal object LocaleOps {

    val all: Map<String, Op> = mapOf(
        "e164phone" to Op(::e164phone),
        "iso3166" to Op(::iso3166),
        "rfc5646" to Op(::rfc5646),
    )

    // ------------------------------------------------------------ e164phone

    /** Through Google's libphonenumber, whose `defaultRegion` ISC's attribute is named after. */
    private fun e164phone(call: OpCall): EvalResult {
        val input = call.input?.takeIf { it.isNotBlank() } ?: return EvalResult.Value(null)
        val region = call.text("defaultRegion")?.trim()?.uppercase()?.ifEmpty { null } ?: "US"
        val phones = PhoneNumberUtil.getInstance()
        if (region !in phones.supportedRegions) {
            return EvalResult.Failure("\"$region\" isn't a region; use an ISO 3166 alpha-2 code such as US or AU.")
        }
        val number = try {
            phones.parse(input, region)
        } catch (e: NumberParseException) {
            return EvalResult.Value(null)
        }
        return EvalResult.Value(if (phones.isValidNumber(number)) phones.format(number, PhoneNumberUtil.PhoneNumberFormat.E164) else null)
    }

    // -------------------------------------------------------------- iso3166

    private data class Country(val alpha2: String, val alpha3: String, val numeric: String)

    private fun iso3166(call: OpCall): EvalResult {
        val format = call.text("format")?.trim()?.lowercase()?.ifEmpty { null } ?: "alpha2"
        if (format !in FORMATS) return EvalResult.Failure("\"$format\" isn't a format; use alpha2, alpha3 or numeric.")
        val input = call.input?.trim()?.takeIf { it.isNotEmpty() } ?: return EvalResult.Value(null)
        val country = COUNTRIES[key(input)] ?: input.toIntOrNull()?.let { COUNTRIES[it.toString().padStart(3, '0')] }
            ?: return EvalResult.Value(null)
        return EvalResult.Value(
            when (format) {
                "alpha3" -> country.alpha3
                "numeric" -> country.numeric
                else -> country.alpha2
            },
        )
    }

    /**
     * Every way a country can be written, to the country: its codes, its English name from the ISO table, and the
     * names Java knows for it, in English and in each of its own languages.
     */
    private val COUNTRIES: Map<String, Country> by lazy {
        val byName = HashMap<String, Country>()
        val locales = Locale.getAvailableLocales().filter { it.country.isNotEmpty() }.groupBy { it.country }
        table("iso3166.tsv").forEach { columns ->
            val (alpha2, alpha3, numeric, name) = columns
            val country = Country(alpha2, alpha3, numeric)
            val region = Locale.of("", alpha2)
            val names = listOf(alpha2, alpha3, numeric, name, region.getDisplayCountry(Locale.ENGLISH)) +
                locales[alpha2].orEmpty().map { region.getDisplayCountry(it) }
            names.filter { it.isNotBlank() }.forEach { byName.putIfAbsent(key(it), country) }
        }
        byName
    }

    // -------------------------------------------------------------- rfc5646

    /** Through SailPoint's own conversion table, from a language's name or three-letter code. */
    private fun rfc5646(call: OpCall): EvalResult {
        val input = call.input?.trim()?.takeIf { it.isNotEmpty() } ?: return EvalResult.Value(null)
        return EvalResult.Value(LANGUAGES[key(input)])
    }

    private val LANGUAGES: Map<String, String> by lazy {
        table("rfc5646.tsv").associate { (name, tag) -> key(name) to tag }
    }

    // --------------------------------------------------------------- shared

    private fun key(text: String): String = text.trim().lowercase(Locale.ROOT)

    /** A tab-separated table from the plugin's resources, without its `#` comments. */
    private fun table(name: String): List<List<String>> =
        LocaleOps::class.java.getResourceAsStream("/transform/$name")!!.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }.toList()
        }

    private val FORMATS = setOf("alpha2", "alpha3", "numeric")
}
