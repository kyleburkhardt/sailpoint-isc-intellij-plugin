package com.sailpoint.intellij.explorer

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import com.sailpoint.intellij.api.ACCOUNTS
import com.sailpoint.intellij.api.CREATE_MACHINE_ACCOUNT
import com.sailpoint.intellij.api.MACHINE_ACCOUNTS
import com.sailpoint.intellij.api.ACCOUNT_AGGREGATION
import com.sailpoint.intellij.api.ACCOUNT_SCHEMA
import com.sailpoint.intellij.api.ENTITLEMENTS
import com.sailpoint.intellij.api.GROUP_AGGREGATION
import com.sailpoint.intellij.api.IscClient
import com.sailpoint.intellij.api.IscItem
import com.sailpoint.intellij.api.ResourceKind
import com.sailpoint.intellij.editor.IscChangeListener
import com.sailpoint.intellij.editor.IscEditorService
import com.sailpoint.intellij.run.SourceRunner
import com.sailpoint.intellij.settings.IscConfigurable
import com.sailpoint.intellij.settings.IscSettings
import com.sailpoint.intellij.settings.IscSettingsListener
import com.sailpoint.intellij.settings.IscTenant
import com.sailpoint.intellij.settings.TenantDialog
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Tree of configured tenants, each with its object categories:
 * `Tenant > Transforms > transform`. Objects with child kinds get their own categories,
 * e.g. `Tenant > Sources > source > Schemas > schema`. Categories load lazily on first expand.
 */
class IscExplorerPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private sealed interface NodeData
    private class TenantData(val tenant: IscTenant) : NodeData
    /** [parent] is the object whose children this lists, for kinds with a [ResourceKind.parent]. */
    private class CategoryData(
        val tenantId: String,
        val kind: ResourceKind,
        val parent: IscItem? = null,
        /** Which slice of the kind this lists, for kinds shown in more than one folder. */
        val variant: String = "",
        val filter: (IscItem) -> Boolean = { true },
    ) : NodeData {
        var loaded = false
        var count: Int? = null
    }
    private class ItemData(val item: IscItem) : NodeData
    /**
     * A folder grouping some of [parent]'s children, e.g. `Accounts`. Its static children come from the kinds
     * in that group; folders with [content] load the rest from ISC when first expanded.
     */
    private class GroupData(val tenantId: String, val name: String, val parent: IscItem, val content: FolderContent? = null) : NodeData {
        var loaded = false
    }

    /** Children a folder works out from the source's schemas and schedules. */
    private enum class FolderContent { ACCOUNTS, ENTITLEMENTS }
    private class MessageData(val text: String, val isError: Boolean = false) : NodeData

    private companion object {
        /** Source type of CSV sources, which can aggregate from an uploaded file. */
        const val DELIMITED_FILE = "DelimitedFile"
    }

    /** Category variants, for kinds listed in more than one folder. */
    private object Variants {
        const val HUMAN = "human"
        const val MACHINE = "machine"
    }

    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)

    private val refresh = action("Refresh", AllIcons.Actions.Refresh, { true }) { refreshSelection() }
    private val open = action("Open", AllIcons.Actions.MenuOpen, { true }) { openSelection() }
    private val addTenant = action("Add Tenant…", AllIcons.General.Add, { true }) { addTenant() }
    private val editTenant = action("Edit Tenant…", AllIcons.Actions.Edit, { selectedTenant() != null }) { editTenant() }
    private val removeTenant = action("Remove Tenant", AllIcons.General.Remove, { selectedTenant() != null }) { removeTenant() }
    private val newTransform = action("New Transform…", AllIcons.FileTypes.Json, { tenants().isNotEmpty() }) {
        NewTransformDialog(project, selectedTenant()?.id).showAndCreate()
    }
    private val newSource = action("New Source…", AllIcons.General.Add, { selectedCategory()?.kind == ResourceKind.SOURCES }) {
        selectedCategory()?.let { NewSourceDialog(project, it.tenantId).showAndChoose() }
    }
    private val settings = action("Settings…", AllIcons.General.GearPlain, { true }) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, IscConfigurable::class.java)
    }

    private val newSchedule = action("New Aggregation Schedule…", AllIcons.General.Add, { selectedFolder()?.content != null }) {
        newSchedule()
    }

    private val newProvisioningPolicy = action(
        "New Provisioning Policy…", AllIcons.General.Add,
        { selectedCategory()?.kind == ResourceKind.SOURCE_PROVISIONING_POLICIES },
    ) {
        selectedCategory()?.let { NewProvisioningPolicyDialog(project, it.parent ?: return@action, it.variant == Variants.MACHINE).showAndCreate() }
    }

    private val deleteItem = action("Delete…", AllIcons.General.Remove, { selectedItem()?.kind?.deletable == true }) {
        deleteSelection()
    }

    /** Delete for a one-per-parent config, which removes its settings but keeps the entry. */
    private val removeConfiguration = action("Remove Configuration…", AllIcons.General.Remove, { selectedItem()?.kind?.deletable == true }) {
        deleteSelection()
    }

    /** Run submenu on sources: aggregations, connector checks and the like, run in ISC. */
    private val runGroup = DefaultActionGroup("Run", true).apply {
        add(sourceAction("Aggregate Accounts") { runner().aggregateAccounts(it) })
        add(sourceAction("Aggregate Accounts, Reprocessing All") { runner().aggregateAccounts(it, reprocessAll = true) })
        add(sourceAction("Aggregate Accounts from File…", csvOnly = true) { source ->
            chooseCsv("Accounts CSV")?.let { runner().aggregateAccounts(source, file = it) }
        })
        add(sourceAction("Aggregate Entitlements") { runner().aggregateEntitlements(it) })
        add(sourceAction("Aggregate Entitlements from File…", csvOnly = true) { source ->
            chooseCsv("Entitlements CSV")?.let { runner().aggregateEntitlements(source, file = it) }
        })
        add(sourceAction("Process Uncorrelated Accounts") { runner().processUncorrelatedAccounts(it) })
        addSeparator()
        add(sourceAction("Test Connection") { runner().testConnection(it) })
        add(sourceAction("Test Configuration") { runner().testConfiguration(it) })
        add(sourceAction("Ping Cluster") { runner().pingCluster(it) })
        add(sourceAction("Peek Resource Objects…") { runner().peekResourceObjects(it) })
        addSeparator()
        add(sourceAction("Synchronize Attributes (Experimental)") { runner().synchronizeAttributes(it) })
        addSeparator()
        add(sourceAction("Upload Connector File…") { source ->
            val descriptor = FileChooserDescriptorFactory.singleFile().withTitle("Connector File (e.g. a JDBC Driver)")
            FileChooser.chooseFile(descriptor, project, null)?.toNioPath()?.let { runner().uploadConnectorFile(source, it) }
        })
        add(sourceAction("Remove All Accounts…") { runner().removeAllAccounts(it) })
    }

    private val deleteSource = action("Delete Source…", AllIcons.General.Remove, { selectedItem()?.kind == ResourceKind.SOURCES }) {
        selectedItem()?.takeIf { it.kind == ResourceKind.SOURCES }?.let { runner().deleteSource(it) }
    }

    private val newEntitlementType = action(
        "New Entitlement Type…", AllIcons.General.Add, { selectedFolder()?.content == FolderContent.ENTITLEMENTS },
    ) {
        newEntitlementType()
    }

    /** Actions shown in the context menu of every node, above the node's own [nodeActions]. */
    private val commonActions: List<AnAction> = listOf(refresh)

    /** Context menu actions specific to the right-clicked node; null when nothing is selected. */
    private fun nodeActions(node: DefaultMutableTreeNode?): List<AnAction> = when (val data = node?.userObject as? NodeData) {
        null -> listOf(addTenant)
        is TenantData -> listOf(addTenant, editTenant, removeTenant)
        is CategoryData -> when (data.kind) {
            ResourceKind.SOURCES -> listOf(newSource)
            ResourceKind.TRANSFORMS -> listOf(newTransform)
            ResourceKind.SOURCE_PROVISIONING_POLICIES -> listOf(newProvisioningPolicy)
            else -> emptyList()
        }
        is GroupData -> when (data.content) {
            FolderContent.ACCOUNTS -> listOf(newSchedule)
            FolderContent.ENTITLEMENTS -> listOf(newSchedule, newEntitlementType)
            null -> emptyList()
        }
        is ItemData -> listOfNotNull(
            open,
            runGroup.takeIf { data.item.kind == ResourceKind.SOURCES },
            when {
                data.item.kind == ResourceKind.SOURCES -> deleteSource
                // Every source needs its account schema, so only entitlement types' schemas can go.
                data.item.kind == ResourceKind.SOURCE_SCHEMAS -> deleteItem.takeIf { entitlementType(node) != null }
                !data.item.kind.deletable -> null
                data.item.kind.singleton -> removeConfiguration
                else -> deleteItem
            },
        )
        is MessageData -> emptyList()
    }

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = Renderer()
        // Double-click toggles categories but only opens objects, even those with child categories (see below).
        tree.toggleClickCount = 0
        tree.emptyText.text = "No SailPoint tenants configured"
        tree.emptyText.appendSecondaryText("Add tenant", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { addTenant() }
        TreeSpeedSearch.installOn(tree, false) { path ->
            when (val data = (path.lastPathComponent as DefaultMutableTreeNode).userObject) {
                is TenantData -> data.tenant.name
                is CategoryData -> data.kind.displayName
                is GroupData -> data.name
                is ItemData -> data.item.name
                else -> ""
            }
        }
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) {
                val node = event.path.lastPathComponent as DefaultMutableTreeNode
                when (val data = node.userObject) {
                    is CategoryData -> if (!data.loaded) load(node, data)
                    is GroupData -> if (data.content != null && !data.loaded) loadFolder(node, data)
                    else -> Unit
                }
            }

            override fun treeCollapsed(event: TreeExpansionEvent) = Unit
        })
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                if (openSelection()) return true
                val path = tree.selectionPath ?: return false
                if (tree.isExpanded(path)) tree.collapsePath(path) else tree.expandPath(path)
                return true
            }
        }.installOn(tree)
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && openSelection()) e.consume()
            }
        })

        toolbar = ActionManager.getInstance()
            .createActionToolbar("SailPointIscExplorer", DefaultActionGroup(addTenant, refresh, newTransform, Separator.getInstance(), settings), true)
            .also { it.targetComponent = this }
            .component
        PopupHandler.installPopupMenu(tree, ContextMenu(), "SailPointIscExplorerPopup")
        setContent(ScrollPaneFactory.createScrollPane(tree, true))

        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(IscSettingsListener.TOPIC, IscSettingsListener { rebuild() })
        project.messageBus.connect(this)
            .subscribe(IscChangeListener.TOPIC, IscChangeListener { tenantId, kind, parentId -> reloadShowing(tenantId, kind, parentId) })
        rebuild()
    }

    /** Rebuilds the tree from settings, keeping loaded data for tenants whose connection didn't change. */
    private fun rebuild() {
        val previous = root.children().toList().map { it as DefaultMutableTreeNode }
            .associateBy { (it.userObject as TenantData).tenant.id }
        val expanded = TreeUtil.collectExpandedUserObjects(tree).mapNotNull { key(it) }.toSet()
        val selected = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.let { key(it.userObject) }

        root.removeAllChildren()
        for (tenant in tenants()) {
            val old = previous[tenant.id]
            val oldTenant = (old?.userObject as? TenantData)?.tenant
            val node = if (old != null && oldTenant?.baseUrl == tenant.baseUrl && oldTenant.clientId == tenant.clientId) {
                old.apply { userObject = TenantData(tenant) }
            } else {
                tenantNode(tenant)
            }
            root.add(node)
        }
        model.reload()

        TreeUtil.treeNodeTraverser(root).forEach { node ->
            val data = (node as DefaultMutableTreeNode).userObject
            val path = TreePath(node.path)
            if (key(data) in expanded) tree.expandPath(path)
            if (selected != null && key(data) == selected) tree.selectionPath = path
        }
        if (root.childCount == 1 && expanded.isEmpty()) tree.expandRow(0)
    }

    private fun key(data: Any?): String? = when (data) {
        is TenantData -> data.tenant.id
        is CategoryData -> "${data.tenantId}/${data.parent?.id}/${data.kind}/${data.variant}"
        is GroupData -> "${data.tenantId}/${data.parent.id}/group/${data.name}"
        is ItemData -> "${data.item.tenantId}/${data.item.parent?.id}/${data.item.kind}/${data.item.id}"
        else -> null
    }

    private fun tenantNode(tenant: IscTenant) = DefaultMutableTreeNode(TenantData(tenant)).apply {
        ResourceKind.entries.filter { it.parent == null }.forEach { add(categoryNode(tenant.id, it)) }
    }

    private fun itemNode(item: IscItem) = DefaultMutableTreeNode(ItemData(item)).apply {
        item.kind.children.filter { it.group == null }.forEach { add(childNode(item, it)) }
        item.kind.childFolders.forEach { add(folderNode(item, it)) }
    }

    /** A folder of [parent]'s children, with a placeholder while its ISC-loaded part is still to come. */
    private fun folderNode(parent: IscItem, name: String) = DefaultMutableTreeNode(
        GroupData(parent.tenantId, name, parent, folderContent(name)),
    ).apply {
        if (folderContent(name) != null) add(DefaultMutableTreeNode(MessageData("Loading…")))
        if (name == MACHINE_ACCOUNTS && ResourceKind.SOURCE_PROVISIONING_POLICIES.parent == parent.kind) {
            add(
                categoryNode(parent.tenantId, ResourceKind.SOURCE_PROVISIONING_POLICIES, parent, Variants.MACHINE) {
                    it.detail == CREATE_MACHINE_ACCOUNT
                },
            )
        }
        parent.kind.children.filter { it.group == name }.forEach { add(childNode(parent, it)) }
    }

    private fun folderContent(name: String) = when (name) {
        ACCOUNTS -> FolderContent.ACCOUNTS
        ENTITLEMENTS -> FolderContent.ENTITLEMENTS
        else -> null
    }

    /** A category to expand, or the object itself for a kind with only one per parent. */
    private fun childNode(parent: IscItem, kind: ResourceKind): DefaultMutableTreeNode = when {
        kind.singleton -> DefaultMutableTreeNode(ItemData(IscItem(parent.tenantId, kind, "", kind.displayName, null, parent)))
        // Machine account policies live in the Machine Accounts folder instead; the detail holds the usage type.
        kind == ResourceKind.SOURCE_PROVISIONING_POLICIES ->
            categoryNode(parent.tenantId, kind, parent, Variants.HUMAN) { it.detail != CREATE_MACHINE_ACCOUNT }
        else -> categoryNode(parent.tenantId, kind, parent)
    }

    private fun categoryNode(
        tenantId: String,
        kind: ResourceKind,
        parent: IscItem? = null,
        variant: String = "",
        filter: (IscItem) -> Boolean = { true },
    ) = DefaultMutableTreeNode(CategoryData(tenantId, kind, parent, variant, filter)).apply {
            // Placeholder child so the node is expandable before anything is loaded.
            add(DefaultMutableTreeNode(MessageData("Loading…")))
        }

    /** Reloads the selected category, or every category of the selected tenant, or everything. */
    private fun refreshSelection() {
        val selected = generateSequence(tree.lastSelectedPathComponent as? DefaultMutableTreeNode) { it.parent as? DefaultMutableTreeNode }
            .firstOrNull { it.userObject is CategoryData || (it.userObject as? GroupData)?.content != null || it.userObject is TenantData }
        val nodes = when (selected?.userObject) {
            is CategoryData, is GroupData -> listOf(selected)
            is TenantData -> selected.childNodes()
            else -> root.childNodes().flatMap { it.childNodes() }
        }
        nodes.forEach { replaceNode(it) }
    }

    /** Reloads the loaded categories and folders that list [kind] objects of [parentId], after ISC changed them. */
    private fun reloadShowing(tenantId: String, kind: ResourceKind, parentId: String?) {
        TreeUtil.treeNodeTraverser(root).map { it as DefaultMutableTreeNode }.filter { node ->
            when (val data = node.userObject) {
                is CategoryData -> data.loaded && data.tenantId == tenantId && data.kind == kind && data.parent?.id == parentId
                // Hidden kinds (schemas, schedules) are listed by the folders that load them.
                is GroupData -> data.loaded && data.content != null && kind.hidden && data.tenantId == tenantId && data.parent.id == parentId
                else -> false
            }
        }.toList().forEach { replaceNode(it) }
    }

    /** Swaps a category or folder for an unloaded copy of itself, keeping its place and expanded state. */
    private fun replaceNode(node: DefaultMutableTreeNode) {
        // Skip nodes an earlier replacement already took out of the tree.
        if (node.root !== root) return
        val fresh = when (val data = node.userObject) {
            is CategoryData -> categoryNode(data.tenantId, data.kind, data.parent, data.variant, data.filter)
            is GroupData -> folderNode(data.parent, data.name)
            else -> return
        }
        val wasExpanded = tree.isExpanded(TreePath(node.path))
        val parent = node.parent as? DefaultMutableTreeNode ?: return
        val index = parent.getIndex(node)
        model.removeNodeFromParent(node)
        model.insertNodeInto(fresh, parent, index)
        if (wasExpanded) tree.expandPath(TreePath(fresh.path))
    }

    /** Puts a folder's loaded children among its fixed ones, in the order the kinds are declared. */
    private fun DefaultMutableTreeNode.sortChildren() {
        val sorted = childNodes().sortedBy {
            when (val data = it.userObject) {
                is ItemData -> data.item.kind.ordinal
                is CategoryData -> data.kind.ordinal
                // Folders of entitlement types come after the kinds themselves.
                else -> Int.MAX_VALUE
            }
        }
        removeAllChildren()
        sorted.forEach { add(it) }
    }

    private fun DefaultMutableTreeNode.childNodes(): List<DefaultMutableTreeNode> =
        children().toList().map { it as DefaultMutableTreeNode }

    private fun load(node: DefaultMutableTreeNode, data: CategoryData) {
        data.loaded = true
        val tenantName = service<IscSettings>().findTenant(data.tenantId)?.name ?: "tenant"
        val what = data.kind.displayName.lowercase() + (data.parent?.let { " of ${it.name}" } ?: "")
        object : Task.Backgroundable(project, "Loading $what from $tenantName", true) {
            private var items: List<IscItem> = emptyList()
            private var error: String? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    val limit = if (data.kind == ResourceKind.IDENTITIES) 1_000 else 10_000
                    items = service<IscClient>().list(data.tenantId, data.kind, data.parent, limit).filter(data.filter)
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message ?: e.toString()
                }
            }

            override fun onFinished() {
                // A refresh or settings change may have replaced this node while we were loading.
                if (TreeUtil.findNodeWithObject(root, data) !== node) return
                node.removeAllChildren()
                when {
                    error != null -> {
                        data.loaded = false
                        node.add(DefaultMutableTreeNode(MessageData(error!!, isError = true)))
                    }
                    items.isEmpty() -> node.add(DefaultMutableTreeNode(MessageData("None")))
                    else -> items.forEach { node.add(itemNode(it)) }
                }
                data.count = items.size.takeIf { error == null }
                model.nodeStructureChanged(node)
            }
        }.queue()
    }

    /** Fills a folder with what its source's schemas and schedules say belongs in it. */
    private fun loadFolder(node: DefaultMutableTreeNode, data: GroupData) {
        data.loaded = true
        val source = data.parent
        object : Task.Backgroundable(project, "Loading ${data.name.lowercase()} of ${source.name}", true) {
            private var children: List<DefaultMutableTreeNode> = emptyList()
            private var error: String? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    val client = service<IscClient>()
                    val schemas = client.list(source.tenantId, ResourceKind.SOURCE_SCHEMAS, source)
                    val schedules = client.list(source.tenantId, ResourceKind.SOURCE_SCHEDULES, source)
                    fun schedule(type: String) = schedules.firstOrNull { it.id == type }
                        ?.let { itemNode(it.copy(name = "Aggregation Schedule")) }
                    children = when (data.content) {
                        FolderContent.ACCOUNTS -> listOfNotNull(
                            schemas.firstOrNull { it.name == ACCOUNT_SCHEMA }?.let { itemNode(it.copy(name = "Schema")) },
                            schedule(ACCOUNT_AGGREGATION),
                        )
                        // One folder per entitlement type, holding that type's schema.
                        else -> listOfNotNull(schedule(GROUP_AGGREGATION)) +
                            schemas.filter { it.name != ACCOUNT_SCHEMA }.map { schema ->
                                DefaultMutableTreeNode(GroupData(source.tenantId, schema.name, source)).apply {
                                    add(itemNode(schema.copy(name = "Schema")))
                                }
                            }
                    }
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message ?: e.toString()
                }
            }

            override fun onFinished() {
                if (TreeUtil.findNodeWithObject(root, data) !== node) return
                node.childNodes().filter { it.userObject is MessageData }.forEach { node.remove(it) }
                if (error != null) {
                    data.loaded = false
                    node.insert(DefaultMutableTreeNode(MessageData(error!!, isError = true)), 0)
                } else {
                    children.forEach { node.add(it) }
                    node.sortChildren()
                    if (node.childCount == 0) node.add(DefaultMutableTreeNode(MessageData("None")))
                }
                model.nodeStructureChanged(node)
            }
        }.queue()
    }

    private fun openSelection(): Boolean {
        val data = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? ItemData ?: return false
        project.service<IscEditorService>().open(data.item)
        return true
    }

    private fun tenants() = service<IscSettings>().tenants

    private fun selectedTenant(): IscTenant? =
        generateSequence(tree.lastSelectedPathComponent as? DefaultMutableTreeNode) { it.parent as? DefaultMutableTreeNode }
            .mapNotNull { it.userObject as? TenantData }
            .firstOrNull()?.tenant

    private fun selectedItem(): IscItem? =
        ((tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? ItemData)?.item

    /** Deletes the selected object in ISC after confirming; the tree reloads when ISC confirms. */
    private fun deleteSelection() {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
        val item = selectedItem()?.takeIf { it.kind.deletable } ?: return
        val type = entitlementType(node)
        if (item.kind == ResourceKind.SOURCE_SCHEMAS && type == null) return
        val where = item.parent?.let { " from ${it.kind.singularName} '${it.name}'" }.orEmpty()
        val question = when {
            type != null -> "Delete entitlement type '$type' (its schema)$where? This can't be undone."
            item.kind.singleton -> "Remove the ${item.kind.singularName}$where? Its settings are deleted in ISC."
            else -> "Delete ${item.kind.singularName} '${item.name}'$where? This can't be undone."
        }
        if (Messages.showYesNoDialog(project, question, "Delete from SailPoint ISC", Messages.getWarningIcon()) != Messages.YES) return
        project.service<IscEditorService>().delete(item)
    }

    /** The entitlement type a schema node belongs to (its folder under Entitlements), or null for any other node. */
    private fun entitlementType(node: DefaultMutableTreeNode?): String? {
        if ((node?.userObject as? ItemData)?.item?.kind != ResourceKind.SOURCE_SCHEMAS) return null
        val typeFolder = node.parent as? DefaultMutableTreeNode ?: return null
        val entitlements = (typeFolder.parent as? DefaultMutableTreeNode)?.userObject as? GroupData
        return (typeFolder.userObject as? GroupData)?.name?.takeIf { entitlements?.content == FolderContent.ENTITLEMENTS }
    }

    /** Offers a new entitlement type (schema) on the source whose Entitlements folder is selected. */
    private fun newEntitlementType() {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val data = node.userObject as? GroupData ?: return
        val existing = node.childNodes().mapNotNull { (it.userObject as? GroupData)?.name }.toSet()
        NewEntitlementTypeDialog(project, data.parent, existing).showAndCreate()
    }

    private fun selectedCategory(): CategoryData? =
        (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? CategoryData

    private fun selectedFolder(): GroupData? =
        (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? GroupData

    /** Creates the aggregation schedule this folder covers, unless the source already has it. */
    private fun newSchedule() {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val data = node.userObject as? GroupData ?: return
        val type = when (data.content ?: return) {
            FolderContent.ACCOUNTS -> ACCOUNT_AGGREGATION
            FolderContent.ENTITLEMENTS -> GROUP_AGGREGATION
        }
        val existing = node.childNodes().any { (it.userObject as? ItemData)?.item?.kind == ResourceKind.SOURCE_SCHEDULES }
        if (existing) {
            Messages.showInfoMessage(
                project, "'${data.parent.name}' already has a ${data.name.lowercase()} aggregation schedule.", "New Aggregation Schedule",
            )
            return
        }
        NewScheduleDialog(project, data.parent, listOf(type)).showAndCreate()
    }

    private fun addTenant() {
        val dialog = TenantDialog(this, null, null)
        if (dialog.showAndGet()) service<IscSettings>().addOrUpdateTenant(dialog.result, dialog.resultSecret)
    }

    private fun editTenant() {
        val tenant = selectedTenant() ?: return
        val settings = service<IscSettings>()
        val dialog = TenantDialog(this, tenant, settings.loadSecret(tenant.id))
        if (dialog.showAndGet()) settings.addOrUpdateTenant(dialog.result, dialog.resultSecret)
    }

    private fun removeTenant() {
        val tenant = selectedTenant() ?: return
        if (Messages.showYesNoDialog(
                project, "Remove tenant '${tenant.name}' and its stored credentials?", "Remove SailPoint Tenant", Messages.getQuestionIcon(),
            ) == Messages.YES
        ) {
            service<IscSettings>().removeTenant(tenant.id)
        }
    }

    private fun runner() = project.service<SourceRunner>()

    /** A Run action on the selected source; [csvOnly] ones only show for delimited file (CSV) sources. */
    private fun sourceAction(text: String, csvOnly: Boolean = false, perform: (IscItem) -> Unit) = object : DumbAwareAction(text) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val source = selectedItem()?.takeIf { it.kind == ResourceKind.SOURCES }
            e.presentation.isEnabledAndVisible = source != null && (!csvOnly || source.detail == DELIMITED_FILE)
        }

        override fun actionPerformed(e: AnActionEvent) {
            selectedItem()?.takeIf { it.kind == ResourceKind.SOURCES }?.let(perform)
        }
    }

    private fun chooseCsv(title: String): Path? {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("csv").withTitle(title)
        return FileChooser.chooseFile(descriptor, project, null)?.toNioPath()
    }

    private fun action(text: String, icon: Icon, enabled: () -> Boolean, perform: () -> Unit) = object : DumbAwareAction(text, null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled()
        }

        override fun actionPerformed(e: AnActionEvent) = perform()
    }

    private inner class ContextMenu : ActionGroup(), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun getChildren(e: AnActionEvent?): Array<AnAction> {
            val specific = nodeActions(tree.lastSelectedPathComponent as? DefaultMutableTreeNode)
            val separator = if (specific.isEmpty()) emptyList() else listOf(Separator.getInstance())
            return (commonActions + separator + specific).toTypedArray()
        }
    }

    override fun dispose() = Unit

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            when (val data = (value as? DefaultMutableTreeNode)?.userObject) {
                is TenantData -> {
                    icon = AllIcons.Nodes.WebFolder
                    append(data.tenant.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ${data.tenant.baseUrl.removePrefix("https://")}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is CategoryData -> {
                    icon = data.kind.icon()
                    append(data.kind.displayName)
                    data.count?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                }
                is ItemData -> {
                    icon = data.item.kind.icon()
                    append(data.item.name)
                    data.item.detail?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                }
                is GroupData -> {
                    icon = AllIcons.Nodes.Folder
                    append(data.name)
                }
                is MessageData -> {
                    if (data.isError) icon = AllIcons.General.Warning
                    append(data.text, if (data.isError) SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }
            }
        }

        private fun ResourceKind.icon(): Icon = when (this) {
            ResourceKind.SOURCES -> AllIcons.Nodes.DataTables
            ResourceKind.TRANSFORMS -> AllIcons.Nodes.Function
            ResourceKind.CONNECTOR_RULES -> AllIcons.Nodes.Method
            ResourceKind.IDENTITIES -> AllIcons.General.User
            ResourceKind.SOURCE_SCHEMAS -> AllIcons.Nodes.DataSchema
            ResourceKind.SOURCE_PROVISIONING_POLICIES -> AllIcons.Nodes.Template
            ResourceKind.SOURCE_SCHEDULES -> AllIcons.Actions.Execute
            ResourceKind.SOURCE_CORRELATION -> AllIcons.Nodes.Related
            ResourceKind.SOURCE_ACCOUNT_DELETE_APPROVAL, ResourceKind.SOURCE_MACHINE_ACCOUNT_DELETE_APPROVAL ->
                AllIcons.Actions.Checked
            ResourceKind.SOURCE_ATTRIBUTE_SYNC -> AllIcons.Actions.Refresh
            ResourceKind.SOURCE_NATIVE_CHANGE_DETECTION -> AllIcons.Actions.Preview
        }
    }
}
