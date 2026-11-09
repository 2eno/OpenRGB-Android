package io.github.openrgbremote.core.domain

import io.github.openrgbremote.core.domain.testing.FakeConnectionRepository
import io.github.openrgbremote.core.domain.testing.FakeDiscoveryRepository
import io.github.openrgbremote.core.domain.testing.FakeServerRepository

import io.github.openrgbremote.core.domain.usecase.HostError
import io.github.openrgbremote.core.domain.usecase.PortError
import io.github.openrgbremote.core.domain.usecase.ServerInput
import io.github.openrgbremote.core.domain.usecase.ServerInputValidation
import io.github.openrgbremote.core.domain.usecase.ValidateServerInput
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ValidateServerInputTest {
    private val validate = ValidateServerInput()

    @ParameterizedTest
    @ValueSource(strings = ["192.168.1.20", "gaming-pc", "Gaming-PC.local", "pc.fritz.box", "fe80::1", "[fd00::abcd]", "::1", "a.b.c.d.example."])
    fun `accepts addresses people actually type`(host: String) {
        assertIs<ServerInputValidation.Valid>(validate("", host, "6742"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["192.168.1", "999.1.1.1", "-pc", "pc_", "http://pc", "pc:6742", "a..b"])
    fun `rejects malformed hosts`(host: String) {
        val result = assertIs<ServerInputValidation.Invalid>(validate("", host, "6742"))
        assertEquals(HostError.INVALID, result.hostError)
    }

    @Test
    fun `reports empty host and spaces`() {
        assertEquals(HostError.EMPTY, (validate("", "  ", "6742") as ServerInputValidation.Invalid).hostError)
        assertEquals(HostError.CONTAINS_SPACES, (validate("", "my pc", "6742") as ServerInputValidation.Invalid).hostError)
    }

    @Test
    fun `validates the port`() {
        assertEquals(PortError.EMPTY, (validate("", "pc", "") as ServerInputValidation.Invalid).portError)
        assertEquals(PortError.NOT_A_NUMBER, (validate("", "pc", "abc") as ServerInputValidation.Invalid).portError)
        assertEquals(PortError.OUT_OF_RANGE, (validate("", "pc", "70000") as ServerInputValidation.Invalid).portError)
        assertEquals(PortError.OUT_OF_RANGE, (validate("", "pc", "0") as ServerInputValidation.Invalid).portError)
    }

    @Test
    fun `normalizes input and defaults the name to the host`() {
        assertEquals(
            ServerInputValidation.Valid(ServerInput("gaming-pc.local", "gaming-pc.local", 6742)),
            validate("  ", " Gaming-PC.local ", " 6742 "),
        )
        assertEquals("Desk", (validate(" Desk ", "[FD00::1]", "1") as ServerInputValidation.Valid).input.name)
        assertEquals("fd00::1", (validate("x", "[FD00::1]", "1") as ServerInputValidation.Valid).input.host)
    }
}
