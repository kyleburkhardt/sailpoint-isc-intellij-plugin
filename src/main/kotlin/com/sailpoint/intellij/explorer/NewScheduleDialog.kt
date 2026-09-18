package com.sailpoint.intellij.explorer

import com.google.gson.JsonObject
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.sailpoint.intellij.api.IscItem
import com.sailpoint.intellij.api.ResourceKind
import com.sailpoint.intellij.editor.IscEditorService
import com.sailpoint.intellij.schedule.ScheduleBuilder
import com.sailpoint.intellij.schedule.ScheduleTimeZone
import javax.swing.JComponent

/** Picks the type and cron expression of a new aggregation schedule for [source]. */
class NewScheduleDialog(private val project: Project, private val source: IscItem, private val types: List<String>) : DialogWrapper(project) {
    private var type: String? = types.firstOrNull()
    private val schedule = ScheduleBuilder("0 0 5,13,21 * * ?", ScheduleTimeZone.resolve(project, source.tenantId))

    init {
        title = "New Schedule for ${source.name}"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Type:") {
            comboBox(types, textListCellRenderer { it?.let(::typeName) }).bindItem(::type).align(AlignX.FILL)
        }
        schedule.addTo(this)
    }

    fun showAndCreate() {
        if (showAndGet()) {
            project.service<IscEditorService>().newSchedule(source, type ?: return, schedule.cronExpression)
        }
    }

    companion object {
        private fun typeName(type: String) =
            ResourceKind.SOURCE_SCHEDULES.toItem("", JsonObject().apply { addProperty("type", type) }).name
    }
}
