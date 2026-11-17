package ru.rim.dd.core.dlms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.rim.dd.testutil.RealMeterBuffer
import java.io.File

/** Справочник OBIS-кодов: названия из паспортов приборов, подписи для экранов. */
class ObisCatalogTest {

    @Test
    fun knownCodes_giveRussianNames() {
        assertEquals("Температура", ObisCatalog.nameOf("0.0.96.9.0.255"))
        assertEquals("Напряжение батарейки", ObisCatalog.nameOf("0.0.96.6.3.255"))
        assertEquals("Номинальное напряжение", ObisCatalog.nameOf("1.0.0.6.0.255"))
    }

    @Test
    fun unknownCode_hasNoName_andCaptionIsTheCodeItself() {
        assertNull(ObisCatalog.nameOf("9.9.9.9.9.9"))
        assertEquals("9.9.9.9.9.9", ObisCatalog.caption("9.9.9.9.9.9"))
    }

    @Test
    fun caption_isNameDotCode() {
        assertEquals("Температура · 0.0.96.9.0.255", ObisCatalog.caption("0.0.96.9.0.255"))
    }

    @Test
    fun codesFromRealBuffer_exceptPositionalFields_haveNames() {
        // Позиционные поля буфера (часы, реле, фазы, тариф, «Значки ДД») паспортных названий не имеют.
        val positional = setOf(CLOCK_STATUS_OBIS, RELAY_CONTROL_STATE_OBIS, DD_PHASE_DISPLAY_OBIS, CURRENT_TARIFF_OBIS, TAMPER_STATUS_OBIS)
        val missing = RealMeterBuffer.readings().map { it.obisCode }.filter { it !in positional && ObisCatalog.nameOf(it) == null }
        assertTrue("нет названия для: $missing", missing.isEmpty())
    }

    @Test
    fun sourceHasNoAccidentalDuplicateKeys() {
        // mapOf молча оставляет последнее значение при повторе ключа — опечатка в коде осталась бы незамеченной.
        // Два повтора намеренные: в конце таблицы названия из прошивки пульта заменяют паспортные.
        val intentionalOverrides = setOf("0.0.0.9.1.255", "0.0.0.9.2.255")
        val file = File("src/main/java/ru/rim/dd/core/dlms/ObisCatalog.kt")
        assumeTrue("исходник не найден из рабочей папки теста", file.exists())
        val keys = Regex("^\\s*\"([0-9.]+)\" to ", RegexOption.MULTILINE).findAll(file.readText()).map { it.groupValues[1] }.toList()
        assertTrue("в справочнике должно быть много записей", keys.size > 300)
        val duplicates = keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertEquals("повторяющиеся OBIS-коды сверх намеренных", emptySet<String>(), duplicates - intentionalOverrides)
        assertEquals("Время прибора", ObisCatalog.nameOf("0.0.0.9.1.255"))
        assertEquals("Дата прибора", ObisCatalog.nameOf("0.0.0.9.2.255"))
        assertNotNull(ObisCatalog.nameOf(keys.first()))
    }

    //Проверка всех известных, что все точно работает
    // Register: базовые параметры и константы
    @Test
        fun register_basicParameters() {
            assertEquals("Продолжительность прерывания питания", ObisCatalog.nameOf("0.0.96.50.27.255"))
            assertEquals("Напряжение батарейки", ObisCatalog.nameOf("0.0.96.6.3.255"))
            assertEquals("Время работы", ObisCatalog.nameOf("0.0.96.8.0.255"))
            assertEquals("Температура", ObisCatalog.nameOf("0.0.96.9.0.255"))
            assertEquals("Сопротивление потерь", ObisCatalog.nameOf("1.0.0.10.2.255"))
            assertEquals("Постоянная счетчика (акт.)", ObisCatalog.nameOf("1.0.0.3.3.255"))
            assertEquals("Постоянная счетчика (реакт.)", ObisCatalog.nameOf("1.0.0.3.4.255"))
        }

        @Test
        fun register_nominalValues() {
            assertEquals("Номинальное напряжение", ObisCatalog.nameOf("1.0.0.6.0.255"))
            assertEquals("Номинальный ток", ObisCatalog.nameOf("1.0.0.6.1.255"))
            assertEquals("Номинальная частота", ObisCatalog.nameOf("1.0.0.6.2.255"))
            assertEquals("Максимальный ток", ObisCatalog.nameOf("1.0.0.6.3.255"))
            assertEquals("Согласованное напряжение", ObisCatalog.nameOf("1.0.0.6.4.255"))
        }

        @Test
        fun register_activePowerAndEnergy() {
            assertEquals("Активн. потр.", ObisCatalog.nameOf("1.0.1.29.0.255"))
            assertEquals("Активная мощность", ObisCatalog.nameOf("1.0.1.7.0.255"))
            assertEquals("Активн. потр. РДЧ", ObisCatalog.nameOf("1.0.1.8.0.101"))
            assertEquals("Активн. потр.", ObisCatalog.nameOf("1.0.1.8.0.255"))
        }

        @Test
        fun register_activeTariffs() {
            for (t in 1..8) {
                assertEquals("$t тариф (акт. потр.)", ObisCatalog.nameOf("1.0.1.8.$t.255"))
                assertEquals("$t тариф РДЧ (акт. потр.)", ObisCatalog.nameOf("1.0.1.8.$t.101"))
            }
        }

        @Test
        fun register_reactivePowerAndEnergy() {
            assertEquals("Реактивн. потр.", ObisCatalog.nameOf("1.0.3.29.0.255"))
            assertEquals("Реактивная мощность", ObisCatalog.nameOf("1.0.3.7.0.255"))
            assertEquals("Реактивн. потр. РДЧ", ObisCatalog.nameOf("1.0.3.8.0.101"))
            assertEquals("Реактивн. потр.", ObisCatalog.nameOf("1.0.3.8.0.255"))
            assertEquals("Реактивн. ген.", ObisCatalog.nameOf("1.0.4.8.0.255"))
        }

        @Test
        fun register_reactiveTariffs() {
            for (t in 1..8) {
                assertEquals("$t тариф (реакт. потр.)", ObisCatalog.nameOf("1.0.3.8.$t.255"))
                assertEquals("$t тариф РДЧ (реакт. потр.)", ObisCatalog.nameOf("1.0.3.8.$t.101"))
                assertEquals("$t тариф (реакт. ген.)", ObisCatalog.nameOf("1.0.4.8.$t.255"))
                assertEquals("$t тариф РДЧ (реакт. ген.)", ObisCatalog.nameOf("1.0.4.8.$t.101"))
            }
        }

