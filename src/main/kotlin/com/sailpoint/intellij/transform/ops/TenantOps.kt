package com.sailpoint.intellij.transform.ops

import com.google.gson.JsonObject
import com.sailpoint.intellij.api.string
import com.sailpoint.intellij.transform.EvalResult
import com.sailpoint.intellij.transform.NeedKind
import com.sailpoint.intellij.transform.NeededInput
import com.sailpoint.intellij.transform.Op
import com.sailpoint.intellij.transform.OpCall

/**
 * Operations that read data only the tenant has. Each asks for a test value through [EvalResult.Needs] instead of
 * failing, so the rest of the transform still previews around it.
 */
internal object TenantOps {

    val all: Map<String, Op> = mapOf(
        "identityAttribute" to Op(::identityAttribute),
        "accountAttribute" to Op(::accountAttribute),
        "getReferenceIdentityAttribute" to Op(::referenceAttribute),
        "reference" to Op(::reference),
        "displayName" to Op(::displayName),
    )

    /**
     * The identity attributes `displayName` reads. SailPoint doesn't name them; these are ISC's usual first and last
     * name attributes, and the preferred name the docs describe.
     */
    val DISPLAY_NAME_PARTS = listOf("preferredName", "firstname", "lastname")

    /** The source an `accountAttribute` reads from, by whichever of its three names the transform uses. */
    fun accountSource(attributes: JsonObject): String? =
        attributes.string("sourceName") ?: attributes.string("applicationName") ?: attributes.string("applicationId")

    private fun identityAttribute(call: OpCall): EvalResult {
        val attribute = call.text("name") ?: return call.missing("name")
        val supplied = call.context.identityAttributes[attribute]
        return supplied(call, supplied, NeededInput(NeedKind.IDENTITY_ATTRIBUTE, attribute))
    }

    private fun accountAttribute(call: OpCall): EvalResult {
        val source = accountSource(call.attributes) ?: return call.missing("sourceName")
        val attribute = call.text("attributeName") ?: return call.missing("attributeName")
        val supplied = call.context.accountAttributes[source]?.get(attribute)
        return supplied(call, supplied, NeededInput(NeedKind.ACCOUNT_ATTRIBUTE, attribute, source))
    }

    private fun referenceAttribute(call: OpCall): EvalResult {
        val uid = call.text("uid") ?: return call.missing("uid")
        val attribute = call.text("attributeName") ?: return call.missing("attributeName")
        val supplied = call.context.referenceAttributes[uid]?.get(attribute)
        return supplied(call, supplied, NeededInput(NeedKind.REFERENCE_IDENTITY_ATTRIBUTE, attribute, uid))
    }

    /** Another saved transform, run on the value flowing in. Its `id` is the transform's name. */
    private fun reference(call: OpCall): EvalResult {
        val name = call.text("id") ?: return call.missing("id")
        val transform = call.context.resolveTransform(name) ?: return EvalResult.Needs(NeededInput(NeedKind.TRANSFORM, name))
        return call.runReferenced(name, transform)
    }

    /** The preferred name, or else the given name, then the family name: "John Doe". */
    private fun displayName(call: OpCall): EvalResult {
        val (preferred, given, family) = DISPLAY_NAME_PARTS.map { name ->
            supplied(call, call.context.identityAttributes[name], NeededInput(NeedKind.IDENTITY_ATTRIBUTE, name))
        }
        listOf(preferred, given, family).firstOrNull { it !is EvalResult.Value }?.let { return it }
        val first = preferred.textOrNull?.takeIf { it.isNotBlank() } ?: given.textOrNull
        return EvalResult.Value(listOfNotNull(first, family.textOrNull).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { null })
    }

    /** The test value for [need], nothing when it's known to be empty, or else a request for one. */
    private fun supplied(call: OpCall, value: String?, need: NeededInput): EvalResult = when {
        value != null -> EvalResult.Value(value)
        need in call.context.absent -> EvalResult.Value(null)
        else -> EvalResult.Needs(need)
    }
}
