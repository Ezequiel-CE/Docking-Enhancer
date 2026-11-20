package com.odininputmirror.domain.usecase

import com.odininputmirror.domain.repository.MirrorSettingsRepository

class SetManualExternalControllerUseCase(
    private val mirrorSettingsRepository: MirrorSettingsRepository,
) {
    operator fun invoke(guid: String?) {
        mirrorSettingsRepository.setManualExternalController(guid)
    }
}