        // Register: качество электроэнергии (ПКЭ)
        @Test
        fun register_voltageDeviations() {
            assertEquals("Положит. отклонение напр.", ObisCatalog.nameOf("1.0.12.128.0.255"))
            assertEquals("Положит. отклонение напр. AB", ObisCatalog.nameOf("1.0.12.128.1.255"))
            assertEquals("Положит. отклонение напр. BC", ObisCatalog.nameOf("1.0.12.128.2.255"))
            assertEquals("Положит. отклонение напр. CA", ObisCatalog.nameOf("1.0.12.128.3.255"))
            assertEquals("Отриц. отклонение напр.", ObisCatalog.nameOf("1.0.12.129.0.255"))
            assertEquals("Установивш. отклонение напр.", ObisCatalog.nameOf("1.0.12.130.0.255"))
        }

        @Test
        fun register_voltageThresholds() {
            assertEquals("Порог провала", ObisCatalog.nameOf("1.0.12.31.0.255"))
            assertEquals("Порог перенапряжения", ObisCatalog.nameOf("1.0.12.35.0.255"))
            assertEquals("Порог отключения", ObisCatalog.nameOf("1.0.12.39.0.255"))
            assertEquals("Время провала", ObisCatalog.nameOf("1.0.12.43.0.255"))
            assertEquals("Время перенапряжения", ObisCatalog.nameOf("1.0.12.44.0.255"))
            assertEquals("Время отключения", ObisCatalog.nameOf("1.0.12.45.0.255"))
        }

        @Test
        fun register_phaseVoltages() {
            assertEquals("Напряжение", ObisCatalog.nameOf("1.0.12.7.0.255"))
            assertEquals("Напряжение AB", ObisCatalog.nameOf("1.0.12.7.1.255"))
            assertEquals("Напряжение BC", ObisCatalog.nameOf("1.0.12.7.2.255"))
            assertEquals("Напряжение CA", ObisCatalog.nameOf("1.0.12.7.3.255"))
            assertEquals("Глубина от U ном.", ObisCatalog.nameOf("1.0.12.7.4.255"))
            assertEquals("Напряжение AB", ObisCatalog.nameOf("1.0.124.7.0.255"))
            assertEquals("Напряжение BC", ObisCatalog.nameOf("1.0.125.7.0.255"))
            assertEquals("Напряжение CA", ObisCatalog.nameOf("1.0.126.7.0.255"))
        }

        @Test
        fun register_currentAndFrequency() {
            assertEquals("Ток", ObisCatalog.nameOf("1.0.11.7.0.255"))
            assertEquals("Лимит по току", ObisCatalog.nameOf("1.0.11.134.0.255"))
            assertEquals("Частота", ObisCatalog.nameOf("1.0.14.7.0.255"))
            assertEquals("Отклонение частоты", ObisCatalog.nameOf("1.0.14.130.0.255"))
            assertEquals("Порог отклонения частоты", ObisCatalog.nameOf("1.0.145.35.0.255"))
        }

        @Test
        fun register_powerQualityCoefficients() {
            assertEquals("Cos Ф", ObisCatalog.nameOf("1.0.13.7.0.255"))
            assertEquals("Tg(Ф), фаза A", ObisCatalog.nameOf("1.0.128.7.0.255"))
            assertEquals("Tg(Ф), фаза B", ObisCatalog.nameOf("1.0.129.7.0.255"))
            assertEquals("Tg(Ф), фаза C", ObisCatalog.nameOf("1.0.130.7.0.255"))
            assertEquals("Tg(Ф) средн. за час", ObisCatalog.nameOf("1.0.131.27.0.255"))
            assertEquals("Порог превыш. Tg(Ф)", ObisCatalog.nameOf("1.0.131.35.0.255"))
            assertEquals("Tg(Ф), длительность", ObisCatalog.nameOf("1.0.131.37.0.255"))
            assertEquals("Защитный интервал Tg(Ф)", ObisCatalog.nameOf("1.0.131.44.0.255"))
            assertEquals("Tg(Ф), максимум", ObisCatalog.nameOf("1.0.131.6.128.255"))
            assertEquals("Tg(Ф)", ObisCatalog.nameOf("1.0.131.7.0.255"))
        }

        @Test
        fun register_asymmetry() {
            assertEquals("Напряжение прямой посл.", ObisCatalog.nameOf("1.0.132.7.0.255"))
            assertEquals("Напряжение обратной посл.", ObisCatalog.nameOf("1.0.133.7.0.255"))
            assertEquals("Напряжение нулевой посл.", ObisCatalog.nameOf("1.0.134.7.0.255"))
            assertEquals("К-ент несимметрии (обратный)", ObisCatalog.nameOf("1.0.135.7.0.255"))
            assertEquals("К-ент несимметрии (нулевой)", ObisCatalog.nameOf("1.0.136.7.0.255"))
            assertEquals("Порог к-та несимметрии (обр.)", ObisCatalog.nameOf("1.0.133.35.0.255"))
            assertEquals("Ток прямой посл.", ObisCatalog.nameOf("1.0.142.7.0.255"))
            assertEquals("Ток обратной посл.", ObisCatalog.nameOf("1.0.143.7.0.255"))
            assertEquals("К-ент несимметрии по току (обратный)", ObisCatalog.nameOf("1.0.145.7.0.255"))
            assertEquals("Ток нейтрали", ObisCatalog.nameOf("1.0.91.7.0.255"))
            assertEquals("Разбаланс токов", ObisCatalog.nameOf("1.0.91.7.131.255"))
            assertEquals("Разбаланс токов, %", ObisCatalog.nameOf("1.0.91.7.132.255"))
        }

        //Register: фазные величины (A, B, C)
        @Test
        fun register_phaseA() {
            assertEquals("Активная мощность, фаза A", ObisCatalog.nameOf("1.0.21.7.0.255"))
            assertEquals("Активная энергия фаза А, потр.", ObisCatalog.nameOf("1.0.21.8.0.255"))
            assertEquals("Активная энергия фаза А, ген.", ObisCatalog.nameOf("1.0.22.8.0.255"))
            assertEquals("Реактивная мощность, фаза A", ObisCatalog.nameOf("1.0.23.7.0.255"))
            assertEquals("Реактивная энергия фаза А, потр.", ObisCatalog.nameOf("1.0.23.8.0.255"))
            assertEquals("Реактивная энергия фаза А, ген.", ObisCatalog.nameOf("1.0.24.8.0.255"))
            assertEquals("Полная мощность, фаза A", ObisCatalog.nameOf("1.0.29.7.0.255"))
            assertEquals("Ток, фаза A", ObisCatalog.nameOf("1.0.31.7.0.255"))
            assertEquals("Напряжение текущее, фаза A", ObisCatalog.nameOf("1.0.32.7.0.255"))
            assertEquals("Cos(Ф), фаза A", ObisCatalog.nameOf("1.0.33.7.0.255"))
            assertEquals("Положит. отклонение напр. A", ObisCatalog.nameOf("1.0.32.128.0.255"))
            assertEquals("Отриц. отклонение напр. A", ObisCatalog.nameOf("1.0.32.129.0.255"))
        }

