package ru.rim.dd.data.dlms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.rim.dd.testutil.DlmsFixtures
import ru.rim.dd.testutil.FakeNativeDlmsApi
import ru.rim.dd.testutil.RealMeterBuffer

/**
 * Протокольный уровень на настоящих байтах из лога прибора: GET буфера, склейка DLMS-блоков,
 * ассоциация AKROS, команды реле. Нативный клиент заменён фейком, железа не нужно.
 */
class DlmsSessionClientTest {

    private val native = FakeNativeDlmsApi()
    private val client = DlmsSessionClient(native)

    private val mainBuffer = "0.0.21.0.2.255"

    // ---------- GET буфера индикации, путь РиМ ----------

    @Test
    fun readMainBuffer_rim_decodesRealResponse_usingFlatAddressing() {
        native.onFlatGet = { _, _, _ -> RealMeterBuffer.rawResponse() }

        val result = client.readMainBuffer()

        assertTrue(result is MainBufferResult.Decoded)
        assertEquals(RealMeterBuffer.describe(RealMeterBuffer.readings()), RealMeterBuffer.describe((result as MainBufferResult.Decoded).readings))
        assertEquals(listOf("flat-get 7 $mainBuffer 2"), native.calls)
    }

    @Test
    fun readMainBuffer_assemblesDlmsBlocks_intoSameReadings() {
        val blocks = DlmsFixtures.splitIntoBlocks(RealMeterBuffer.rawResponse(), blockSize = 100)
        assertTrue("для проверки нужно несколько блоков", blocks.size >= 3)
        native.onFlatGet = { _, _, _ -> blocks[0] }
        native.onNextBlockFlat = { last -> blocks[last] }   // после блока N просят блок N+1 (индекс N)

        val result = client.readMainBuffer()

        assertTrue(result is MainBufferResult.Decoded)
        assertEquals(RealMeterBuffer.describe(RealMeterBuffer.readings()), RealMeterBuffer.describe((result as MainBufferResult.Decoded).readings))
        val nextRequests = native.calls.filter { it.startsWith("flat-next-block") }
        assertEquals((1 until blocks.size).map { "flat-next-block $it" }, nextRequests)
    }

    @Test
    fun readMainBuffer_requestNotSent_returnsNotSentWithNativeError() {
        native.flatGetSends = false
        native.error = "нет клиента"

        assertEquals(MainBufferResult.NotSent("нет клиента"), client.readMainBuffer())
    }

    @Test
    fun readMainBuffer_emptyResponse_returnsEmpty() {
        native.onFlatGet = { _, _, _ -> null }

        assertTrue(client.readMainBuffer() is MainBufferResult.Empty)
    }

    @Test
    fun readMainBuffer_garbageWithoutLlcHeader_isIncomplete() {
        native.onFlatGet = { _, _, _ -> byteArrayOf(1, 2, 3, 4, 5) }

        assertEquals(MainBufferResult.Incomplete, client.readMainBuffer())
    }

    @Test
    fun readMainBuffer_dataAccessError_isIncomplete() {
        native.onFlatGet = { _, _, _ -> DlmsFixtures.getResponseError(code = 0x0D) }

        assertEquals(MainBufferResult.Incomplete, client.readMainBuffer())
    }

    @Test
    fun readMainBuffer_blockChainBroken_isIncomplete() {
        val blocks = DlmsFixtures.splitIntoBlocks(RealMeterBuffer.rawResponse(), blockSize = 100)
        native.onFlatGet = { _, _, _ -> blocks[0] }
        native.onNextBlockFlat = { null }                     // второй блок не пришёл

        assertEquals(MainBufferResult.Incomplete, client.readMainBuffer())
        assertEquals(1, native.count("flat-next-block"))
    }

    @Test
    fun readMainBuffer_nextBlockRequestNotSent_isIncomplete() {
        val blocks = DlmsFixtures.splitIntoBlocks(RealMeterBuffer.rawResponse(), blockSize = 100)
        native.onFlatGet = { _, _, _ -> blocks[0] }
        native.nextBlockSends = false

        assertEquals(MainBufferResult.Incomplete, client.readMainBuffer())
    }

    @Test
    fun readMainBuffer_neverEndingBlocks_stopsAtLimitOf32() {
        // Прибор «забыл» поставить флаг последнего блока: обмен не должен длиться вечно.
        fun endlessBlock(n: Int) = DlmsFixtures.block(byteArrayOf(0x00), blockNumber = n, last = false)
        native.onFlatGet = { _, _, _ -> endlessBlock(1) }
        native.onNextBlockFlat = { last -> endlessBlock(last + 1) }

        assertEquals(MainBufferResult.Incomplete, client.readMainBuffer())
        assertEquals("первый блок + 31 запрос следующих = 32", 31, native.count("flat-next-block"))
    }

    // ---------- GET буфера, запасной путь AKROS ----------

    private fun prepareAkrosDevice() {
        native.onGet = DlmsFixtures.akrosGetResponder()
    }

    @Test
    fun establishAkrosSession_followsReferenceOrder_andReadsDescriptions() {
        prepareAkrosDevice()

        val error = client.establishAkrosSession()

        assertNull(error)
        assertEquals(
            listOf(
                "snrm 32/1/16",
                "ua",
                "aarq 1 Reader",
                "get 7 $mainBuffer 3",                 // capture_objects
                "get 3 1.0.1.8.0.255 3",               // scaler_unit каждой колонки
                "get 3 1.0.32.7.0.255 3",
            ),
            native.calls,
        )
    }

