package dev.karipap.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.karipap.app.db.AppsRepository
import dev.karipap.app.db.CannoliDatabase
import dev.karipap.app.db.CollectionsRepository
import dev.karipap.app.db.RecentlyPlayedRepository
import dev.karipap.app.db.RomScanner
import dev.karipap.app.config.PlatformConfig
import dev.karipap.app.db.RomsRepository
import dev.karipap.app.util.ArcadeTitleLookup
import dev.karipap.app.util.ArtworkLookup
import dev.karipap.app.util.GamelistXmlManager
import dev.karipap.app.util.RomDirectoryWalker
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun provideCannoliDatabase(paths: CannoliPathsProvider): CannoliDatabase =
        CannoliDatabase(paths)

    @Provides @Singleton
    fun provideArtworkLookup(paths: CannoliPathsProvider): ArtworkLookup =
        ArtworkLookup(paths)

    @Provides @Singleton
    fun provideArcadeTitleLookup(paths: CannoliPathsProvider): ArcadeTitleLookup =
        ArcadeTitleLookup(paths)

    @Provides @Singleton
    fun provideGamelistXmlManager(paths: CannoliPathsProvider): GamelistXmlManager =
        GamelistXmlManager(paths)

    @Provides @Singleton
    fun provideRomDirectoryWalker(
        paths: CannoliPathsProvider,
        platformConfig: PlatformConfig,
        arcadeTitleLookup: ArcadeTitleLookup,
        @ApplicationContext context: Context,
    ): RomDirectoryWalker = RomDirectoryWalker(paths, context.assets, platformConfig, arcadeTitleLookup)

    @Provides @Singleton
    fun provideRomScanner(
        db: CannoliDatabase,
        walker: RomDirectoryWalker,
        artwork: ArtworkLookup,
    ): RomScanner = RomScanner(db, walker, artwork)

    @Provides @Singleton
    fun provideRomsRepository(
        paths: CannoliPathsProvider,
        db: CannoliDatabase,
        artwork: ArtworkLookup,
        gamelist: GamelistXmlManager,
    ): RomsRepository = RomsRepository(paths, db, artwork, gamelist)

    @Provides @Singleton
    fun provideAppsRepository(db: CannoliDatabase): AppsRepository =
        AppsRepository(db)

    @Provides @Singleton
    fun provideCollectionsRepository(db: CannoliDatabase): CollectionsRepository =
        CollectionsRepository(db)

    @Provides @Singleton
    fun provideRecentlyPlayedRepository(db: CannoliDatabase): RecentlyPlayedRepository =
        RecentlyPlayedRepository(db)
}
