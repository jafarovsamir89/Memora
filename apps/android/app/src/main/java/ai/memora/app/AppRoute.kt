package ai.memora.app

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import java.util.UUID

/** Stable screen identifiers for the five primary destinations and their secondary sections. */
internal enum class AppDestination(val key: String) {
    TODAY("home"),
    SECRETARY("chat"),
    CASES("cases"),
    MEMORY("memory"),
    MORE("more"),
    TASKS("tasks"),
    VOICE_MEMORY("voice_memory"),
    NOTES("notes"),
    TABLES("tables"),
    PORTABLE_BACKUP("portable_backup"),
    LICENSES("licenses"),
    INBOX("inbox"),
    FINANCE("finance"),
    CALENDAR("calendar"),
    PEOPLE("people"),
    PROJECTS("projects"),
    SETTINGS("settings"),
    ACTION_TARGET("action_target");

    val isPrimaryTab: Boolean get() = this in PRIMARY_TABS

    fun primaryTab(): AppDestination = when (this) {
        TODAY, SECRETARY, CASES, MEMORY, MORE -> this
        else -> when (this) {
            ACTION_TARGET -> MORE // The target-specific route resolves its own anchor below.
            else -> MORE
        }
    }

    companion object {
        val PRIMARY_TABS = listOf(TODAY, SECRETARY, CASES, MEMORY, MORE)
        fun fromKey(key: String): AppDestination? = entries.firstOrNull { it.key == key }
        fun fromLegacyRoute(key: String): AppDestination = fromKey(key) ?: TODAY
    }
}

/** Only persisted, UUID-backed targets emitted by the agent action mapper can become object routes. */
internal enum class AppTargetKind(val key: String, val primaryTab: AppDestination) {
    MEMORY("memory", AppDestination.MEMORY),
    PERSON("person", AppDestination.MORE),
    FINANCE("finance", AppDestination.MORE),
    CASE("case", AppDestination.CASES),
    SOURCE("source", AppDestination.CASES),
    TASK("task", AppDestination.MORE),
    CALENDAR("calendar", AppDestination.MORE),
    PROJECT("project", AppDestination.MORE),
    NOTE("note", AppDestination.MORE),
    TABLE("table", AppDestination.MORE);

    companion object {
        fun fromKey(key: String): AppTargetKind? = entries.firstOrNull { it.key == key }
        fun fromAction(kind: String?, id: String?): AppRoute? {
            val targetKind = kind?.let(::fromKey) ?: return null
            val targetId = id?.takeIf { runCatching { UUID.fromString(it) }.isSuccess } ?: return null
            return AppRoute(AppDestination.ACTION_TARGET, targetKind, targetId)
        }
    }
}

/** A route carries the screen type and, when needed, an exact stable object ID. */
internal data class AppRoute(
    val destination: AppDestination,
    val targetKind: AppTargetKind? = null,
    val targetId: String? = null,
    val conversationTurnId: String? = null,
) {
    init {
        require((targetKind == null) == (targetId == null)) { "route_target_incomplete" }
        require((destination == AppDestination.ACTION_TARGET) == (targetKind != null)) { "route_target_destination_invalid" }
        if (targetId != null) require(runCatching { UUID.fromString(targetId) }.isSuccess) { "route_target_id_invalid" }
        require(conversationTurnId == null || destination == AppDestination.SECRETARY) {
            "conversation_route_destination_invalid"
        }
        if (conversationTurnId != null) require(runCatching { UUID.fromString(conversationTurnId) }.isSuccess) {
            "conversation_route_id_invalid"
        }
    }

    val stateKey: String get() = encode()
    val selectedPrimaryTab: AppDestination
        get() = targetKind?.primaryTab ?: destination.primaryTab()

    fun encode(): String = when {
        conversationTurnId != null -> "conversation:$conversationTurnId"
        targetKind != null -> "target:${targetKind.key}:${requireNotNull(targetId)}"
        else -> "screen:${destination.key}"
    }

    companion object {
        fun screen(destination: AppDestination) = AppRoute(destination)
        fun conversation(turnId: String): AppRoute? = runCatching {
            AppRoute(AppDestination.SECRETARY, conversationTurnId = turnId)
        }.getOrNull()
        fun target(kind: AppTargetKind, id: String): AppRoute? =
            runCatching { AppRoute(AppDestination.ACTION_TARGET, kind, id) }.getOrNull()
        fun source(evidenceId: String): AppRoute? = target(AppTargetKind.SOURCE, evidenceId)
        fun fromLegacyRoute(key: String) = decode(key) ?: screen(AppDestination.fromLegacyRoute(key))
        fun decode(value: String): AppRoute? {
            if (value.startsWith("conversation:")) {
                return conversation(value.removePrefix("conversation:"))
            }
            if (value.startsWith("screen:")) {
                val destination = AppDestination.fromKey(value.removePrefix("screen:")) ?: return null
                if (destination == AppDestination.ACTION_TARGET) return null
                return screen(destination)
            }
            if (value.startsWith("target:")) {
                val fields = value.split(':', limit = 3)
                if (fields.size != 3) return null
                val kind = AppTargetKind.fromKey(fields[1]) ?: return null
                return runCatching { AppRoute(AppDestination.ACTION_TARGET, kind, fields[2]) }.getOrNull()
            }
            return null
        }
    }
}

/** Immutable navigation reducer; its entire route history is saveable across recreation/process death. */
internal data class AppNavigationState(
    val current: AppRoute,
    val backStack: List<AppRoute> = emptyList(),
) {
    fun navigate(destination: AppRoute): AppNavigationState =
        if (destination == current) this else copy(current = destination, backStack = backStack + current)

    fun selectTab(destination: AppDestination): AppNavigationState {
        require(destination.isPrimaryTab) { "not_a_primary_tab" }
        return copy(current = AppRoute.screen(destination), backStack = emptyList())
    }

    fun back(): AppNavigationState = when {
        backStack.isNotEmpty() -> copy(current = backStack.last(), backStack = backStack.dropLast(1))
        current.destination != AppDestination.TODAY -> copy(current = AppRoute.screen(AppDestination.TODAY))
        else -> this
    }

    companion object {
        fun start(route: AppRoute) = AppNavigationState(route)

        val Saver: Saver<AppNavigationState, Any> = listSaver(
            save = { state -> (listOf(state.current.encode()) + state.backStack.map(AppRoute::encode)) },
            restore = { values ->
                val routes = values.mapNotNull(AppRoute::decode)
                routes.firstOrNull()?.let { AppNavigationState(it, routes.drop(1)) }
                    ?: AppNavigationState.start(AppRoute.screen(AppDestination.TODAY))
            },
        )
    }
}
