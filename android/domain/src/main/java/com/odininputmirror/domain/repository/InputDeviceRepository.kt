package com.odininputmirror.domain.repository

import com.odininputmirror.domain.model.ControllerDevice
import com.odininputmirror.domain.model.MappingKey
import com.odininputmirror.domain.model.TargetTraits

interface InputDeviceRepository {
    fun getConnectedControllers(): List<ControllerDevice>

    /**
     * What the mirror's target — the internal pad — declares it can receive, read from the live
     * device. Null when there is no internal pad to inspect; callers fall back to the measured Odin
     * defaults rather than to an empty editor.
     */
    fun targetTraits(): TargetTraits? = null

    /**
     * The capability bitmasks of the REAL external controller behind this mapping key. Same shape as
     * the target's traits, no labels: what matters here is which codes the pad declares, because the
     * controller database's indices only mean anything counted over exactly that set.
     */
    fun sourceTraits(key: MappingKey): TargetTraits? = null

    /**
     * The capability bitmasks of the node the mirror actually READS for this mapping key: the
     * firmware's republished twin of the real pad. Null when no twin exists.
     *
     * Some firmware (the Retroid Pocket 6) re-publishes every external pad already translated to the
     * handheld's own layout, so the twin's codes are not the real pad's codes. A default mapping built
     * from the real pad's layout would then be applied on top of a translation that already happened.
     */
    fun twinTraits(key: MappingKey): TargetTraits? = null
}
