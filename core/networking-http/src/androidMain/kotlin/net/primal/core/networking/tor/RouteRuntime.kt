package net.primal.core.networking.tor

/**
 * What a client needs to know to follow the network mode: the live [controller] and where the
 * built-in engine's proxy currently is. Separate from [NetworkRoute] and the built-in engine object
 * so it can be built from fakes in tests.
 */
class RouteRuntime(
    val controller: RouteController,
    val builtInPort: () -> Int?,
    val awaitBuiltInPort: (timeoutMs: Long) -> Int?,
)
