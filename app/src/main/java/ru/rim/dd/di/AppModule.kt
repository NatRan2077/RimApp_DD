package ru.rim.dd.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ru.rim.dd.data.local.AppDatabase
import ru.rim.dd.data.local.ReadingDao
import ru.rim.dd.data.repository.MeterRepository
import ru.rim.dd.data.repository.MeterRepositoryImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindMeterRepository(impl: MeterRepositoryImpl): MeterRepository
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.create(context)

    @Provides
    fun provideReadingDao(db: AppDatabase): ReadingDao = db.readingDao()
}
