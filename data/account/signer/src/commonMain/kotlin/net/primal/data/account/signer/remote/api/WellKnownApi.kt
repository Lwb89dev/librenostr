package net.primal.data.account.signer.remote.api

import de.jensklingenberg.ktorfit.http.GET
import net.primal.data.account.signer.remote.api.model.MediumTrustPermissionsResponse
import net.primal.data.account.signer.remote.api.model.PermissionsResponse

interface WellKnownApi {
    @GET("https://nostrich.org/.well-known/librenostr-nip46-defaults.json")
    suspend fun getMediumTrustPermissions(): MediumTrustPermissionsResponse

    @GET("https://nostrich.org/.well-known/librenostr-nip46-permissions.json")
    suspend fun getSignerPermissions(): PermissionsResponse
}
