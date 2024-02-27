package com.outsystems.firebase.cloudmessaging;

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.PermissionChecker.PERMISSION_GRANTED
import androidx.core.content.PermissionChecker.PermissionResult
import com.outsystems.osnotificationpermissions.OSNotificationPermissions
import com.outsystems.plugins.firebasemessaging.controller.*
import com.outsystems.plugins.firebasemessaging.model.FirebaseMessagingError
import com.outsystems.plugins.firebasemessaging.model.database.DatabaseManager
import com.outsystems.plugins.firebasemessaging.model.database.DatabaseManagerInterface
import com.outsystems.plugins.oscordova.CordovaImplementation
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.IO
import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaInterface
import org.apache.cordova.CordovaWebView
import org.json.JSONArray


class OSFirebaseCloudMessaging : CordovaImplementation() {

    override var callbackContext: CallbackContext? = null
    private lateinit var notificationManager : FirebaseNotificationManagerInterface
    private lateinit var messagingManager : FirebaseMessagingManagerInterface
    private lateinit var controller : FirebaseMessagingController
    private lateinit var databaseManager: DatabaseManagerInterface

    private var deviceReady: Boolean = false
    private val eventQueue: MutableList<String> = mutableListOf()
    private var notificationPermission = OSNotificationPermissions()

    companion object {
        private const val CHANNEL_NAME_KEY = "notification_channel_name"
        private const val CHANNEL_DESCRIPTION_KEY = "notification_channel_description"
        private const val ERROR_FORMAT_PREFIX = "OS-PLUG-FCMS-"
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 123123
        private const val NOTIFICATION_PERMISSION_SEND_LOCAL_REQUEST_CODE = 987987
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

        if(Build.VERSION.SDK_INT >= 33 &&
            !notificationPermission.hasNotificationPermission(this)) {

            notificationPermission.requestNotificationPermission(
                this,
                NOTIFICATION_PERMISSION_SEND_LOCAL_REQUEST_CODE)
        }

    }

    override fun execute(action: String, args: JSONArray, callbackContext: CallbackContext): Boolean {
        this.callbackContext = callbackContext
        val result = runBlocking {
            when (action) {
                "ready" -> {
                    ready()
                }
                "getToken" -> {
                    controller.getToken()
                }
                "subscribe" -> {
                    args.getString(0)?.let { topic ->
                        controller.subscribe(topic)
                    }
                }
                "unsubscribe" -> {
                    args.getString(0)?.let { topic ->
                        controller.unsubscribe(topic)
                    }
                }
                "registerDevice" -> {
                    registerWithPermission()
                }
                "unregisterDevice" -> {
                    controller.unregisterDevice()
                }
                "clearNotifications" -> {
                    clearNotifications()
                }
                "sendLocalNotification" -> {
                    sendLocalNotification(args)
                }
                "setBadge" -> {
                    setBadgeNumber()
                }
                "getBadge" -> {
                    getBadgeNumber()
                }
                "getPendingNotifications" -> {
                    args.getBoolean(0).let { clearFromDatabase ->
                        controller.getPendingNotifications(clearFromDatabase)
                    }
                }
                else -> false
            }
            true
        }
        return result
    }

    override fun onRequestPermissionResult(requestCode: Int,
                                           permissions: Array<String>,
                                           grantResults: IntArray) {
        when(requestCode) {
            NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                CoroutineScope(IO).launch {
                    controller.registerDevice()
                }
            }
        }
    }

    override fun areGooglePlayServicesAvailable(): Boolean {
        // Not used in this project.
        return false
    }

    private fun getBadgeNumber() {
        controller.getBadgeNumber()
    }

    private suspend fun registerWithPermission() {
        val hasPermission = notificationPermission.hasNotificationPermission(this)
        if(Build.VERSION.SDK_INT < 33 || hasPermission) {
            controller.registerDevice()
        }
        else {
            notificationPermission
                .requestNotificationPermission(this, NOTIFICATION_PERMISSION_REQUEST_CODE)
        }
    }

    private fun sendLocalNotification(args : JSONArray) {
        val badge = args.get(0).toString().toInt()
        val title = args.get(1).toString()
        val text = args.get(2).toString()
        val channelName = args.get(3).toString()
        val channelDescription = args.get(4).toString()
        controller.sendLocalNotification(badge, title, text, null, channelName, channelDescription)
    }

    private fun clearNotifications() {
        controller.clearNotifications()
    }

    private fun setBadgeNumber() {
        controller.setBadgeNumber()
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

}