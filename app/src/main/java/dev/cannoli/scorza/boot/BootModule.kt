package dev.cannoli.scorza.boot

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent

@Module
@InstallIn(ActivityComponent::class)
abstract class BootModule {
    @Binds
    abstract fun bindPermissionStatus(impl: AndroidPermissionStatus): PermissionStatus
}
