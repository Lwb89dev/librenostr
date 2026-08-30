package net.primal.android.premium.manage.media.api

import javax.inject.Inject
import net.primal.android.premium.manage.media.api.model.MediaStorageStats
import net.primal.android.premium.manage.media.api.model.MediaUploadsResponse

/** Paid Primal media management is not part of LibreNostr. */
class MediaManagementApiImpl @Inject constructor() : MediaManagementApi {
    override suspend fun getMediaStats(userId: String) =
        MediaStorageStats(videosInBytes = 0, imagesInBytes = 0, otherFilesInBytes = 0)

    override suspend fun getMediaUploads(userId: String) =
        MediaUploadsResponse(paging = null, cdnResources = emptyList(), uploadInfo = null)

    override suspend fun deleteMedia(userId: String, mediaUrl: String) = Unit
}
