package com.sailpoint.intellij.explorer

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

class IscToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = IscExplorerPanel(project)
        val content = toolWindow.contentManager.factory.createContent(panel, null, false)
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)
    }
}
