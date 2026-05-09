package com.streame.tv.data.remote.supabase

import android.util.Log
import com.streame.tv.BuildConfig
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AvatarRepository"

@Serializable
data class SupabaseAvatarCatalogItem(
    val id: String,
    val display_name: String,
    val storage_path: String,
    val category: String,
    val sort_order: Int,
    val bg_color: String? = null
)

data class AvatarCatalogItem(
    val id: String,
    val displayName: String,
    val imageUrl: String,
    val category: String,
    val sortOrder: Int,
    val bgColor: String? = null
)

@Singleton
class AvatarRepository @Inject constructor(
    private val postgrest: Postgrest
) {
    private var cachedCatalog: List<AvatarCatalogItem>? = null

    suspend fun getAvatarCatalog(): List<AvatarCatalogItem> {
        cachedCatalog?.let { return it }

        return try {
            val response = postgrest.rpc("get_avatar_catalog")
            val remote = response.decodeList<SupabaseAvatarCatalogItem>()
            val catalog = remote.map { item ->
                AvatarCatalogItem(
                    id = item.id,
                    displayName = item.display_name,
                    imageUrl = avatarImageUrl(item.storage_path),
                    category = item.category,
                    sortOrder = item.sort_order,
                    bgColor = item.bg_color
                )
            }
            cachedCatalog = catalog
            catalog
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get avatar catalog", e)
            emptyList()
        }
    }

    fun getAvatarImageUrl(avatarId: String, catalog: List<AvatarCatalogItem>): String? {
        return catalog.find { it.id == avatarId }?.imageUrl
    }

    fun invalidateCache() {
        cachedCatalog = null
    }

    companion object {
        fun avatarImageUrl(storagePath: String): String {
            val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
            return "$baseUrl/storage/v1/object/public/avatars/$storagePath"
        }
    }
}
