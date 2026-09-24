package net.primal.core.networking.tor

import io.kotest.matchers.shouldBe
import org.junit.Test

class RouteControllerTest {

    private fun config(mode: NetworkMode, engine: TorEngineType = TorEngineType.ORBOT, port: Int = 9050) =
        RouteConfig(mode = mode, engine = engine, orbotPort = port)

    @Test
    fun `it starts at epoch zero with the initial configuration`() {
        val controller = RouteController(config(NetworkMode.DIRECT))

        controller.state.value shouldBe RouteState(config(NetworkMode.DIRECT), epoch = 0)
    }

    @Test
    fun `a real change moves the epoch and reports it`() {
        val controller = RouteController(config(NetworkMode.DIRECT))

        controller.update(config(NetworkMode.TOR)) shouldBe true
        controller.state.value shouldBe RouteState(config(NetworkMode.TOR), epoch = 1)

        controller.update(config(NetworkMode.ONION_ONLY)) shouldBe true
        controller.state.value.epoch shouldBe 2
    }

    @Test
    fun `writing the same configuration again changes nothing`() {
        val controller = RouteController(config(NetworkMode.TOR))

        controller.update(config(NetworkMode.TOR)) shouldBe false

        controller.state.value.epoch shouldBe 0
    }

    @Test
    fun `changing only the engine or the Orbot port counts as a change`() {
        val controller = RouteController(config(NetworkMode.TOR))

        // Connections opened through the old proxy must not outlive it.
        controller.update(config(NetworkMode.TOR, engine = TorEngineType.BUILT_IN)) shouldBe true
        controller.update(config(NetworkMode.TOR, engine = TorEngineType.BUILT_IN, port = 9150)) shouldBe true

        controller.state.value.epoch shouldBe 2
    }

    @Test
    fun `the configuration is readable without collecting the flow`() {
        val controller = RouteController(config(NetworkMode.DIRECT))
        controller.update(config(NetworkMode.TOR))

        controller.config shouldBe config(NetworkMode.TOR)
    }
}
