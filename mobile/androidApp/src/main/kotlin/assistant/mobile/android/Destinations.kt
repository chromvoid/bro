package assistant.mobile.android

import androidx.navigation3.runtime.NavKey
import assistant.mobile.design.R as DesignR
import assistant.mobile.core.notifications.NotificationDestination
import assistant.mobile.core.tasks.TaskState
import kotlinx.serialization.Serializable

/** UI route keys are Android-owned; the common core has no navigation or Compose dependency. */
@Serializable
sealed interface Destination : NavKey {
    @Serializable
    data object Glasses : Destination

    @Serializable
    data class Section(val screen: FamilyScreen) : Destination

    /** A user-initiated composer action; recovery carries only the durable journal key. */
    @Serializable
    data class AssistantEntry(val recoveryKey: String? = null) : Destination

    /** The card's account-local date survives navigation independently of the device clock. */
    @Serializable
    data class FoodDay(val date: String? = null) : Destination

    /** Personal and shared lists use the same task workspace. */
    @Serializable
    data class TaskList(val id: String) : Destination

    @Serializable
    data class TaskNew(val listId: String? = null, val initialState: TaskState = TaskState.Todo) : Destination

    @Serializable
    data class SharedProjection(val id: String, val day: String? = null) : Destination

    @Serializable
    data class Timer(val id: String) : Destination

    @Serializable
    data class Reminder(val id: String) : Destination

    @Serializable
    data class PersonalEntry(val openWeight: Boolean = false, val recoveryKey: String? = null) : Destination

    @Serializable
    data class PersonalHistory(val metric: String? = null, val resultId: String? = null,
        val period: Int? = null) : Destination

    @Serializable
    data class Plugin(val installation: String, val screen: String = "home", val coin: String? = null) : Destination

    @Serializable
    data class ActivityEntry(val newSession: Boolean = false, val recoveryKey: String? = null) : Destination

    /** IDs only become reachable after their domain has data; no invented records in the scaffold. */
    @Serializable
    data class Detail(val screen: FamilyDetail, val id: String, val parentId: String? = null,
        val taskTab: String = "details") : Destination
}

@Serializable
enum class FamilyScreen(val title: Int) {
    Today(DesignR.string.nav_today),
    Assistant(DesignR.string.nav_assistant),
    Food(DesignR.string.nav_food),
    FoodStats(DesignR.string.stats_title),
    Tasks(DesignR.string.nav_tasks),
    Timers(DesignR.string.nav_timers),
    Notifications(DesignR.string.notifications_title),
    Circle(DesignR.string.nav_circle),
    Friends(DesignR.string.circle_friends),
    Shared(DesignR.string.shared_title),
    Spaces(DesignR.string.circle_spaces),
    SharedLists(DesignR.string.sharing_lists),
    Challenges(DesignR.string.circle_challenges),
    Activity(DesignR.string.nav_activity),
    Places(DesignR.string.nav_places),
    Personal(DesignR.string.nav_personal),
    BodyAtlas(DesignR.string.personal_atlas_title),
    Profile(DesignR.string.personal_profile),
    PersonalHistory(DesignR.string.personal_history),
    Sources(DesignR.string.sources_title),
    Goals(DesignR.string.sources_goals),
    Recordings(DesignR.string.nav_recordings),
    Settings(DesignR.string.nav_settings),
    SpeechSettings(DesignR.string.speech_settings_title),
    SharingSettings(DesignR.string.settings_sharing),
    Devices(DesignR.string.settings_devices),
    NotificationSettings(DesignR.string.notification_settings_title),
    LocationSettings(DesignR.string.location_settings_title),
    Capabilities(DesignR.string.settings_capabilities),
    Login(DesignR.string.auth_title),
    Welcome(DesignR.string.auth_invite_title),
    NoAccess(DesignR.string.state_no_access_title),
    UnknownLink(DesignR.string.app_not_found),
    AccountDeletion(DesignR.string.account_deletion_title),
}

