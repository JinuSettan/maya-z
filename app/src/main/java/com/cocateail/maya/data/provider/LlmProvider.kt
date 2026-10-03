package com.cocateail.maya.data.provider

import android.graphics.Bitmap
import com.cocateail.maya.data.model.DeveloperConfig
import com.cocateail.maya.data.model.MayaCharacter
import com.cocateail.maya.data.specialist.SpecialistType

data class LlmRequest(
    val prompt: String,
    val history: List<Pair<String, String>>,
    val character: MayaCharacter,
    val config: DeveloperConfig,
    val specialistType: SpecialistType,
    val imageBitmap: Bitmap? = null,
    val replyToId: String? = null,
    val repliedMessageSender: String? = null,
    val repliedMessageText: String? = null,
    val repliedMessageImageUrl: String? = null
)

interface LlmProvider {
    val providerId: String
    val providerDisplayName: String

    suspend fun generateResponse(request: LlmRequest): String?
}
