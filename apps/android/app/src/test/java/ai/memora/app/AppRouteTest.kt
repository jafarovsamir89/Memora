package ai.memora.app

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class AppRouteTest {
    @Test fun actionTargetRouteRoundTripsExactStableIdAndTabAnchor() {
        val id = "d6fd955d-3c06-4e3a-9f6a-2ab741c83d42"
        val route = AppTargetKind.fromAction("case", id)

        assertEquals(AppRoute(AppDestination.ACTION_TARGET, AppTargetKind.CASE, id), route)
        assertEquals(AppDestination.CASES, route?.selectedPrimaryTab)
        assertEquals(route, AppRoute.decode(requireNotNull(route).encode()))
        assertEquals(route, AppRoute.fromLegacyRoute(requireNotNull(route).encode()))
    }

    @Test fun invalidOrUnknownActionTargetsNeverCreateGenericObjectRoutes() {
        assertNull(AppTargetKind.fromAction("case", "not-an-id"))
        assertNull(AppTargetKind.fromAction("unknown", UUID.randomUUID().toString()))
        assertNull(AppTargetKind.fromAction("case", null))
    }

    @Test fun backRestoresExactPreviousRouteAndRetainsEachScreenStateKey() {
        val first = AppRoute.screen(AppDestination.SECRETARY)
        val caseId = "5e33a6d4-7f73-4e40-a926-8edfb0146325"
        val target = requireNotNull(AppTargetKind.fromAction("case", caseId))
        val state = AppNavigationState.start(first).navigate(AppRoute.screen(AppDestination.SETTINGS)).navigate(target)

        assertEquals(target, state.current)
        assertEquals(AppDestination.CASES, state.current.selectedPrimaryTab)
        assertEquals(AppRoute.screen(AppDestination.SETTINGS), state.back().current)
        assertEquals(AppRoute.screen(AppDestination.SECRETARY), state.back().back().current)
        assertEquals(first, AppRoute.decode(first.stateKey))
        assertEquals(target.stateKey, AppRoute.decode(target.stateKey)?.stateKey)
    }

    @Test fun selectingAnotherPrimaryTabClearsNestedHistory() {
        val state = AppNavigationState.start(AppRoute.screen(AppDestination.MORE))
            .navigate(AppRoute.screen(AppDestination.FINANCE))
            .selectTab(AppDestination.MEMORY)

        assertEquals(AppRoute.screen(AppDestination.MEMORY), state.current)
        assertEquals(emptyList<AppRoute>(), state.backStack)
    }

    @Test fun personAndSourceRoutesRetainExactIdsAndReturnToTheConversation() {
        val conversation = AppRoute.screen(AppDestination.SECRETARY)
        val personId = UUID.randomUUID().toString()
        val sourceId = UUID.randomUUID().toString()
        val person = requireNotNull(AppRoute.target(AppTargetKind.PERSON, personId))
        val source = requireNotNull(AppRoute.source(sourceId))
        val state = AppNavigationState.start(conversation).navigate(
            requireNotNull(AppRoute.target(AppTargetKind.CASE, UUID.randomUUID().toString())),
        ).navigate(person).navigate(source)

        assertEquals(personId, AppRoute.decode(person.encode())?.targetId)
        assertEquals(sourceId, AppRoute.decode(source.encode())?.targetId)
        assertEquals(AppDestination.MORE, person.selectedPrimaryTab)
        assertEquals(AppDestination.CASES, source.selectedPrimaryTab)
        assertEquals(person, state.back().current)
        assertEquals(conversation, state.back().back().back().current)
    }

    @Test fun projectAndNoteRoutesKeepTypedIdsAndMoreTabAnchor() {
        val projectId = "429d696e-c2ae-4431-b19f-05d75a2b0f4e"
        val noteId = "a2aaf74c-102e-40d0-93c9-e8408f6290fd"
        val project = requireNotNull(AppRoute.target(AppTargetKind.PROJECT, projectId))
        val note = requireNotNull(AppRoute.target(AppTargetKind.NOTE, noteId))
        val navigation = AppNavigationState.start(AppRoute.screen(AppDestination.SECRETARY))
            .navigate(AppRoute.screen(AppDestination.PROJECTS)).navigate(project).navigate(note)

        assertEquals("target:project:$projectId", project.encode())
        assertEquals(project, AppRoute.decode(project.encode()))
        assertEquals("target:note:$noteId", note.encode())
        assertEquals(note, AppRoute.decode(note.encode()))
        assertEquals(AppDestination.MORE, project.selectedPrimaryTab)
        assertEquals(project, navigation.back().current)
        assertEquals(AppRoute.screen(AppDestination.PROJECTS), navigation.back().back().current)
    }

    @Test fun navigationStateSaverRestoresCurrentRouteAndBackStack() {
        val target = requireNotNull(AppTargetKind.fromAction(
            "finance", "b911ad3b-491d-4ed0-8f9a-01b204e1b196",
        ))
        val original = AppNavigationState.start(AppRoute.screen(AppDestination.SECRETARY))
            .navigate(AppRoute.screen(AppDestination.MORE))
            .navigate(target)
        val saverScope = object : SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }
        val saved = with(saverScope) {
            with(AppNavigationState.Saver) { save(original) }
        }

        assertEquals(original, AppNavigationState.Saver.restore(requireNotNull(saved)))
    }
}