        @Test
        fun register_phaseB() {
            assertEquals("Активная мощность, фаза B", ObisCatalog.nameOf("1.0.41.7.0.255"))
            assertEquals("Активная энергия фаза B, потр.", ObisCatalog.nameOf("1.0.41.8.0.255"))
            assertEquals("Активная энергия фаза B, ген.", ObisCatalog.nameOf("1.0.42.8.0.255"))
            assertEquals("Реактивная мощность, фаза B", ObisCatalog.nameOf("1.0.43.7.0.255"))
            assertEquals("Реактивная энергия фаза B, потр.", ObisCatalog.nameOf("1.0.43.8.0.255"))
            assertEquals("Реактивная энергия фаза B, ген.", ObisCatalog.nameOf("1.0.44.8.0.255"))
            assertEquals("Полная мощность, фаза B", ObisCatalog.nameOf("1.0.49.7.0.255"))
            assertEquals("Ток, фаза B", ObisCatalog.nameOf("1.0.51.7.0.255"))
            assertEquals("Напряжение текущее, фаза B", ObisCatalog.nameOf("1.0.52.7.0.255"))
            assertEquals("Cos(Ф), фаза B", ObisCatalog.nameOf("1.0.53.7.0.255"))
        }

        @Test
        fun register_phaseC() {
            assertEquals("Активная мощность, фаза C", ObisCatalog.nameOf("1.0.61.7.0.255"))
            assertEquals("Активная энергия фаза C, потр.", ObisCatalog.nameOf("1.0.61.8.0.255"))
            assertEquals("Активная энергия фаза C, ген.", ObisCatalog.nameOf("1.0.62.8.0.255"))
            assertEquals("Реактивная мощность, фаза C", ObisCatalog.nameOf("1.0.63.7.0.255"))
            assertEquals("Реактивная энергия фаза C, потр.", ObisCatalog.nameOf("1.0.63.8.0.255"))
            assertEquals("Реактивная энергия фаза C, ген.", ObisCatalog.nameOf("1.0.64.8.0.255"))
            assertEquals("Полная мощность, фаза C", ObisCatalog.nameOf("1.0.69.7.0.255"))
            assertEquals("Ток, фаза C", ObisCatalog.nameOf("1.0.71.7.0.255"))
            assertEquals("Напряжение текущее, фаза C", ObisCatalog.nameOf("1.0.72.7.0.255"))
            assertEquals("Cos(Ф), фаза C", ObisCatalog.nameOf("1.0.73.7.0.255"))
        }

        @Test
        fun register_angles() {
            assertEquals("Угол Uab", ObisCatalog.nameOf("1.0.81.7.10.255"))
            assertEquals("Угол Ubc", ObisCatalog.nameOf("1.0.81.7.21.255"))
            assertEquals("Угол Uca", ObisCatalog.nameOf("1.0.81.7.2.255"))
            assertEquals("Угол UI фазы A", ObisCatalog.nameOf("1.0.81.7.4.255"))
            assertEquals("Угол UI фазы B", ObisCatalog.nameOf("1.0.81.7.15.255"))
            assertEquals("Угол UI фазы C", ObisCatalog.nameOf("1.0.81.7.26.255"))
        }

        @Test
        fun register_lossesAndQualityHours() {
            assertEquals("Удел. потерь", ObisCatalog.nameOf("1.0.88.8.0.255"))
            assertEquals("Удел. потерь РДЧ", ObisCatalog.nameOf("1.0.88.8.0.101"))
            assertEquals("Вольт кв. часы", ObisCatalog.nameOf("1.0.89.8.0.255"))
            assertEquals("Вольт кв. часы  РДЧ", ObisCatalog.nameOf("1.0.89.8.0.101"))
            assertEquals("Некач. част.", ObisCatalog.nameOf("0.0.96.8.1.255"))
            assertEquals("Некач. энер.", ObisCatalog.nameOf("0.0.96.5.1.255"))
            assertEquals("Время отклонения напряжений", ObisCatalog.nameOf("1.0.147.133.0.255"))
            assertEquals("Число перенапряжений", ObisCatalog.nameOf("1.0.148.36.0.255"))
        }

        // ---- Register: мощность и лимиты ----
        @Test
        fun register_powerLimitsAndMaxima() {
            assertEquals("Лимит по напряжению", ObisCatalog.nameOf("1.0.12.134.0.255"))
            assertEquals("Максимум", ObisCatalog.nameOf("1.0.15.16.0.255"))
            assertEquals("Максимум в часы пиков", ObisCatalog.nameOf("1.0.15.16.1.255"))
            assertEquals("Абсолютный минимум, активный", ObisCatalog.nameOf("1.0.15.3.128.255"))
            assertEquals("Порог журнала мощности", ObisCatalog.nameOf("1.0.15.35.128.255"))
            assertEquals("Порог журнала мощности (пик.)", ObisCatalog.nameOf("1.0.15.35.130.255"))
            assertEquals("Абсолютный максимум, активный", ObisCatalog.nameOf("1.0.15.6.128.255"))
            assertEquals("Усредненный максимум", ObisCatalog.nameOf("1.0.15.6.129.255"))
            assertEquals("Усредненный максимум в часы пиков", ObisCatalog.nameOf("1.0.15.6.130.255"))
            assertEquals("Модуль активной мощности", ObisCatalog.nameOf("1.0.15.7.0.255"))
            assertEquals("Абсолютный минимум, реакт.", ObisCatalog.nameOf("1.0.137.3.128.255"))
            assertEquals("Абсолютный максимум, реакт.", ObisCatalog.nameOf("1.0.137.6.128.255"))
            assertEquals("Абсолютный минимум, полн.", ObisCatalog.nameOf("1.0.9.3.128.255"))
            assertEquals("Абсолютный максимум, полн.", ObisCatalog.nameOf("1.0.9.6.128.255"))
            assertEquals("Полная мощность", ObisCatalog.nameOf("1.0.9.7.0.255"))
        }

