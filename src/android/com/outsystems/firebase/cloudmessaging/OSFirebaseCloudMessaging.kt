package com.outsystems.firebase.cloudmessaging

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.outsystems.plugins.firebasemessaging.controller.*
import com.outsystems.plugins.firebasemessaging.model.FirebaseMessagingError
import com.outsystems.plugins.firebasemessaging.model.database.DatabaseManager
import com.outsystems.plugins.firebasemessaging.model.database.DatabaseManagerInterface
import com.outsystems.plugins.oscordova.CordovaImplementation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaInterface
import org.apache.cordova.CordovaWebView
import org.apache.cordova.PluginResult
import org.apache.cordova.PluginResult.Status
import org.json.JSONArray
import org.json.JSONObject

class OSFirebaseCloudMessaging : CordovaImplementation() {

    override var callbackContext: CallbackContext? = null
    private lateinit var notificationManager : FirebaseNotificationManagerInterface
    private lateinit var messagingManager : FirebaseMessagingManagerInterface
    private lateinit var controller : FirebaseMessagingController
    private lateinit var databaseManager: DatabaseManagerInterface

    private var deviceReady: Boolean = false
    private val eventQueue: MutableList<String> = mutableListOf()

    private val gson: Gson = GsonBuilder().excludeFieldsWithoutExposeAnnotation().create()

    private var flow: MutableSharedFlow<OSFCMPermissionEvents>? = null

    companion object {
        private const val CHANNEL_NAME_KEY = "notification_channel_name"
        private const val CHANNEL_DESCRIPTION_KEY = "notification_channel_description"
        private const val ERROR_FORMAT_PREFIX = "OS-PLUG-FCMS-"
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 123123
        const val FCM_EXPLICIT_NOTIFICATION = "com.outsystems.fcm.notification"
        const val GOOGLE_MESSAGE_ID = "google.message_id"
    }

