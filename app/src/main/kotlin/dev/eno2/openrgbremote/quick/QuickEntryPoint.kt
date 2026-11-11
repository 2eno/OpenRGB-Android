package dev.eno2.openrgbremote.quick

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.eno2.openrgbremote.core.data.connection.ApplicationScope
import dev.eno2.openrgbremote.core.domain.repository.EffectController
import dev.eno2.openrgbremote.core.domain.repository.ProfileRepository
import dev.eno2.openrgbremote.core.domain.repository.QuickActionRepository
import dev.eno2.openrgbremote.core.domain.repository.ScheduleRepository
import kotlinx.coroutines.CoroutineScope

/** Access to singletons from classes Hilt does not create (widget, tiles, receivers). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface QuickEntryPoint {
    fun runner(): QuickActionRunner
    fun labels(): QuickActionLabels
    fun quickActions(): QuickActionRepository
    fun effects(): EffectController
    fun schedules(): ScheduleRepository
    fun profiles(): ProfileRepository
    fun scheduleAlarms(): ScheduleAlarms

    @ApplicationScope fun scope(): CoroutineScope

    companion object {
        fun get(context: Context): QuickEntryPoint = EntryPointAccessors.fromApplication(context.applicationContext, QuickEntryPoint::class.java)
    }
}