        //  Data: идентификация и время
        @Test
        fun data_identification() {
            assertEquals("Идентификатор ПО", ObisCatalog.nameOf("0.0.0.2.0.255"))
            assertEquals("Идентификатор версии", ObisCatalog.nameOf("0.0.0.2.1.255"))
            assertEquals("MD5 (полный)", ObisCatalog.nameOf("0.0.0.2.8.255"))
            assertEquals("MD5 (диапазон)", ObisCatalog.nameOf("0.0.0.2.164.255"))
            assertEquals("Диапазон расчета MD5", ObisCatalog.nameOf("0.0.0.2.165.255"))
            assertEquals("Серийный номер", ObisCatalog.nameOf("0.0.96.1.0.255"))
            assertEquals("Тип прибора", ObisCatalog.nameOf("0.0.96.1.1.255"))
            assertEquals("Версия ПО", ObisCatalog.nameOf("0.0.96.1.2.255"))
            assertEquals("Производитель", ObisCatalog.nameOf("0.0.96.1.3.255"))
            assertEquals("Год выпуска", ObisCatalog.nameOf("0.0.96.1.4.255"))
            assertEquals("Дистанционный дисплей", ObisCatalog.nameOf("0.0.96.1.5.255"))
            assertEquals("Версия СПОДЭС", ObisCatalog.nameOf("0.0.96.1.6.255"))
            assertEquals("Схема подключения", ObisCatalog.nameOf("0.0.96.1.7.255"))
            assertEquals("Описание", ObisCatalog.nameOf("0.0.96.1.10.255"))
            assertEquals("Парный модуль", ObisCatalog.nameOf("0.0.96.1.164.255"))
            assertEquals("Bluetooth", ObisCatalog.nameOf("0.0.96.1.170.255"))
            assertEquals("Логическое имя", ObisCatalog.nameOf("0.0.42.0.0.255"))
        }

        @Test
        fun data_timeAndCorrections() {
            assertEquals("Время прибора", ObisCatalog.nameOf("0.0.0.9.1.255")) // перекрыто прошивкой
            assertEquals("Дата прибора", ObisCatalog.nameOf("0.0.0.9.2.255"))
            assertEquals("Счетчик коррекций", ObisCatalog.nameOf("0.0.96.2.0.255"))
            assertEquals("Время последней коррекции", ObisCatalog.nameOf("0.0.96.2.1.255"))
            assertEquals("Счетчик мастер-коррекций", ObisCatalog.nameOf("0.0.96.2.10.255"))
            assertEquals("Время последней мастер-коррекции", ObisCatalog.nameOf("0.0.96.2.11.255"))
            assertEquals("Время установки часов", ObisCatalog.nameOf("0.0.96.2.12.255"))
            assertEquals("Время активации", ObisCatalog.nameOf("0.0.96.2.13.255"))
            assertEquals("Время калибровки", ObisCatalog.nameOf("0.0.96.2.5.255"))
            assertEquals("Время изм. тариф. расп.", ObisCatalog.nameOf("0.0.96.2.7.255"))
            assertEquals("Время последнего самоконтроля", ObisCatalog.nameOf("0.0.96.2.128.255"))
        }

        @Test
        fun data_tariffsAndRelay() {
            assertEquals("Текущий тариф", ObisCatalog.nameOf("0.0.96.14.0.255"))
            assertEquals("Счетчик выключений размыкателя", ObisCatalog.nameOf("0.0.96.15.0.255"))
            assertEquals("Счетчик дискр. входа 1", ObisCatalog.nameOf("0.0.96.15.50.255"))
            assertEquals("Счетчик дискр. входа 2", ObisCatalog.nameOf("0.0.96.15.51.255"))
            assertEquals("Аварийный тариф", ObisCatalog.nameOf("0.0.96.5.131.255"))
            assertEquals("Дискретные входы-выходы", ObisCatalog.nameOf("0.0.96.3.0.255"))
            assertEquals("Дискретные входы", ObisCatalog.nameOf("0.0.96.3.1.255"))
            assertEquals("Дискретные выходы", ObisCatalog.nameOf("0.0.96.3.2.255"))
            assertEquals("Размыкатель", ObisCatalog.nameOf("0.0.96.3.10.255"))
        }

        // Data: пломбы и датчики вмешательства
        @Test
        fun data_sealsCountersAndTiming() {
            assertEquals("Счетчик пломбы корпуса", ObisCatalog.nameOf("0.0.96.20.0.255"))
            assertEquals("Последняя сработка пломбы корпуса", ObisCatalog.nameOf("0.0.96.20.1.255"))
            assertEquals("Продолж. сработки пломбы корпуса", ObisCatalog.nameOf("0.0.96.20.2.255"))
            assertEquals("Общее время сработки пломбы корпуса", ObisCatalog.nameOf("0.0.96.20.3.255"))
            assertEquals("Счетчик пломбы клеммника", ObisCatalog.nameOf("0.0.96.20.5.255"))
            assertEquals("Последняя сработка пломбы клеммника", ObisCatalog.nameOf("0.0.96.20.6.255"))
            assertEquals("Продолж. сработки пломбы клеммника", ObisCatalog.nameOf("0.0.96.20.7.255"))
            assertEquals("Общее время сработки пломбы клеммника", ObisCatalog.nameOf("0.0.96.20.8.255"))
            assertEquals("Счетчик магнитного датчика", ObisCatalog.nameOf("0.0.96.20.15.255"))
            assertEquals("Последняя сработка магнитного датчика", ObisCatalog.nameOf("0.0.96.20.16.255"))
            assertEquals("Продолж. сработки магнитного датчика", ObisCatalog.nameOf("0.0.96.20.17.255"))
            assertEquals("Общее время сработки магнитного датчика", ObisCatalog.nameOf("0.0.96.20.18.255"))
        }

        @Test
        fun data_sealStatesAndMeteringModule() {
            assertEquals("Пломба корпуса", ObisCatalog.nameOf("0.0.96.51.0.255"))
            assertEquals("Пломба клеммника", ObisCatalog.nameOf("0.0.96.51.1.255"))
            assertEquals("Пломба МС", ObisCatalog.nameOf("0.0.96.51.2.255"))
            assertEquals("Магнитное поле", ObisCatalog.nameOf("0.0.96.51.3.255"))
            assertEquals("ВЧ поле", ObisCatalog.nameOf("0.0.96.51.4.255"))
            assertEquals("Состояние пломб", ObisCatalog.nameOf("0.0.96.51.5.255"))
            assertEquals("Обжатие пломб", ObisCatalog.nameOf("0.0.96.51.6.255"))
            assertEquals("Сброс", ObisCatalog.nameOf("0.0.96.51.7.255"))
            assertEquals("Первое вскрытие корпуса", ObisCatalog.nameOf("0.0.96.51.8.255"))
            assertEquals("Первое вскрытие клеммника", ObisCatalog.nameOf("0.0.96.51.9.255"))
            assertEquals("Первое вскрытие МС", ObisCatalog.nameOf("0.0.96.51.12.255"))
        }

