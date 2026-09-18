package com.sailpoint.intellij.schema

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType
import com.sailpoint.intellij.api.ResourceKind
import com.sailpoint.intellij.editor.IscVirtualFile

/** Validation and completion for transforms opened from ISC, and for local `*.transform.json` files. */
class TransformSchemaProviderFactory : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(TransformSchemaProvider)

    private object TransformSchemaProvider : JsonSchemaFileProvider {
        override fun isAvailable(file: VirtualFile): Boolean =
            (file is IscVirtualFile && file.kind == ResourceKind.TRANSFORMS) || file.name.endsWith(".transform.json")

        override fun getName(): String = "SailPoint ISC Transform"

        override fun getSchemaFile(): VirtualFile? =
            JsonSchemaProviderFactory.getResourceFile(TransformSchemaProviderFactory::class.java, "/schemas/transform.schema.json")

        override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema
    }
}
