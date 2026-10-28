package ru.rim.dd.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.rim.dd.core.ble.MeterBleClient
import ru.rim.dd.core.ble.MeterTransport
import ru.rim.dd.core.bridge.NativeDlmsApi
import ru.rim.dd.core.bridge.SpodesClientBridge
import ru.rim.dd.core.coroutines.DefaultDispatcherProvider
import ru.rim.dd.core.coroutines.DispatcherProvider
import ru.rim.dd.data.local.JournalStore
import ru.rim.dd.data.local.JournalWriter
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

/**
 * Связка интерфейсов с боевыми реализациями. Всё, что сеанс знает о Bluetooth, нативном клиенте,
 * диспетчерах и журнале, — это интерфейсы ниже; в unit-тестах вместо них подставляются фейки.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CoreModule {
    @Binds
    @Singleton
    abstract fun bindMeterTransport(impl: MeterBleClient): MeterTransport

    @Binds
    @Singleton
    abstract fun bindNativeDlmsApi(impl: SpodesClientBridge): NativeDlmsApi

    @Binds
    @Singleton
    abstract fun bindDispatcherProvider(impl: DefaultDispatcherProvider): DispatcherProvider

    @Binds
    @Singleton
    abstract fun bindJournalWriter(impl: JournalStore): JournalWriter
}