        @Test
        fun data_sealMsAndTamperFlags() {
            assertEquals("Счетчик пломбы МС", ObisCatalog.nameOf("0.0.96.50.45.255"))
            assertEquals("Последняя сработка пломбы МС", ObisCatalog.nameOf("0.0.96.50.46.255"))
            assertEquals("Продолж. сработки пломбы МС", ObisCatalog.nameOf("0.0.96.50.47.255"))
            assertEquals("Общее время сработки пломбы МС", ObisCatalog.nameOf("0.0.96.50.48.255"))
            assertEquals("Счетчик пломбы МС", ObisCatalog.nameOf("0.0.96.75.0.255"))
            assertEquals("Последняя сработка пломбы МС", ObisCatalog.nameOf("0.0.96.75.1.255"))
            assertEquals("Продолж. сработки пломбы МС", ObisCatalog.nameOf("0.0.96.75.2.255"))
            assertEquals("Общее время сработки пломбы МС", ObisCatalog.nameOf("0.0.96.75.3.255"))
            assertEquals("Значки ДД", ObisCatalog.nameOf("0.0.96.50.170.255"))
            assertEquals("Текущее состояние", ObisCatalog.nameOf("0.0.97.98.0.255"))
            assertEquals("Маска событий инициативного выхода", ObisCatalog.nameOf("0.0.97.98.10.255"))
            assertEquals("Установленные флаги", ObisCatalog.nameOf("0.0.97.98.20.255"))
            assertEquals("Статус самодиагностики", ObisCatalog.nameOf("0.0.96.5.170.255"))
            assertEquals("Статус контроля мощности", ObisCatalog.nameOf("0.0.96.5.2.255"))
        }

        //Data: связь, порты, push
        @Test
        fun data_communicationAndPorts() {
            assertEquals("Порт", ObisCatalog.nameOf("0.0.96.12.4.255"))
            assertEquals("Клиент", ObisCatalog.nameOf("0.0.96.12.6.255"))
            assertEquals("Коммуникационный профиль", ObisCatalog.nameOf("0.0.135.210.0.255"))
            assertEquals("Статус учета энергии", ObisCatalog.nameOf("0.0.135.220.0.255"))
            assertEquals("RFPLC-адрес маршрутизатора (тест RF ПУ)", ObisCatalog.nameOf("0.0.96.128.1.255"))
            assertEquals("Счетчик принятых пакетов 868 МГц", ObisCatalog.nameOf("0.0.96.128.2.255"))
            assertEquals("Счетчик принятых пакетов 433 МГц", ObisCatalog.nameOf("0.0.96.128.3.255"))
            assertEquals("Мощность передатчика", ObisCatalog.nameOf("0.0.96.128.4.255"))
            assertEquals("Адаптация радио для Монголии", ObisCatalog.nameOf("0.0.96.128.5.255"))
            assertEquals("Индикатор режима Turbo", ObisCatalog.nameOf("0.0.96.128.6.255"))
            assertEquals("Источник Push 1", ObisCatalog.nameOf("0.0.96.5.134.255"))
            assertEquals("Источник Push 2", ObisCatalog.nameOf("0.1.96.5.134.255"))
            assertEquals("Источник Push 3", ObisCatalog.nameOf("0.2.96.5.134.255"))
        }

        // Data: разное
        @Test
        fun data_miscellaneous() {
            assertEquals("Часы больших нагрузок", ObisCatalog.nameOf("0.0.128.1.0.255"))
            assertEquals("Часы утреннего и вечернего пика", ObisCatalog.nameOf("0.0.128.2.0.255"))
            assertEquals("Настройка индикатора (кнопка)", ObisCatalog.nameOf("0.0.128.21.0.255"))
            assertEquals("Настройка индикатора (авто)", ObisCatalog.nameOf("0.0.128.21.1.255"))
            assertEquals("Подсветка индикатора", ObisCatalog.nameOf("0.0.96.4.1.255"))
            assertEquals("Режим телеметр. выходов", ObisCatalog.nameOf("0.0.96.4.2.255"))
            assertEquals("Аппаратная блокировка", ObisCatalog.nameOf("0.0.96.4.3.255"))
            assertEquals("Произвольная константа", ObisCatalog.nameOf("0.0.96.4.4.255"))
            assertEquals("Инициативный выход", ObisCatalog.nameOf("0.0.96.5.3.255"))
            assertEquals("Последняя запись журнала", ObisCatalog.nameOf("0.0.96.5.135.255"))
            assertEquals("Счетчик CTR", ObisCatalog.nameOf("0.0.43.1.2.255"))
            assertEquals("Время прерывания питания", ObisCatalog.nameOf("0.0.96.50.26.255"))
            assertEquals("Время восстановления питания", ObisCatalog.nameOf("0.0.96.50.31.255"))
            assertEquals("Статус отсутствия напряжений", ObisCatalog.nameOf("1.0.96.10.0.255"))
            assertEquals("Состояние дискр. выходов", ObisCatalog.nameOf("1.0.96.4.3.255"))
            assertEquals("Статус учета энергии", ObisCatalog.nameOf("1.0.96.5.1.255"))
            assertEquals("Отображение фаз ДД", ObisCatalog.nameOf("1.0.96.50.0.255"))
        }

        @Test
        fun data_algorithmsAndTransformers() {
            assertEquals("Алгоритм расчета акт. мощн.", ObisCatalog.nameOf("1.0.0.11.1.255"))
            assertEquals("Алгоритм расчета акт. энерг.", ObisCatalog.nameOf("1.0.0.11.2.255"))
            assertEquals("Алгоритм расчета реакт. мощн.", ObisCatalog.nameOf("1.0.0.11.3.255"))
            assertEquals("Алгоритм расчета реакт. энерг.", ObisCatalog.nameOf("1.0.0.11.4.255"))
            assertEquals("Алгоритм расчета полной. мощн.", ObisCatalog.nameOf("1.0.0.11.5.255"))
            assertEquals("Алгоритм расчета полной энерг.", ObisCatalog.nameOf("1.0.0.11.6.255"))
            assertEquals("Алгоритм расчета Cos(Ф)", ObisCatalog.nameOf("1.0.0.11.7.255"))
            assertEquals("К-ент трансф. по току", ObisCatalog.nameOf("1.0.0.4.2.255"))
            assertEquals("К-ент трансф. по напряжению", ObisCatalog.nameOf("1.0.0.4.3.255"))
            assertEquals("Время усредн. пиковой мощности", ObisCatalog.nameOf("1.0.0.8.0.255"))
            assertEquals("Период профиля 1", ObisCatalog.nameOf("1.0.0.8.4.255"))
            assertEquals("Период профиля 2", ObisCatalog.nameOf("1.0.0.8.5.255"))
        }

