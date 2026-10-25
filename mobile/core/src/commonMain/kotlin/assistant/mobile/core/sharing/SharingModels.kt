package assistant.mobile.core.sharing

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.circle.*
import assistant.mobile.core.food.FoodDay
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.tasks.TaskList
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SharingPermission(val wire: String) {
    @SerialName("summary") Summary("summary"), @SerialName("detail") Detail("detail");
}
@Serializable
data class SharingSection(val id: String, val name: String, @SerialName("owner_id") val ownerId: String, val revision: Long)
@Serializable
data class SharingGrant(@SerialName("resource_id") val resourceId: String, @SerialName("user_id") val userId: String,
    val permission: SharingPermission, val name: String, val login: String? = null)
@Serializable
data class OpenedSection(val id: String, val name: String, @SerialName("owner_id") val ownerId: String,
    val permission: String, @SerialName("owner_name") val ownerName: String, @SerialName("owner_login") val ownerLogin: String? = null)
@Serializable
data class SharingOverview(val sections: List<SharingSection>, val grants: List<SharingGrant>, val shared: List<OpenedSection>? = null)
@Serializable
enum class ListPermission(val wire: String) {
    @SerialName("view") View("view"), @SerialName("edit") Edit("edit"), @SerialName("manage") Manage("manage");
}
@Serializable
data class ListAccessResource(val id: String, val name: String, @SerialName("member_access") val memberAccess: String,
    val revision: Long, @SerialName("space_id") val spaceId: String? = null)
@Serializable
data class ListGrant(@SerialName("user_id") val userId: String, val permission: ListPermission, val name: String, val login: String? = null)
@Serializable
data class ListGrants(val resource: ListAccessResource, val grants: List<ListGrant>)

data class SharedNutritionSummary(val value: String?, val target: String?, val remaining: String?)
data class SharedMetric(val kind: String, val status: String, val value: String?)
data class SharedProfile(val heightCm: String?, val birthDate: String?)
/** Deliberately keeps only fields used by the web projection. Hidden source records never enter UI state. */
data class SharedProjection(val section: String, val permission: String, val date: String?,
    val summary: SharedNutritionSummary? = null, val diary: FoodDay? = null,
    val metrics: List<SharedMetric> = emptyList(), val profile: SharedProfile? = null)

enum class SharingSurface { Settings, Shared, ListGrants }
data class SharingState(val namespace: AccountNamespace, val surface: SharingSurface = SharingSurface.Settings,
    val sharedId: String? = null, val listId: String? = null, val day: String? = null,
    val overview: CircleQuery<SharingOverview> = CircleQuery(), val projection: CircleQuery<SharedProjection> = CircleQuery(),
    val spaces: CircleQuery<CircleSpaces> = CircleQuery(), val lists: CircleQuery<List<TaskList>> = CircleQuery(),
    val relationships: CircleQuery<List<CircleRelationship>> = CircleQuery(), val grants: CircleQuery<ListGrants> = CircleQuery(),
    val members: CircleQuery<List<CircleMember>> = CircleQuery(), val person: CirclePerson? = null,
    val scopes: Set<String> = emptySet(), val locale: String = "ru", val today: String = "", val gone: Boolean = false,
    val busy: Boolean = false, val searching: Boolean = false, val error: assistant.mobile.core.LocalizedString? = null,
    val serverError: String? = null, val pending: List<Submission> = emptyList()) {
    val opened get() = overview.data?.shared.orEmpty().filter { sharingReadScope(it.name) in scopes }
    val selected get() = opened.firstOrNull { it.id == sharedId }
    val list get() = lists.data.orEmpty().firstOrNull { it.id == listId }
    val grantable get() = members.data.orEmpty().filter { it.role == CircleRole.Member }
    val activeSpaces get() = spaces.data?.spaces.orEmpty().filter { it.archived != true }
    val sharedLists get() = lists.data.orEmpty().filter { it.spaceId != null }
    val people get() = relationshipGroups(relationships.data.orEmpty(), namespace.userId)
}

fun sharingReadScope(section: String) = if (section == "nutrition") "nutrition.read" else "personal.read"
fun sharingGrantsOf(overview: SharingOverview, sectionId: String) = overview.grants.filter { it.resourceId == sectionId }.sortedBy { it.name }