/** Every ID-bearing family route in plan §2.1 has a typed placeholder destination. */
@Serializable
enum class FamilyDetail(val title: Int) {
    AssistantMessage(DesignR.string.nav_assistant),
    Task(DesignR.string.nav_tasks),
    Channel(DesignR.string.nav_circle),
    Space(DesignR.string.circle_spaces),
    SpaceMembers(DesignR.string.circle_members),
    SpaceChallenges(DesignR.string.circle_challenges),
    SharedList(DesignR.string.sharing_lists),
    ListAccess(DesignR.string.circle_list_access),
    Challenge(DesignR.string.circle_challenges),
    ActivitySession(DesignR.string.nav_activity),
    PlacesDay(DesignR.string.nav_places),
    PersonalSource(DesignR.string.sources_title),
    PersonalSourceRow(DesignR.string.sources_title),
    Device(DesignR.string.settings_devices),
    PluginScreen(DesignR.string.plugins_title),
}

/** Typed notification targets enter the same private navigation tree as in-app actions. */
internal fun NotificationDestination.nativeDestination(): Destination = when (this) {
    is NotificationDestination.Task -> Destination.Detail(FamilyDetail.Task, id)
    is NotificationDestination.Timer -> id?.let { Destination.Timer(it) } ?: Destination.Section(FamilyScreen.Timers)
    is NotificationDestination.Reminder -> id?.let { Destination.Reminder(it) } ?: Destination.Section(FamilyScreen.Timers)
    is NotificationDestination.Message -> Destination.Detail(FamilyDetail.AssistantMessage, id)
    NotificationDestination.Notifications -> Destination.Section(FamilyScreen.Notifications)
    is NotificationDestination.Screen -> {
        val direct = mapOf(
            "/today" to FamilyScreen.Today, "/assistant" to FamilyScreen.Assistant,
            "/tasks" to FamilyScreen.Tasks, "/food" to FamilyScreen.Food, "/food/stats" to FamilyScreen.FoodStats,
            "/personal" to FamilyScreen.Personal, "/personal/history" to FamilyScreen.PersonalHistory,
            "/personal/profile" to FamilyScreen.Profile, "/personal/goals" to FamilyScreen.Goals,
            "/personal/sources" to FamilyScreen.Sources, "/circle" to FamilyScreen.Circle,
            "/activity" to FamilyScreen.Activity, "/places" to FamilyScreen.Places,
            "/settings" to FamilyScreen.Settings, "/settings/notifications" to FamilyScreen.NotificationSettings,
            "/settings/devices" to FamilyScreen.Devices,
            "/settings/sharing" to FamilyScreen.SharingSettings, "/settings/access" to FamilyScreen.SharingSettings,
            "/settings/shared" to FamilyScreen.Shared, "/circle/shared" to FamilyScreen.Shared,
        )[path]
        if (direct != null) Destination.Section(direct)
        else {
            val parts = path.removePrefix("/").split('/')
            when {
                parts.size == 3 && parts[0] == "circle" && parts[1] == "lists" -> Destination.TaskList(parts[2])
                parts.size == 3 && parts[0] == "circle" && parts[1] == "channels" -> Destination.Detail(FamilyDetail.Channel, parts[2])
                parts.size == 3 && parts[0] == "circle" && parts[1] == "spaces" -> Destination.Detail(FamilyDetail.Space, parts[2])
                else -> circleDestination(parts) ?: Destination.Section(FamilyScreen.UnknownLink)
            }
        }
    }
}

fun FamilyScreen.children(): List<FamilyScreen> = when (this) {
    FamilyScreen.Food -> listOf(FamilyScreen.FoodStats)
    FamilyScreen.Circle -> listOf(FamilyScreen.Friends)
    FamilyScreen.Personal -> listOf(
        FamilyScreen.Profile, FamilyScreen.PersonalHistory, FamilyScreen.Sources,
        FamilyScreen.Goals, FamilyScreen.BodyAtlas,
    )
    FamilyScreen.Settings -> listOf(
        FamilyScreen.SpeechSettings, FamilyScreen.SharingSettings, FamilyScreen.Devices,
        FamilyScreen.NotificationSettings, FamilyScreen.LocationSettings, FamilyScreen.Capabilities,
    )
    else -> emptyList()
}
