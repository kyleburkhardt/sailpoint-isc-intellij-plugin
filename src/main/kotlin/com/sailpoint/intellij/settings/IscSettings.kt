package com.sailpoint.intellij.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic
import com.sailpoint.intellij.api.IscApiException
import com.sailpoint.intellij.api.IscConnection
import java.util.UUID

/** One configured ISC tenant. [id] is a stable local key; the others are user-editable. */
data class IscTenant(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val tenant: String,
    val clientId: String,
) {
    val baseUrl: String get() = IscConnection.normalizeBaseUrl(tenant)
}

fun interface IscSettingsListener {
    fun tenantsChanged()

    companion object {
        @Topic.AppLevel
        val TOPIC = Topic(IscSettingsListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

/** Tenants are stored in `sailpoint-isc.xml`; each tenant's PAT secret lives in the IDE Password Safe. */
@Service(Service.Level.APP)
@State(name = "SailPointIscSettings", storages = [Storage("sailpoint-isc.xml")])
class IscSettings : SimplePersistentStateComponent<IscSettings.SettingsState>(SettingsState()) {
    class TenantState : BaseState() {
        var id by string()
        var name by string()
        var tenant by string()
        var clientId by string()
    }

    class SettingsState : BaseState() {
        var tenants by list<TenantState>()
    }

    val tenants: List<IscTenant>
        get() = state.tenants.map {
            IscTenant(it.id.orEmpty(), it.name.orEmpty(), it.tenant.orEmpty(), it.clientId.orEmpty())
        }

    fun findTenant(id: String): IscTenant? = tenants.find { it.id == id }

    /** Replaces the tenant list, forgetting secrets of removed tenants. */
    fun setTenants(newTenants: List<IscTenant>) {
        val removed = tenants.map { it.id }.toSet() - newTenants.map { it.id }.toSet()
        removed.forEach { saveSecret(it, "") }
        state.tenants = newTenants.mapTo(mutableListOf()) { t ->
            TenantState().apply {
                id = t.id
                name = t.name.trim()
                tenant = t.tenant.trim()
                clientId = t.clientId.trim()
            }
        }
        ApplicationManager.getApplication().messageBus.syncPublisher(IscSettingsListener.TOPIC).tenantsChanged()
    }

    fun addOrUpdateTenant(tenant: IscTenant, secret: String?) {
        if (secret != null) saveSecret(tenant.id, secret)
        val list = tenants.toMutableList()
        val index = list.indexOfFirst { it.id == tenant.id }
        if (index >= 0) list[index] = tenant else list += tenant
        setTenants(list)
    }

    fun removeTenant(id: String) = setTenants(tenants.filterNot { it.id == id })

    fun loadSecret(tenantId: String): String = PasswordSafe.instance.getPassword(credentialAttributes(tenantId)).orEmpty()

    fun saveSecret(tenantId: String, secret: String) {
        PasswordSafe.instance.set(credentialAttributes(tenantId), secret.takeIf { it.isNotEmpty() }?.let { Credentials(tenantId, it) })
    }

    /** Throws [IscApiException] if the tenant is gone or incompletely configured. */
    fun connection(tenantId: String): IscConnection {
        val tenant = findTenant(tenantId) ?: throw IscApiException(0, "The SailPoint tenant was removed from settings.")
        val secret = loadSecret(tenantId)
        if (tenant.baseUrl.isEmpty() || tenant.clientId.isBlank() || secret.isEmpty()) {
            throw IscApiException(0, "Tenant '${tenant.name}' is missing its URL, client ID or client secret.")
        }
        return IscConnection(tenant.baseUrl, tenant.clientId, secret)
    }

    private fun credentialAttributes(tenantId: String) =
        CredentialAttributes(generateServiceName("SailPoint ISC", tenantId))
}
