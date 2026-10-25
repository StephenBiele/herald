package dev.hermeskotlin.ui.settings

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.phone.PhoneTool
import dev.hermeskotlin.core.phone.PhoneToolRunner
import dev.hermeskotlin.core.phone.PhoneToolsBridge
import dev.hermeskotlin.core.push.PushApi
import dev.hermeskotlin.core.push.PushGateway
import dev.hermeskotlin.core.push.PushKeys
import dev.hermeskotlin.core.push.PushRegistration
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneToolsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun messagesCanBeEnabledAndRetentionIsRemembered() = runTest(dispatcher) {
        val disk = InMemoryKeyValueStore()
        val settings = SettingsStore(disk, backgroundScope)
        assertFalse(PhoneTool.Messages.id in settings.settings.filterNotNull().first().phoneToolsOn)

        val (bridge, client) = testBridge(settings, backgroundScope)
        try {
            val viewModel = PhoneToolsViewModel(settings, bridge)
            viewModel.setTool(PhoneTool.Messages, true)
            viewModel.setMessageRetentionDays(2)
            advanceUntilIdle()

            val restored = SettingsStore(disk, backgroundScope).settings.filterNotNull().first()
            assertTrue(PhoneTool.Messages.id in restored.phoneToolsOn)
            assertEquals(2, restored.messageRetentionDays)

            viewModel.setTool(PhoneTool.Messages, false)
            advanceUntilIdle()
            val disabled = SettingsStore(disk, backgroundScope).settings.filterNotNull().first()
            assertFalse(PhoneTool.Messages.id in disabled.phoneToolsOn)
            assertEquals(2, disabled.messageRetentionDays)
        } finally {
            client.close()
        }
    }

    private fun testBridge(settings: SettingsStore, scope: CoroutineScope): Pair<PhoneToolsBridge, HttpClient> {
        val storage = InMemoryKeyValueStore()
        val cookies = PersistentCookiesStorage(storage)
        val client = createHttpClient(MockEngine { error("Unexpected HTTP request") }, cookies)
        val connection = GatewayConnection(AuthApi(client, cookies), { _, _ -> error("Unexpected socket connection") }, scope)
        val bridge = PhoneToolsBridge(
            api = PushApi(connection),
            keys = TestKeys,
            store = storage,
            connection = connection,
            gateways = GatewayRepository(storage),
            settings = settings,
            runner = object : PhoneToolRunner {
                override suspend fun run(tool: PhoneTool, args: JsonObject, session: String): JsonElement =
                    error("Phone tools are not run in this test")
            },
            scope = scope,
            deviceName = { "Test phone" },
        )
        return bridge to client
    }

    private object TestKeys : PushKeys {
        override val deviceId = "test-phone"
        override fun registration(name: String): PushRegistration = error("Unused")
        override fun pinned(gatewayUrl: String): PushGateway? = null
        override fun accepts(keys: PushGateway): Boolean = false
        override fun pin(gatewayUrl: String, keys: PushGateway) = Unit
        override fun retire(gatewayUrl: String) = Unit
        override fun retired(gatewayUrl: String): Set<String> = emptySet()
        override fun forgetRetired(gatewayUrl: String, deviceId: String) = Unit
        override fun pinnedGatewayUrl(): String? = null
    }
}
