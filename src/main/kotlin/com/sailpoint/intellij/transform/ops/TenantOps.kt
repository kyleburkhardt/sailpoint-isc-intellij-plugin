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
    )

    /** The source an `accountAttribute` reads from, by whichever of its three names the transform uses. */
    fun accountSource(attributes: JsonObject): String? =
        attributes.string("sourceName") ?: attributes.string("applicationName") ?: attributes.string("applicationId")

    private fun identityAttribute(call: OpCall): EvalResult {
        val attribute = call.text("name") ?: return call.missing("name")
        val supplied = call.context.identityAttributes[attribute]
        return supplied?.let { EvalResult.Value(it) } ?: EvalResult.Needs(NeededInput(NeedKind.IDENTITY_ATTRIBUTE, attribute))
    }

    private fun accountAttribute(call: OpCall): EvalResult {
        val source = accountSource(call.attributes) ?: return call.missing("sourceName")
        val attribute = call.text("attributeName") ?: return call.missing("attributeName")
        val supplied = call.context.accountAttributes[source]?.get(attribute)
        return supplied?.let { EvalResult.Value(it) } ?: EvalResult.Needs(NeededInput(NeedKind.ACCOUNT_ATTRIBUTE, attribute, source))
    }

    private fun referenceAttribute(call: OpCall): EvalResult {
        val uid = call.text("uid") ?: return call.missing("uid")
        val attribute = call.text("attributeName") ?: return call.missing("attributeName")
        val supplied = call.context.referenceAttributes[uid]?.get(attribute)
        return supplied?.let { EvalResult.Value(it) }
            ?: EvalResult.Needs(NeededInput(NeedKind.REFERENCE_IDENTITY_ATTRIBUTE, attribute, uid))
    }
}
