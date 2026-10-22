package ru.rim.dd.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.rim.dd.data.repository.ConnectionRepository
import ru.rim.dd.data.repository.ConnectionRepositoryImpl
import ru.rim.dd.data.repository.JournalRepository
import ru.rim.dd.data.repository.JournalRepositoryImpl
import ru.rim.dd.data.repository.MeterDataRepository
import ru.rim.dd.data.repository.MeterDataRepositoryImpl
import ru.rim.dd.data.repository.RelayRepository
import ru.rim.dd.data.repository.RelayRepositoryImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindConnectionRepository(impl: ConnectionRepositoryImpl): ConnectionRepository

    @Binds
    @Singleton
    abstract fun bindMeterDataRepository(impl: MeterDataRepositoryImpl): MeterDataRepository

    @Binds
    @Singleton
    abstract fun bindRelayRepository(impl: RelayRepositoryImpl): RelayRepository

    @Binds
    @Singleton
    abstract fun bindJournalRepository(impl: JournalRepositoryImpl): JournalRepository
}
