package com.sil.mia

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import android.widget.ImageButton
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.sil.services.AudioService
import com.sil.services.ScreenshotService
import com.sil.workers.PeriodicNotificationCallWorker
import com.sil.workers.PeriodicSensorDataWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class Main : AppCompatActivity() {
    // region Vars
    private val TAG = "Main"
    private val PREFS_GENERAL = "com.sil.mia.generalSharedPrefs"
    private val KEY_FIRST_RUN = "isFirstRun"
    private val KEY_AUDIO_ENABLED = "isAudioRecordingEnabled"
    private val KEY_SCREENSHOT_ENABLED = "isScreenshotMonitoringEnabled"
    private val KEY_NOTIFICATION_ENABLED = "isNotificationReceivingEnabled"
    private val KEY_SENSOR_UPLOAD_ENABLED = "isUploadingSensorDataEnabled"
    private val PERIODIC_NOTIFICATION_CHECK_WORK = "periodic_notification_check_work"
    private val PERIODIC_SENSOR_DATA_UPLOAD_WORK = "periodic_sensor_data_upload_work"

    private lateinit var generalSharedPref: SharedPreferences

    private lateinit var audioToggleButton: ToggleButton
    private lateinit var screenshotToggleButton: ToggleButton
    private lateinit var notificationsToggleButton: ToggleButton
    private lateinit var settingsButton: ImageButton
    // endregion

    // region Common
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initSharedPreferences()
        initViews()
        setupListeners()
        updateToggleStates()

        if (ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            val shortcut = ShortcutInfoCompat.Builder(this, "share_image")
                .setShortLabel("Save to MIA")
                .setLongLabel("Save to MIA")
                .setIcon(IconCompat.createWithResource(this, R.drawable.mia_stat_name))
                .setIntent(
                    Intent(Intent.ACTION_SEND)
                        .setClass(this, Share::class.java)
                        .setType("image/*")
                )
                .build()

            ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)
        }
    }

    private fun initSharedPreferences() {
        generalSharedPref = getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
        if (generalSharedPref.getBoolean(KEY_FIRST_RUN, true)) {
            generalSharedPref.edit { putBoolean(KEY_FIRST_RUN, false) }
        }
    }
    private fun initViews() {
        audioToggleButton = findViewById(R.id.audioToggleButton)
        screenshotToggleButton = findViewById(R.id.screenshotToggleButton)
        notificationsToggleButton = findViewById(R.id.notificationsToggleButton)

        settingsButton = findViewById(R.id.settingsButton)
    }
    private fun setupListeners() {
        settingsButton.setOnClickListener {
            startActivity(Intent(this, Settings::class.java))
        }
        audioToggleButton.setOnCheckedChangeListener { _, isChecked ->
            Log.i(TAG, "Audio toggle changed: isChecked=$isChecked")
            audioToggleButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            updateServiceState(
                AudioService::class.java,
                isChecked,
                KEY_AUDIO_ENABLED
            )
            val shouldUploadSensorData = audioToggleButton.isChecked || screenshotToggleButton.isChecked || notificationsToggleButton.isChecked
            updateWorkerState(
                "sensor",
                PERIODIC_SENSOR_DATA_UPLOAD_WORK,
                shouldUploadSensorData,
                KEY_SENSOR_UPLOAD_ENABLED
            )
        }
        screenshotToggleButton.setOnCheckedChangeListener { _, isChecked ->
            Log.i(TAG, "Screenshot toggle changed: isChecked=$isChecked")
            audioToggleButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            updateServiceState(
                ScreenshotService::class.java,
                isChecked,
                KEY_SCREENSHOT_ENABLED
            )
            val shouldUploadSensorData = audioToggleButton.isChecked || screenshotToggleButton.isChecked || notificationsToggleButton.isChecked
            updateWorkerState(
                "sensor",
                PERIODIC_SENSOR_DATA_UPLOAD_WORK,
                shouldUploadSensorData,
                KEY_SENSOR_UPLOAD_ENABLED
            )
        }
        notificationsToggleButton.setOnCheckedChangeListener { _, isChecked ->
            Log.i(TAG, "Notifications toggle changed: isChecked=$isChecked")
            audioToggleButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            updateWorkerState(
                "notification",
                PERIODIC_NOTIFICATION_CHECK_WORK,
                isChecked,
                KEY_NOTIFICATION_ENABLED
            )
            val shouldUploadSensorData = audioToggleButton.isChecked || screenshotToggleButton.isChecked || notificationsToggleButton.isChecked
            updateWorkerState(
                "sensor",
                PERIODIC_SENSOR_DATA_UPLOAD_WORK,
                shouldUploadSensorData,
                KEY_SENSOR_UPLOAD_ENABLED
            )
        }
    }
    private fun updateToggleStates() {
//        // Animator setup
//        val expandAnim = AnimatorInflater.loadAnimator(this, R.animator.expand)
//        val shrinkAnim = AnimatorInflater.loadAnimator(this, R.animator.shrink)

        fun animateToggle(toggle: ToggleButton, shouldBeChecked: Boolean) {
            // Only animate if the state changes
            val wasChecked = toggle.isChecked
            toggle.isChecked = shouldBeChecked

            if (wasChecked == shouldBeChecked) return

//            val animRes = if (shouldBeChecked) R.animator.expand else R.animator.shrink
//            val anim = AnimatorInflater.loadAnimator(this, animRes)
//            anim.setTarget(toggle)
//            anim.start()
        }

        val isAudioRunning = isServiceRunning(this, AudioService::class.java)
        animateToggle(audioToggleButton, isAudioRunning)

        val isScreenshotRunning = isServiceRunning(this, ScreenshotService::class.java)
        animateToggle(screenshotToggleButton, isScreenshotRunning)

        val notificationCheckWorkInfoList =  WorkManager.getInstance(this)
            .getWorkInfosForUniqueWork(PERIODIC_NOTIFICATION_CHECK_WORK)
            .get()
        val isNotificationRunning = notificationCheckWorkInfoList.any {
            it.state == androidx.work.WorkInfo.State.RUNNING || it.state == androidx.work.WorkInfo.State.ENQUEUED
        }
        animateToggle(notificationsToggleButton, isNotificationRunning)
    }
    // endregion

    // region State Related
    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        Log.i(TAG, "isServiceRunning | Checking if ${serviceClass.simpleName} is running...")

        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun updateServiceState(serviceClass: Class<*>, isEnabled: Boolean, preferenceKey: String) {
        val serviceIntent = Intent(this, serviceClass)
        if (isEnabled) {
            Log.i(TAG, "${serviceClass.simpleName} created")
            startForegroundService(serviceIntent)
        } else {
            Log.i(TAG, "${serviceClass.simpleName} stopped")
            stopService(serviceIntent)
        }
        CoroutineScope(Dispatchers.IO).launch {
            generalSharedPref.edit { putBoolean(preferenceKey, isEnabled) }
        }
    }
    private fun updateWorkerState(workerType: String, workName: String, isEnabled: Boolean, preferenceKey: String, intervalMinutes: Long = 15) {
        if (isEnabled) {
            Log.i(TAG, "$workName created")

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // Create the periodic work request
            val uploadWorkRequest = when (workerType) {
                "sensor" -> PeriodicWorkRequestBuilder<PeriodicSensorDataWorker>(intervalMinutes, TimeUnit.MINUTES)
                "notification" -> PeriodicWorkRequestBuilder<PeriodicNotificationCallWorker>(intervalMinutes, TimeUnit.MINUTES)
                else -> throw IllegalArgumentException("Unknown worker type: $workerType")
            }
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    OneTimeWorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .setConstraints(constraints)
                .build()

            // Schedule the work, replacing any existing one
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                workName,
                ExistingPeriodicWorkPolicy.REPLACE,
                uploadWorkRequest
            )
        } else {
            Log.i(TAG, "$workName stopped")
            WorkManager.getInstance(this).cancelUniqueWork(workName)
        }

        CoroutineScope(Dispatchers.IO).launch {
            generalSharedPref.edit { putBoolean(preferenceKey, isEnabled) }
        }
    }
    // endregion
}