    override fun initialize(cordova: CordovaInterface, webView: CordovaWebView) {
        super.initialize(cordova, webView)
        databaseManager = DatabaseManager.getInstance(getActivity())
        notificationManager = FirebaseNotificationManager(getActivity(), databaseManager)
        messagingManager = FirebaseMessagingManager()
        controller = FirebaseMessagingController(controllerDelegate, messagingManager, notificationManager)

        setupChannelNameAndDescription()

        val intent = getActivity().intent
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume(multitasking: Boolean) {
        // Not used in this project.
    }

    private fun handleIntent(intent: Intent) {
        val extras = intent.extras
        val extrasSize = extras?.size() ?: 0

        // Check if intent comes from an FCM notification. If not, we don't want to handle it
        // This is necessary so that we don't mistakenly deal with deep links thinking they're FCM notification clicks
        val googleMessageId = extras?.getString(GOOGLE_MESSAGE_ID) // for notifications automatically delivered by the FCM SDK
        val fcmInternal = extras?.getString(FCM_EXPLICIT_NOTIFICATION) // for notifications that we explicitly deliver in the FCM plugin

        if (googleMessageId.isNullOrEmpty() && fcmInternal.isNullOrEmpty()) {
            return
        }

        if(extrasSize > 0) {
            val scheme = extras.getString(FirebaseMessagingOnActionClickActivity.ACTION_DEEP_LINK_SCHEME)
            if (scheme.isNullOrEmpty()) {
                FirebaseMessagingOnClickActivity.notifyClickNotification(intent)
            }
            else {
                FirebaseMessagingOnActionClickActivity.notifyClickAction(intent)
            }
        }
    }

    private val controllerDelegate = object: FirebaseMessagingInterface {
        override fun callback(result: String) {
            sendPluginResult(result)
        }
        override fun callbackNotifyApp(event: String, result: String) {
            val js = "cordova.plugins.OSFirebaseCloudMessaging.fireEvent(" +
                    "\"" + event + "\"," + result + ");"
            if(deviceReady) {
                triggerEvent(js)
            }
            else {
                eventQueue.add(js)
            }
        }
        override fun callbackSuccess() {
            sendPluginResult(true)
        }
        override fun callbackBadgeNumber(number: Int) {
            //Does nothing on android
        }
        override fun callbackError(error: FirebaseMessagingError) {
            sendPluginResult(null, Pair(formatErrorCode(error.code), error.description))
        }
    }

    private fun ready() {
        deviceReady = true
        eventQueue.forEach { event ->
            triggerEvent(event)
        }
        eventQueue.clear()
    }

    override fun execute(
        action: String,
        args: JSONArray,
        callbackContext: CallbackContext
    ): Boolean {
        CoroutineScope(IO).launch {
            when (action) {
                "ready" -> {
                    ready()
                }

                "getToken" -> {
                    getToken(callbackContext)
                }

                "subscribe" -> {
                    args.getString(0)?.let {
                        topicOperation(
                            callbackContext,
                            operation = {
                                controller.subscribe(it)
                            },
                            FirebaseMessagingError.SUBSCRIPTION_ERROR
                        )
                    }
                }

                "unsubscribe" -> {
                    args.getString(0)?.let {
                        topicOperation(
                            callbackContext,
                            operation = {
                                controller.unsubscribe(it)
                            },
                            FirebaseMessagingError.UNSUBSCRIPTION_ERROR
                        )
                    }
                }

                "registerDevice" -> {
                    registerDevice(callbackContext)
                }

                "unregisterDevice" -> {
                    unregisterDevice(callbackContext)
                }

                "clearNotifications" -> {
                    clearNotifications(callbackContext)
                }

                "sendLocalNotification" -> {
                    sendLocalNotification(args, callbackContext)
                }

                "getPendingNotifications" -> {
                    val clearFromDatabase = args.getBoolean(0)
                    getPendingNotifications(clearFromDatabase, callbackContext)
                }

                "setDeliveryMetricsExportToBigQuery" -> {
                    args.getJSONObject(0).getBoolean("enable").let {
                        setDeliveryMetricsExportToBigQuery(it, callbackContext)
                    }
                }

                "deliveryMetricsExportToBigQueryEnabled" -> {
                    deliveryMetricsExportToBigQueryEnabled(callbackContext)
                }

                // non available methods
                "setBadge" -> {
                    sendError(callbackContext, FirebaseMessagingError.SET_BADGE_NOT_AVAILABLE_ERROR)
                }

                "getBadge" -> {
                    sendError(callbackContext, FirebaseMessagingError.GET_BADGE_NOT_AVAILABLE_ERROR)
                }

                "getAPNsToken" -> {
                    sendError(callbackContext, FirebaseMessagingError.GET_APNS_TOKEN_NOT_AVAILABLE_ERROR)
                }
            }
        }
        return true
    }

    private suspend fun getToken(callbackContext: CallbackContext) {
        controller.getToken()?.let {
            sendSuccess(callbackContext, it)
        } ?: sendError(callbackContext, FirebaseMessagingError.OBTAINING_TOKEN_ERROR)
    }

    private suspend fun topicOperation(callbackContext: CallbackContext, operation: suspend () -> Boolean, error: FirebaseMessagingError) {
        if (operation()) {
            sendSuccess(callbackContext)
        } else {
            sendError(callbackContext, error)
        }
    }

    private fun getPendingNotifications(clearFromDatabase: Boolean, callbackContext: CallbackContext) {
        val errorCallback: () -> Unit = {
            sendError(callbackContext, FirebaseMessagingError.GET_PENDING_NOTIFICATIONS_ERROR)
        }

        val pendingNotificationNullableList = controller.getPendingNotifications(clearFromDatabase)
        pendingNotificationNullableList?.let { pendingNotificationList ->
            gson.toJson(pendingNotificationList)?.let { jsonString ->
                sendSuccess(callbackContext, jsonString)
            } ?: errorCallback()
        } ?: errorCallback()
    }

    override fun onRequestPermissionResult(requestCode: Int,
                                           permissions: Array<String>,
                                           grantResults: IntArray) {
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            CoroutineScope(IO).launch {
                flow?.emit(
                    if (grantResults.indexOfFirst { it != PackageManager.PERMISSION_GRANTED } == -1)
                        OSFCMPermissionEvents.Granted
                    else OSFCMPermissionEvents.NotGranted
                )
            }
        }
    }

    override fun areGooglePlayServicesAvailable(): Boolean {
        // Not used in this project.
        return false
    }

