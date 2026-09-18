package com.sailpoint.intellij.schedule

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/** Edits the cron expression of an open schedule with the [ScheduleBuilder]. */
class EditScheduleDialog(project: Project, tenantId: String, scheduleName: String, cronExpression: String) : DialogWrapper(project) {
    private val builder = ScheduleBuilder(cronExpression, ScheduleTimeZone.resolve(project, tenantId))

    val cronExpression: String get() = builder.cronExpression

    init {
        title = "Edit Schedule: $scheduleName"
        setOKButtonText("Apply")
        init()
    }

    override fun createCenterPanel(): JComponent = panel { builder.addTo(this) }
}
