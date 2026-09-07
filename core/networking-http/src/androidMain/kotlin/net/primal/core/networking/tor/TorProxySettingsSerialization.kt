package net.primal.core.networking.tor

import androidx.datastore.core.okio.OkioSerializer
import kotlinx.serialization.json.Json
import okio.BufferedSink
import okio.BufferedSource
import okio.use

internal object TorProxySettingsSerialization : OkioSerializer<TorProxySettings> {

    private val jsonSerializer = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    override val defaultValue: TorProxySettings = TorProxySettings()

    override suspend fun readFrom(source: BufferedSource): TorProxySettings {
        return jsonSerializer.decodeFromString<TorProxySettings>(source.readUtf8())
    }

    override suspend fun writeTo(t: TorProxySettings, sink: BufferedSink) {
        sink.use {
            it.writeUtf8(jsonSerializer.encodeToString(TorProxySettings.serializer(), t))
        }
    }
}
