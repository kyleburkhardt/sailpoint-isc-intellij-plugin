package com.sailpoint.intellij.transform

import com.sailpoint.intellij.transform.ops.DateOps
import com.sailpoint.intellij.transform.ops.GeneratorOps
import com.sailpoint.intellij.transform.ops.RuleOps
import com.sailpoint.intellij.transform.ops.StringOps
import com.sailpoint.intellij.transform.ops.TenantOps
import com.sailpoint.intellij.transform.ops.ValueOps

/**
 * The operations the preview can run. An operation missing from here is reported as "not previewed yet" rather than
 * silently producing nothing, so the editor never implies an answer it didn't work out.
 */
internal val OPERATIONS: Map<String, Op> = buildMap {
    putAll(StringOps.all)
    putAll(ValueOps.all)
    putAll(TenantOps.all)
    putAll(GeneratorOps.all)
    putAll(DateOps.all)
    putAll(RuleOps.all)
}

/** The first result that isn't a plain value, which an operation propagates instead of its own answer. */
internal fun List<EvalResult>.firstIssue(): EvalResult? = firstOrNull { it !is EvalResult.Value }