        // Profile
        @Test
        fun profile_indicationAndJournals() {
            assertEquals("Автоиндикация", ObisCatalog.nameOf("0.0.21.0.1.255"))
            assertEquals("Возможности индикации", ObisCatalog.nameOf("0.0.21.0.170.255"))
            assertEquals("Индикация", ObisCatalog.nameOf("0.0.21.0.2.255"))
            assertEquals("Журнал событий по напряжению", ObisCatalog.nameOf("0.0.99.98.0.255"))
            assertEquals("Журнал событий по току", ObisCatalog.nameOf("0.0.99.98.1.255"))
            assertEquals("Журнал включений-выключений", ObisCatalog.nameOf("0.0.99.98.2.255"))
            assertEquals("Журнал коррекций", ObisCatalog.nameOf("0.0.99.98.3.255"))
            assertEquals("Журнал внешних воздействий", ObisCatalog.nameOf("0.0.99.98.4.255"))
            assertEquals("Журнал подключений", ObisCatalog.nameOf("0.0.99.98.5.255"))
            assertEquals("Журнал несанкционированного доступа", ObisCatalog.nameOf("0.0.99.98.6.255"))
            assertEquals("Журнал самодиагностики", ObisCatalog.nameOf("0.0.99.98.7.255"))
            assertEquals("Журнал событий по Tg(Ф)", ObisCatalog.nameOf("0.0.99.98.8.255"))
            assertEquals("Журнал ПКЭ", ObisCatalog.nameOf("0.0.99.98.9.255"))
            assertEquals("Журнал радиопоиска", ObisCatalog.nameOf("0.0.99.98.11.255"))
            assertEquals("Журнал контроля Tg(Ф)", ObisCatalog.nameOf("0.0.99.98.12.255"))
            assertEquals("Журнал установки времени", ObisCatalog.nameOf("0.0.99.98.13.255"))
            assertEquals("Годовой журнал", ObisCatalog.nameOf("0.0.99.98.14.255"))
            assertEquals("Журнал ПКЭ на РДЧ", ObisCatalog.nameOf("0.0.99.98.15.255"))
            assertEquals("Журнал контроля мощности", ObisCatalog.nameOf("0.0.99.98.16.255"))
            assertEquals("Журнал блокировки реле", ObisCatalog.nameOf("0.0.99.98.18.255"))
            assertEquals("Для разработчиков", ObisCatalog.nameOf("0.0.99.98.170.255"))
        }

        @Test
        fun profile_journalDescriptionsAndSizes() {
            assertEquals("Журнал стоп-кадра", ObisCatalog.nameOf("1.0.94.7.0.255"))
            assertEquals("Размерность месячного журнала", ObisCatalog.nameOf("1.0.94.7.1.255"))
            assertEquals("Размерность суточного журнала", ObisCatalog.nameOf("1.0.94.7.2.255"))
            assertEquals("Размерность журнала стоп-кадра", ObisCatalog.nameOf("1.0.94.7.3.255"))
            assertEquals("Размерность профилей", ObisCatalog.nameOf("1.0.94.7.4.255"))
            assertEquals("Размерность журнала напряжений", ObisCatalog.nameOf("1.0.94.7.5.255"))
            assertEquals("Описание журнала событий по напряж.", ObisCatalog.nameOf("1.0.94.7.10.255"))
            assertEquals("Описание журнала событий по току", ObisCatalog.nameOf("1.0.94.7.11.255"))
            assertEquals("Описание журнала вкл.-выкл.", ObisCatalog.nameOf("1.0.94.7.12.255"))
            assertEquals("Описание журнала коррекций", ObisCatalog.nameOf("1.0.94.7.13.255"))
            assertEquals("Описание журнала внеш. возд.", ObisCatalog.nameOf("1.0.94.7.14.255"))
            assertEquals("Описание журнала подключений", ObisCatalog.nameOf("1.0.94.7.15.255"))
            assertEquals("Описание журнала несанкц. доступа", ObisCatalog.nameOf("1.0.94.7.16.255"))
            assertEquals("Описание журнала самодиагностики", ObisCatalog.nameOf("1.0.94.7.17.255"))
            assertEquals("Описание журнала событий по Tg(Ф)", ObisCatalog.nameOf("1.0.94.7.18.255"))
            assertEquals("Месячный журнал", ObisCatalog.nameOf("1.0.98.1.0.255"))
            assertEquals("Суточный журнал", ObisCatalog.nameOf("1.0.98.2.0.255"))
            assertEquals("Профиль нагрузки №1", ObisCatalog.nameOf("1.0.99.1.0.255"))
            assertEquals("Профиль нагрузки №2", ObisCatalog.nameOf("1.0.99.2.0.255"))
            assertEquals("Профиль нагрузки №3", ObisCatalog.nameOf("1.0.99.3.0.255"))
        }

        //Limiter
        @Test
        fun limiter_allLimits() {
            assertEquals("Лимит по мощности", ObisCatalog.nameOf("0.0.17.0.0.255"))
            assertEquals("Ограничение по макс. току", ObisCatalog.nameOf("0.0.17.0.1.255"))
            assertEquals("Ограничение по макс. напряжению", ObisCatalog.nameOf("0.0.17.0.2.255"))
            assertEquals("Размыкание по магн. полю", ObisCatalog.nameOf("0.0.17.0.3.255"))
            assertEquals("Размыкание по разбалансу", ObisCatalog.nameOf("0.0.17.0.4.255"))
            assertEquals("Размыкание по температуре", ObisCatalog.nameOf("0.0.17.0.5.255"))
            assertEquals("Размыкание по пломбе", ObisCatalog.nameOf("0.0.17.0.6.255"))
        }

        // Association LN
        @Test
        fun association_allAccessLevels() {
            assertEquals("Текущая ассоциация", ObisCatalog.nameOf("0.0.40.0.0.255"))
            assertEquals("Ассоциация минимальный доступ", ObisCatalog.nameOf("0.0.40.0.1.255"))
            assertEquals("Ассоциация низкий доступ", ObisCatalog.nameOf("0.0.40.0.2.255"))
            assertEquals("Ассоциация высокий доступ", ObisCatalog.nameOf("0.0.40.0.3.255"))
        }