    private suspend fun registerDevice(callbackContext: CallbackContext) {
        flow = MutableSharedFlow(replay = 1)

        // for build versions from Tiramisu, if it doesn't have permission, request it
        // for older build versions, the permission is given by default
        val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkPermission(Manifest.permission.POST_NOTIFICATIONS)
        if (hasPermission) {
            flow?.emit(OSFCMPermissionEvents.Granted)
        } else {
            requestPermission(NOTIFICATION_PERMISSION_REQUEST_CODE, Manifest.permission.POST_NOTIFICATIONS)
        }

        flow?.collect {
            if (it == OSFCMPermissionEvents.Granted) {
                if (controller.registerDevice()) {
                    sendSuccess(callbackContext)
                } else {
                    sendError(callbackContext, FirebaseMessagingError.REGISTRATION_ERROR)
                }
            } else {
                sendError(callbackContext,  FirebaseMessagingError.NOTIFICATIONS_PERMISSIONS_DENIED_ERROR)
            }
        }
    }

    private suspend fun unregisterDevice(callbackContext: CallbackContext) {
        if (controller.unregisterDevice()) {
            sendSuccess(callbackContext)
        } else {
            sendError(callbackContext, FirebaseMessagingError.UNREGISTRATION_ERROR)
        }
    }

    private fun sendLocalNotification(args: JSONArray, callbackContext: CallbackContext) {
        val badge = args.get(0).toString().toInt()
        val title = args.get(1).toString()
        val text = args.get(2).toString()
        val channelName = args.get(3).toString()
        val channelDescription = args.get(4).toString()

        val result = controller.sendLocalNotification(badge, title, text, null, channelName, channelDescription)
        if (result.first) {
            sendSuccess(callbackContext)
        } else {
            result.second?.let {
                sendError(callbackContext, it)
            }
        }
    }

    private fun clearNotifications(callbackContext: CallbackContext) {
        if (controller.clearNotifications()) {
            sendSuccess(callbackContext)
        } else {
            sendError(callbackContext, FirebaseMessagingError.CLEARING_NOTIFICATIONS_ERROR)
        }
    }

    private fun setDeliveryMetricsExportToBigQuery(enable: Boolean, callbackContext: CallbackContext) {
        controller.setDeliveryMetricsExportToBigQuery(enable)
        sendSuccess(callbackContext)
    }

    private fun deliveryMetricsExportToBigQueryEnabled(callbackContext: CallbackContext) {
        sendSuccess(callbackContext, controller.deliveryMetricsExportToBigQueryEnabled().toString())
    }

    private fun setupChannelNameAndDescription(){
        val channelName = getActivity().getString(getStringResourceId("notification_channel_name"))
        val channelDescription = getActivity().getString(getStringResourceId("notification_channel_description"))

        if(!channelName.isNullOrEmpty()){
            val editorName = getActivity().getSharedPreferences(CHANNEL_NAME_KEY, Context.MODE_PRIVATE).edit()
            editorName.putString(CHANNEL_NAME_KEY, channelName)
            editorName.apply()
        }
        if(!channelDescription.isNullOrEmpty()){
            val editorDescription = getActivity().getSharedPreferences(CHANNEL_DESCRIPTION_KEY, Context.MODE_PRIVATE).edit()
            editorDescription.putString(CHANNEL_DESCRIPTION_KEY, channelDescription)
            editorDescription.apply()
        }
    }

    private fun getStringResourceId(typeAndName: String): Int {
        return getActivity().resources.getIdentifier(typeAndName, "string", getActivity().packageName)
    }

    private fun formatErrorCode(code: Int): String {
        return ERROR_FORMAT_PREFIX + code.toString().padStart(4, '0')
    }

    private fun sendSuccess(callbackContext: CallbackContext, stringValue: String? = null) {
        val pluginResult = stringValue?.let { PluginResult(Status.OK, it) } ?: PluginResult(Status.OK)
        callbackContext.sendPluginResult(pluginResult)
    }

    private fun sendError(callbackContext: CallbackContext, error: FirebaseMessagingError) {
        val pluginResult = PluginResult(
            Status.ERROR,
            JSONObject().apply {
                put("code", formatErrorCode(error.code))
                put("message", error.description)
            }
        )
        callbackContext.sendPluginResult(pluginResult)
    }
}