package dev.eno2.openrgbremote

import androidx.lifecycle.SavedStateHandle
import dev.eno2.openrgbremote.core.data.connection.ConnectionKeepAlive
import dev.eno2.openrgbremote.core.domain.model.ConnectionStatus
import dev.eno2.openrgbremote.core.domain.model.EffectConfig
import dev.eno2.openrgbremote.core.domain.model.Profile
import dev.eno2.openrgbremote.core.domain.model.ProfileAnimation
import dev.eno2.openrgbremote.core.domain.model.EffectState
import dev.eno2.openrgbremote.core.domain.model.QuickAction
import dev.eno2.openrgbremote.core.domain.model.Schedule
import dev.eno2.openrgbremote.core.domain.model.Server
import dev.eno2.openrgbremote.core.domain.testing.FakeConnectionRepository
import dev.eno2.openrgbremote.core.domain.testing.FakeEffectController
import dev.eno2.openrgbremote.core.domain.testing.FakeLightingRepository
import dev.eno2.openrgbremote.core.domain.testing.FakeProfileRepository
import dev.eno2.openrgbremote.core.domain.testing.FakeQuickActionRepository
import dev.eno2.openrgbremote.core.domain.testing.FakeScheduleRepository
import dev.eno2.openrgbremote.core.domain.testing.FakeServerRepository
import dev.eno2.openrgbremote.core.domain.usecase.ApplyProfile
import dev.eno2.openrgbremote.core.domain.usecase.GetProfile
import dev.eno2.openrgbremote.core.domain.usecase.ObserveProfiles
import dev.eno2.openrgbremote.core.domain.usecase.ObserveServerOverviews
import dev.eno2.openrgbremote.core.effects.EffectId
import dev.eno2.openrgbremote.feature.quick.QuickActionsViewModel
import dev.eno2.openrgbremote.feature.quick.ScheduleEditViewModel
import dev.eno2.openrgbremote.quick.QuickActionResult
import dev.eno2.openrgbremote.quick.QuickActionRunner
import dev.eno2.openrgbremote.sdk.testing.RealDevices
import dev.eno2.openrgbremote.core.domain.repository.EffectLauncher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.Closeable
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class QuickActionTest {
    @get:Rule val main = MainDispatcherRule()

    private val connections = FakeConnectionRepository().apply {
        devices.value = mapOf(1L to RealDevices.all().take(3))
    }
    private val overviews = ObserveServerOverviews(FakeServerRepository(listOf(Server(1, "PC", "h"))), connections)
    private val lighting = FakeLightingRepository()
    private val effects = FakeEffectController()
    private val profiles = FakeProfileRepository(listOf(Profile(1, "Desk", listOf(0xFF0000, 0x0000FF))))

    private var acquired = 0
    private var released = 0
    private val keepAlive = ConnectionKeepAlive {
        acquired++
        Closeable { released++ }
    }
    private var refuseForeground = false
    private val launcher = object : EffectLauncher {
        override fun start(config: EffectConfig) {
            effects.start(config)
            check(!refuseForeground) { "ForegroundServiceStartNotAllowedException" }
        }
        override fun stop() = effects.stop()
    }
    private val runner = QuickActionRunner(connections, keepAlive, GetProfile(profiles), ApplyProfile(overviews, lighting, launcher), effects, launcher)

    private fun connected() {
        connections.statuses.value = mapOf(1L to ConnectionStatus.Connected(6, null, null))
    }

    @Test
    fun `profile is applied while the connections are kept alive`() = runTest {
        connected()
        assertEquals(QuickActionResult.Applied(3), runner.run(QuickAction.ApplyProfile(1)))
        assertEquals(3, lighting.calls.size)
        assertEquals(1 to 1, acquired to released)
    }

    @Test
    fun `waits for connecting servers before applying`() = runTest {
        connections.statuses.value = mapOf(1L to ConnectionStatus.Offline)
        val result = async { runner.run(QuickAction.Off) }
        advanceTimeBy(500)
        connections.statuses.value = mapOf(1L to ConnectionStatus.Connecting(1))
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(lighting.calls.isEmpty(), "nothing is sent while connecting")
        connected()
        assertEquals(QuickActionResult.Applied(3), result.await())
        assertTrue(lighting.frames.values.all { frame -> frame.all { it == 0 } }, "Off paints everything black")
    }

    @Test
    fun `unreachable servers and deleted profiles are reported`() = runTest {
        connections.statuses.value = mapOf(1L to ConnectionStatus.Offline)
        assertEquals(QuickActionResult.NoDevices, runner.run(QuickAction.Off))
        connected()
        assertEquals(QuickActionResult.Missing, runner.run(QuickAction.ApplyProfile(42)))
        assertTrue(lighting.calls.isEmpty())
        assertEquals(2 to 2, acquired to released)
    }

    @Test
    fun `effects start, toggle off and fall back to the app in the background`() = runTest {
        connected()
        assertEquals(QuickActionResult.EffectStarted, runner.run(QuickAction.StartEffect(EffectId.FIRE), toggle = true))
        assertEquals(QuickActionResult.EffectStopped, runner.run(QuickAction.StartEffect(EffectId.FIRE), toggle = true))
        assertEquals(1, acquired, "stopping needs no connection")

        refuseForeground = true
        assertEquals(QuickActionResult.NeedsForeground, runner.run(QuickAction.StartEffect(EffectId.AURORA)))
        assertEquals(listOf("start FIRE speed=1.0 targets=null", "stop", "start AURORA speed=1.0 targets=null", "stop"), effects.calls)
    }

    @Test
    fun `dynamic profile from a tile falls back to the app in the background`() = runTest {
        connected()
        profiles.save(Profile(name = "Aurora", colors = listOf(0x00FF7F), animation = ProfileAnimation(EffectId.AURORA)))
        val id = profiles.profiles.value.single { it.name == "Aurora" }.id
        assertEquals(QuickActionResult.Applied(3), runner.run(QuickAction.ApplyProfile(id)))
        assertEquals(EffectId.AURORA, (effects.state.value as EffectState.Running).config.effect)

        assertEquals(QuickActionResult.EffectStopped, runner.run(QuickAction.ApplyProfile(id), toggle = true), "a tile tap on the running profile stops it")
        assertEquals(EffectState.Idle, effects.state.value)

        refuseForeground = true
        assertEquals(QuickActionResult.NeedsForeground, runner.run(QuickAction.ApplyProfile(id)))
        assertEquals(EffectState.Idle, effects.state.value, "the engine must not run without its service")
    }

    @Test
    fun `cancelling a run still releases the keep-alive`() = runTest {
        connected()
        val job = launch { runner.run(QuickAction.Off) }
        advanceTimeBy(500) // inside the flush delay
        job.cancel()
        runCurrent()
        assertEquals(1 to 1, acquired to released)
    }

    @Test
    fun `favorites view model stores slots and lists custom profiles first`() = runTest {
        val repo = FakeQuickActionRepository()
        val vm = QuickActionsViewModel(repo, ObserveProfiles(profiles))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertEquals("Desk", vm.state.value.profiles.first().name)
        assertEquals(9, vm.state.value.profiles.size)

        vm.setSlot(1, QuickAction.StartEffect(EffectId.AURORA))
        vm.setSlot(3, QuickAction.Off)
        assertEquals(listOf(null, QuickAction.StartEffect(EffectId.AURORA), null, QuickAction.Off), vm.state.value.slots)
        vm.setSlot(3, null)
        assertEquals(null, repo.slots.value[3])
    }

    private fun TestScope.editViewModel(repo: FakeScheduleRepository, id: Long, cancelled: MutableList<Long>): ScheduleEditViewModel {
        val vm = ScheduleEditViewModel(SavedStateHandle(mapOf("scheduleId" to id)), repo, { cancelled += it }, ObserveProfiles(profiles))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm
    }

    @Test
    fun `schedule editor validates days, rejects effects and saves`() = runTest {
        val repo = FakeScheduleRepository()
        val vm = editViewModel(repo, 0, mutableListOf())
        assertTrue(vm.state.value.isNew)

        vm.setTime(7, 30)
        vm.setAction(QuickAction.StartEffect(EffectId.FIRE))
        assertEquals(QuickAction.Off, vm.state.value.schedule.action, "schedules cannot start effects")
        vm.setAction(QuickAction.ApplyProfile(1))
        DayOfWeek.entries.forEach(vm::toggleDay)
        vm.save()
        assertTrue(vm.state.value.daysError)
        assertTrue(repo.schedules.value.isEmpty())

        vm.toggleDay(DayOfWeek.SATURDAY)
        assertFalse(vm.state.value.daysError)
        vm.save()
        assertTrue(vm.state.value.done)
        val saved = repo.schedules.value.single()
        assertEquals(LocalTime.of(7, 30), saved.time)
        assertEquals(setOf(DayOfWeek.SATURDAY), saved.days)
        assertEquals(QuickAction.ApplyProfile(1), saved.action)
    }

    @Test
    fun `existing schedule loads and delete cancels its alarm`() = runTest {
        val repo = FakeScheduleRepository(listOf(Schedule(5, LocalTime.of(22, 15), setOf(DayOfWeek.MONDAY), QuickAction.Off, enabled = false)))
        val cancelled = mutableListOf<Long>()
        val vm = editViewModel(repo, 5, cancelled)
        assertFalse(vm.state.value.isNew)
        assertEquals(LocalTime.of(22, 15), vm.state.value.schedule.time)
        assertFalse(vm.state.value.schedule.enabled)

        vm.delete()
        assertEquals(listOf(5L), cancelled)
        assertTrue(repo.schedules.value.isEmpty())
        assertTrue(vm.state.value.done)
    }
}
