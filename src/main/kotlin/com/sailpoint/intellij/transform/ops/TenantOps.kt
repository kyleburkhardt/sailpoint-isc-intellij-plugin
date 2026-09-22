package com.sailpoint.intellij.transform.ops

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
    )

    private fun identityAttribute(call: OpCall): EvalResult {
        val attribute = call.text("name") ?: return call.missing("name")
        val supplied = call.context.identityAttributes[attribute]
        return supplied?.let { EvalResult.Value(it) } ?: EvalResult.Needs(NeededInput(NeedKind.IDENTITY_ATTRIBUTE, attribute))
    }
}