        // Hdlc
        @Test
        fun hdlc_allPorts() {
            assertEquals("Hdlc P1", ObisCatalog.nameOf("0.0.22.0.0.255"))
            assertEquals("Hdlc P2", ObisCatalog.nameOf("0.1.22.0.0.255"))
            assertEquals("Hdlc P3", ObisCatalog.nameOf("0.2.22.0.0.255"))
            assertEquals("Hdlc P3", ObisCatalog.nameOf("0.3.22.0.0.255"))
        }

        // Script Table
        @Test
        fun scriptTable_allScripts() {
            assertEquals("Скрипты РДЧ", ObisCatalog.nameOf("0.0.10.0.1.255"))
            assertEquals("Скрипты тарифного расписания", ObisCatalog.nameOf("0.0.10.0.100.255"))
            assertEquals("Скрипты управления размыкателем", ObisCatalog.nameOf("0.0.10.0.106.255"))
            assertEquals("Скрипты стоп-кадра", ObisCatalog.nameOf("0.0.10.1.1.255"))
        }

        //  Push interface
        @Test
        fun pushInterface_allChannels() {
            assertEquals("Push 1", ObisCatalog.nameOf("0.0.25.9.0.255"))
            assertEquals("Push 2", ObisCatalog.nameOf("0.1.25.9.0.255"))
            assertEquals("Push 3", ObisCatalog.nameOf("0.2.25.9.0.255"))
        }

        // Security setup
        @Test
        fun security_allLevels() {
            assertEquals("Безопасность минимальный уровень", ObisCatalog.nameOf("0.0.43.0.0.255"))
            assertEquals("Безопасность низкий уровень", ObisCatalog.nameOf("0.0.43.0.1.255"))
            assertEquals("Безопасность высокий уровень", ObisCatalog.nameOf("0.0.43.0.2.255"))
        }

        // Action Schedule / Activity Calendar / Clock / Demand / Disconnect / Extended
        @Test
        fun other_singleObjects() {
            assertEquals("РДЧ", ObisCatalog.nameOf("0.0.15.0.0.255"))
            assertEquals("Задание на стоп-кадр", ObisCatalog.nameOf("0.0.15.1.0.255"))
            assertEquals("Тарифное расписание", ObisCatalog.nameOf("0.0.13.0.0.255"))
            assertEquals("Время", ObisCatalog.nameOf("0.0.1.0.0.255"))
            assertEquals("Расчет пиковой мощности", ObisCatalog.nameOf("1.0.1.4.0.255"))
            assertEquals("Пиковая мощность", ObisCatalog.nameOf("1.0.1.6.0.255"))
            assertEquals("Размыкатель", ObisCatalog.nameOf("0.0.96.3.10.255"))
            assertEquals("Перепрошивка ПО", ObisCatalog.nameOf("0.0.44.0.0.255"))
            assertEquals("Оптропорт", ObisCatalog.nameOf("0.0.20.0.0.255"))
            assertEquals("Действующие тарифы", ObisCatalog.nameOf("0.0.14.0.0.255"))
            assertEquals("Особые дни", ObisCatalog.nameOf("0.0.11.0.0.255"))
        }

        // Проверка перекрытия (прошивка пульта)
        @Test
        fun firmwareOverrides_clockNames() {
            // В конце файла ключи 0.0.0.9.1.255 и 0.0.0.9.2.255 переопределены
            assertEquals("Время прибора", ObisCatalog.nameOf("0.0.0.9.1.255"))
            assertEquals("Дата прибора", ObisCatalog.nameOf("0.0.0.9.2.255"))
        }

