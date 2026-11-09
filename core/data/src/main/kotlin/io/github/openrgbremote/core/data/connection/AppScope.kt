package io.github.openrgbremote.core.data.connection

import javax.inject.Qualifier

/** Coroutine scope that lives as long as the process (connections, network monitoring). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
