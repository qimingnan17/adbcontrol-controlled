package com.adbcontrol.controlled.di

import android.content.Context
import com.adbcontrol.controlled.apptime.AppTimeController
import com.adbcontrol.controlled.config.ConfigStore
import com.adbcontrol.controlled.config.PairingClient
import com.adbcontrol.controlled.config.PairingScanner
import com.adbcontrol.controlled.executor.CommandDispatcher
import com.adbcontrol.controlled.net.CommandHandler
import com.adbcontrol.controlled.net.MessageCodec
import com.adbcontrol.controlled.net.MqttManager
import com.adbcontrol.controlled.notification.ReminderNotificationCenter
import com.adbcontrol.controlled.storage.R2StorageClient
import com.adbcontrol.controlled.telemetry.ActivityReporter
import com.adbcontrol.controlled.telemetry.HealthReporter
import com.adbcontrol.controlled.telemetry.LocationReporter
import com.adbcontrol.controlled.telemetry.StatusReporter
import com.adbcontrol.controlled.telemetry.SystemInfoCollector
import com.adbcontrol.controlled.telemetry.TelemetryEngine
import com.adbcontrol.controlled.telemetry.UsageReporter
import com.adbcontrol.controlled.update.PlayAppUpdateChannel
import com.adbcontrol.controlled.update.SelfHostedUpdateChannel
import com.adbcontrol.controlled.update.UpdateChannel
import com.adbcontrol.controlled.executor.ShizukuExecutor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Provides @Singleton
    fun provideConfigStore(@ApplicationContext context: Context, json: Json): ConfigStore =
        ConfigStore(context, json)

    @Provides @Singleton
    fun provideMessageCodec(): MessageCodec = MessageCodec()

    @Provides @Singleton
    fun provideOkHttp(): OkHttpClient = PairingClient.defaultOkHttp()

    @Provides @Singleton
    fun provideMqttManager(
        @ApplicationContext context: Context,
        codec: MessageCodec,
        json: Json,
    ): MqttManager = MqttManager(context, codec, json)

    @Provides @Singleton
    fun provideCommandHandler(
        dispatcher: CommandDispatcher,
        mqttManager: MqttManager,
        json: Json,
        appTimeController: AppTimeController,
        notificationCenter: ReminderNotificationCenter,
    ): CommandHandler = CommandHandler(dispatcher, mqttManager, json, appTimeController, notificationCenter)

    // ---------- 遥测 ----------

    @Provides @Singleton
    fun provideSystemInfoCollector(@ApplicationContext context: Context): SystemInfoCollector =
        SystemInfoCollector(context)

    @Provides @Singleton
    fun provideStatusReporter(
        mqttManager: MqttManager,
        collector: SystemInfoCollector,
        json: Json,
    ): StatusReporter = StatusReporter(mqttManager, collector, json)

    @Provides @Singleton
    fun provideLocationReporter(
        @ApplicationContext context: Context,
        mqttManager: MqttManager,
        json: Json,
    ): LocationReporter = LocationReporter(context, mqttManager, json)

    @Provides @Singleton
    fun provideActivityReporter(
        @ApplicationContext context: Context,
        mqttManager: MqttManager,
        json: Json,
    ): ActivityReporter = ActivityReporter(context, mqttManager, json)

    @Provides @Singleton
    fun provideUsageReporter(
        @ApplicationContext context: Context,
        mqttManager: MqttManager,
        json: Json,
    ): UsageReporter = UsageReporter(context, mqttManager, json)

    @Provides @Singleton
    fun provideHealthReporter(
        @ApplicationContext context: Context,
        dispatcher: CommandDispatcher,
        collector: SystemInfoCollector,
        mqttManager: MqttManager,
        json: Json,
    ): HealthReporter = HealthReporter(
        context, dispatcher, collector,
        appVersion = appVersionName(context),
        mqttManager = mqttManager,
        json = json,
    )

    @Provides @Singleton
    fun provideTelemetryEngine(
        statusReporter: StatusReporter,
        locationReporter: LocationReporter,
        activityReporter: ActivityReporter,
        usageReporter: UsageReporter,
        healthReporter: HealthReporter,
    ): TelemetryEngine = TelemetryEngine(
        statusReporter, locationReporter, activityReporter, usageReporter, healthReporter
    )

    // ---------- 配对 / 更新 / 应用时间 ----------

    @Provides @Singleton
    fun providePairingScanner(@ApplicationContext context: Context): PairingScanner =
        PairingScanner.get(context)

    @Provides @Singleton
    fun providePairingClient(
        @ApplicationContext context: Context,
        httpClient: OkHttpClient,
        json: Json,
    ): PairingClient = PairingClient(context, httpClient, json)

    @Provides @Singleton
    fun provideAppTimeController(
        @ApplicationContext context: Context,
        dispatcher: CommandDispatcher,
        mqttManager: MqttManager,
    ): AppTimeController = AppTimeController(context, dispatcher, mqttManager)

    /**
     * 自建更新通道(默认实现)。Play 通道需要 Activity,仅在 UI 可用时构造,不在此默认提供。
     */
    @Provides @Singleton
    fun provideSelfHostedUpdateChannel(
        @ApplicationContext context: Context,
        httpClient: OkHttpClient,
        json: Json,
        shizukuExecutor: ShizukuExecutor,
    ): UpdateChannel = SelfHostedUpdateChannel(context, httpClient, json, shizukuExecutor)

    private fun appVersionName(context: Context): String =
        com.adbcontrol.controlled.ControlledApp.appVersionName(context)
}