    @Test
    fun readMainBuffer_akros_appliesColumnsAndScalers() {
        prepareAkrosDevice()
        client.establishAkrosSession()
        client.akrosAssociation = true

        val result = client.readMainBuffer()

        assertTrue(result is MainBufferResult.Decoded)
        val readings = (result as MainBufferResult.Decoded).readings
        assertEquals(2, readings.size)
        assertEquals("1.0.1.8.0.255", readings[0].obisCode)
        assertEquals(12345.0, readings[0].value, 1e-6)
        assertEquals(30, readings[0].unit)
        assertEquals("1.0.32.7.0.255", readings[1].obisCode)
        assertEquals(230.1, readings[1].value, 1e-3)       // 2301 × 10^-1
        assertEquals("GET буфера идёт стандартной адресацией", 1, native.count("get 7 $mainBuffer 2"))
        assertEquals(0, native.count("flat-get"))
    }

    @Test
    fun establishAkrosSession_snrmNotFormed_returnsError() {
        native.snrmOk = false
        native.error = "плохой адрес"

        val error = client.establishAkrosSession()

        assertNotNull(error)
        assertTrue(error!!.contains("SNRM"))
        assertEquals(listOf("snrm 32/1/16"), native.calls.filter { it.startsWith("snrm") || it == "ua" })
        assertEquals(0, native.count("ua"))
    }

    @Test
    fun establishAkrosSession_deviceAnswersDm_returnsErrorAboutDm() {
        native.uaResult = 2

        val error = client.establishAkrosSession()

        assertTrue(error!!.contains("DM"))
        assertEquals(0, native.count("aarq"))
    }

    @Test
    fun establishAkrosSession_noUa_returnsTimeoutError() {
        native.uaResult = 0

        val error = client.establishAkrosSession()

        assertTrue(error!!.contains("UA"))
        assertEquals(0, native.count("aarq"))
    }

    @Test
    fun establishAkrosSession_aarqRefused_returnsError() {
        native.aarqOk = false

        val error = client.establishAkrosSession()

        assertTrue(error!!.contains("ассоциация не установлена"))
        assertEquals(0, native.count("get "))
    }

    @Test
    fun establishAkrosSession_captureObjectsFail_doesNotBreakSession() {
        // Описание буфера не пришло: сеанс жив, следующий GET повторит запрос capture_objects.
        native.onGet = { _, _, _ -> null }

        assertNull(client.establishAkrosSession())
    }

    @Test
    fun capturedDescriptionInBlocks_isAssembled() {
        // AKROS отвечает на capture_objects блоками по 128 байт — склейка обязана сработать.
        val captured = DlmsFixtures.getResponse(
            DlmsFixtures.array(List(14) { DlmsFixtures.captureObject(3, "1.0.${it + 1}.8.0.255") }),
        )
        val blocks = DlmsFixtures.splitIntoBlocks(captured, blockSize = 60)
        assertTrue(blocks.size > 1)
        native.onGet = { classId, _, attribute ->
            if (classId == 7 && attribute == 3) blocks[0] else null
        }
        native.onReadyToReceive = { last -> blocks[last] }

        client.establishAkrosSession()

        assertEquals((1 until blocks.size).map { "rr-block $it" }, native.calls.filter { it.startsWith("rr-block") })
        assertEquals("по запросу scaler_unit на каждую из 14 колонок", 14, native.calls.count { it.startsWith("get 3 ") })
    }

    // ---------- реле ----------

    @Test
    fun disconnectControlAction_success_whenActionResultIsZero() {
        native.onAction = { DlmsFixtures.actionResponse(result = 0) }

        assertTrue(client.disconnectControlAction(methodId = 2, label = "вкл"))
        assertEquals(listOf("action 70 0.0.96.3.10.255 method=2"), native.calls)
    }

    @Test
    fun disconnectControlAction_deviceReturnsError_isFailure() {
        native.onAction = { DlmsFixtures.actionResponse(result = 0x0D) }

        assertFalse(client.disconnectControlAction(1, "выкл"))
    }

    @Test
    fun disconnectControlAction_notSent_emptyOrGarbage_areFailures() {
        native.actionSends = false
        assertFalse(client.disconnectControlAction(1, "не ушёл"))

        native.actionSends = true
        native.onAction = { null }
        assertFalse(client.disconnectControlAction(1, "пустой ответ"))

        native.onAction = { byteArrayOf(1, 2, 3, 4) }
        assertFalse(client.disconnectControlAction(1, "мусор"))

        // GET-response вместо ACTION-response — тоже не успех
        native.onAction = { DlmsFixtures.getResponse(DlmsFixtures.i8(0)) }
        assertFalse(client.disconnectControlAction(1, "не тот сервис"))
    }

    // ---------- клиент ----------

    @Test
    fun recreateClient_destroysThenCreates() {
        client.createClient()
        client.recreateClient()

        assertEquals(listOf("create", "destroy", "create"), native.calls)
        assertTrue(native.clientAlive)
    }
}