        // Проверка «сам себя» (заглушки с именем = OBIS)
        @Test
        fun selfNamedEntries_returnTheirOwnCode() {
            // Некоторые записи в карте имеют имя, совпадающее с кодом (заглушки)
            assertEquals("0.0.175.0.0.255", ObisCatalog.nameOf("0.0.175.0.0.255"))
            assertEquals("0.0.176.0.0.255", ObisCatalog.nameOf("0.0.176.0.0.255"))
            assertEquals("1.0.0.6.101.255", ObisCatalog.nameOf("1.0.0.6.101.255"))
            assertEquals("1.0.0.6.102.255", ObisCatalog.nameOf("1.0.0.6.102.255"))
            assertEquals("1.0.10.29.0.255", ObisCatalog.nameOf("1.0.10.29.0.255"))
            assertEquals("1.0.144.7.0.255", ObisCatalog.nameOf("1.0.144.7.0.255"))
            assertEquals("1.0.146.7.0.255", ObisCatalog.nameOf("1.0.146.7.0.255"))
            assertEquals("1.0.9.8.0.255", ObisCatalog.nameOf("1.0.9.8.0.255"))
            assertEquals("1.0.9.29.0.255", ObisCatalog.nameOf("1.0.9.29.0.255"))
            assertEquals("0.0.0.2.166.255", ObisCatalog.nameOf("0.0.0.2.166.255"))
            assertEquals("0.0.0.2.167.255", ObisCatalog.nameOf("0.0.0.2.167.255"))
            assertEquals("0.0.170.0.0.255", ObisCatalog.nameOf("0.0.170.0.0.255"))
            assertEquals("0.0.171.0.0.255", ObisCatalog.nameOf("0.0.171.0.0.255"))
            assertEquals("0.0.172.0.0.255", ObisCatalog.nameOf("0.0.172.0.0.255"))
            assertEquals("0.0.173.0.0.255", ObisCatalog.nameOf("0.0.173.0.0.255"))
            assertEquals("0.0.174.0.0.255", ObisCatalog.nameOf("0.0.174.0.0.255"))
            assertEquals("0.0.96.1.165.255", ObisCatalog.nameOf("0.0.96.1.165.255"))
            assertEquals("0.0.96.5.133.255", ObisCatalog.nameOf("0.0.96.5.133.255"))
            assertEquals("0.1.172.0.0.255", ObisCatalog.nameOf("0.1.172.0.0.255"))
            assertEquals("0.1.173.0.0.255", ObisCatalog.nameOf("0.1.173.0.0.255"))
            assertEquals("0.1.174.0.0.255", ObisCatalog.nameOf("0.1.174.0.0.255"))
            assertEquals("0.2.172.0.0.255", ObisCatalog.nameOf("0.2.172.0.0.255"))
            assertEquals("0.2.174.0.0.255", ObisCatalog.nameOf("0.2.174.0.0.255"))
            assertEquals("0.3.172.0.0.255", ObisCatalog.nameOf("0.3.172.0.0.255"))
            assertEquals("0.3.174.0.0.255", ObisCatalog.nameOf("0.3.174.0.0.255"))
            assertEquals("0.0.99.98.128.255", ObisCatalog.nameOf("0.0.99.98.128.255"))
            assertEquals("0.0.99.98.129.255", ObisCatalog.nameOf("0.0.99.98.129.255"))
            assertEquals("1.0.94.7.128.255", ObisCatalog.nameOf("1.0.94.7.128.255"))
            assertEquals("1.0.94.7.129.255", ObisCatalog.nameOf("1.0.94.7.129.255"))
            assertEquals("1.0.98.2.128.255", ObisCatalog.nameOf("1.0.98.2.128.255"))
            assertEquals("1.0.29.8.0.255", ObisCatalog.nameOf("1.0.29.8.0.255"))
            assertEquals("1.0.30.8.0.255", ObisCatalog.nameOf("1.0.30.8.0.255"))
            assertEquals("1.0.34.7.0.255", ObisCatalog.nameOf("1.0.34.7.0.255"))
            assertEquals("1.0.50.8.0.255", ObisCatalog.nameOf("1.0.50.8.0.255"))
            assertEquals("1.0.54.7.0.255", ObisCatalog.nameOf("1.0.54.7.0.255"))
            assertEquals("1.0.70.8.0.255", ObisCatalog.nameOf("1.0.70.8.0.255"))
            assertEquals("1.0.74.7.0.255", ObisCatalog.nameOf("1.0.74.7.0.255"))
            assertEquals("1.0.124.128.0.255", ObisCatalog.nameOf("1.0.124.128.0.255"))
            assertEquals("1.0.124.129.0.255", ObisCatalog.nameOf("1.0.124.129.0.255"))
            assertEquals("1.0.125.128.0.255", ObisCatalog.nameOf("1.0.125.128.0.255"))
            assertEquals("1.0.125.129.0.255", ObisCatalog.nameOf("1.0.125.129.0.255"))
            assertEquals("1.0.126.128.0.255", ObisCatalog.nameOf("1.0.126.128.0.255"))
            assertEquals("1.0.126.129.0.255", ObisCatalog.nameOf("1.0.126.129.0.255"))
            assertEquals("1.0.128.8.0.255", ObisCatalog.nameOf("1.0.128.8.0.255"))
            assertEquals("1.0.129.8.0.255", ObisCatalog.nameOf("1.0.129.8.0.255"))
            assertEquals("1.0.14.7.164.255", ObisCatalog.nameOf("1.0.14.7.164.255"))
            assertEquals("1.0.148.8.0.255", ObisCatalog.nameOf("1.0.148.8.0.255"))
            assertEquals("1.0.149.8.0.255", ObisCatalog.nameOf("1.0.149.8.0.255"))
            assertEquals("1.0.9.8.0.101", ObisCatalog.nameOf("1.0.9.8.0.101"))
            assertEquals("1.0.9.8.1.101", ObisCatalog.nameOf("1.0.9.8.1.101"))
            assertEquals("1.0.9.8.2.101", ObisCatalog.nameOf("1.0.9.8.2.101"))
            assertEquals("1.0.9.8.3.101", ObisCatalog.nameOf("1.0.9.8.3.101"))
            assertEquals("1.0.9.8.4.101", ObisCatalog.nameOf("1.0.9.8.4.101"))
            assertEquals("1.0.9.8.5.101", ObisCatalog.nameOf("1.0.9.8.5.101"))
            assertEquals("1.0.9.8.6.101", ObisCatalog.nameOf("1.0.9.8.6.101"))
            assertEquals("1.0.9.8.7.101", ObisCatalog.nameOf("1.0.9.8.7.101"))
            assertEquals("1.0.9.8.8.101", ObisCatalog.nameOf("1.0.9.8.8.101"))
            assertEquals("1.0.10.8.0.101", ObisCatalog.nameOf("1.0.10.8.0.101"))
            assertEquals("1.0.10.8.1.101", ObisCatalog.nameOf("1.0.10.8.1.101"))
            assertEquals("1.0.10.8.2.101", ObisCatalog.nameOf("1.0.10.8.2.101"))
            assertEquals("1.0.10.8.3.101", ObisCatalog.nameOf("1.0.10.8.3.101"))
            assertEquals("1.0.10.8.4.101", ObisCatalog.nameOf("1.0.10.8.4.101"))
            assertEquals("1.0.10.8.5.101", ObisCatalog.nameOf("1.0.10.8.5.101"))
            assertEquals("1.0.10.8.6.101", ObisCatalog.nameOf("1.0.10.8.6.101"))
            assertEquals("1.0.10.8.7.101", ObisCatalog.nameOf("1.0.10.8.7.101"))
            assertEquals("1.0.10.8.8.101", ObisCatalog.nameOf("1.0.10.8.8.101"))
        }

        //  Проверка неизвестных ключей
        @Test
        fun unknownObis_returnsNull() {
            assertNull(ObisCatalog.nameOf("9.9.9.9.9.255"))
            assertNull(ObisCatalog.nameOf(""))
            assertNull(ObisCatalog.nameOf("garbage"))
        }

    //Мусор, который по хорошему он не деложен принимать?
    // Все равно должен, там в логи потом его
    @Test
    fun unknownCode_hasNoName_Next() {
        assertNull(ObisCatalog.nameOf("RiM"))
        // Если имени нет, caption возвращает сам код
        assertEquals("RiM", ObisCatalog.caption("RiM"))
    }

    @Test
    fun unknownCode_hasNoName_Null() {
        assertNull(ObisCatalog.nameOf(""))
        assertEquals("", ObisCatalog.caption(""))
    }

    @Test
    fun unknownCode_hasNoName_OnlyTab() {
        assertNull(ObisCatalog.nameOf("\t"))
        assertEquals("\t", ObisCatalog.caption("\t"))
    }

    @Test
    fun unknownCode_hasNoName_RusText() {
        val garbage = "рим!или не? рим:"
        assertNull(ObisCatalog.nameOf(garbage))
        assertEquals(garbage, ObisCatalog.caption(garbage))
    }

    // как caption работает с ИЗВЕСТНЫМ кодом:

    @Test
    fun allKnownCodes_captionIsCorrect_viaReflection() {
        val catalogClass = ObisCatalog::class.java
        val namesField = catalogClass.getDeclaredField("NAMES")
        namesField.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val names = namesField.get(ObisCatalog) as Map<String, String>
        var count = 0
        for ((code, name) in names) {
            assertEquals("$name · $code", ObisCatalog.caption(code))
            count++
        }
        println("Успешно проверено записей: $count")
    }


    }
