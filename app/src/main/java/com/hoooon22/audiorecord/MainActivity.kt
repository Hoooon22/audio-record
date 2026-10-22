package com.hoooon22.audiorecord

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.Formatter
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.hoooon22.audiorecord.RecorderService.State

class MainActivity : Activity() {

    private data class Recording(
        val uri: Uri,
        val name: String,
        val durationMs: Long,
        val size: Long,
        val dateAddedSec: Long,
    )

    private lateinit var timerText: TextView
    private lateinit var statusText: TextView
    private lateinit var recordButton: Button
    private lateinit var pauseButton: Button
    private lateinit var emptyText: TextView
    private lateinit var listView: ListView

    private val recordings = mutableListOf<Recording>()
    private val adapter = RecordingAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private var lastState: State? = null

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupEdgeToEdge()

        timerText = findViewById(R.id.timer)
        statusText = findViewById(R.id.status)
        recordButton = findViewById(R.id.recordButton)
        pauseButton = findViewById(R.id.pauseButton)
        emptyText = findViewById(R.id.emptyText)
        listView = findViewById(R.id.list)

        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ -> play(recordings[position]) }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            showOptions(recordings[position])
            true
        }

        recordButton.setOnClickListener {
            when (RecorderService.state) {
                State.IDLE -> startWithPermissions()
                State.RECORDING, State.PAUSED -> RecorderService.send(this, RecorderService.ACTION_STOP)
                State.SAVING -> Unit
            }
        }
        pauseButton.setOnClickListener {
            when (RecorderService.state) {
                State.RECORDING -> RecorderService.send(this, RecorderService.ACTION_PAUSE)
                State.PAUSED -> RecorderService.send(this, RecorderService.ACTION_RESUME)
                else -> Unit
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadRecordings()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    // ---- 화면 갱신 ----

    private fun render() {
        val state = RecorderService.state
        timerText.text = formatElapsed(RecorderService.elapsedMs)
        statusText.setText(
            when (state) {
                State.IDLE -> R.string.state_idle
                State.RECORDING -> R.string.state_recording
                State.PAUSED -> R.string.state_paused
                State.SAVING -> R.string.state_saving
            }
        )
        recordButton.setText(if (state == State.IDLE) R.string.action_start else R.string.action_stop)
        recordButton.isEnabled = state != State.SAVING
        pauseButton.visibility = if (state == State.RECORDING || state == State.PAUSED) View.VISIBLE else View.GONE
        pauseButton.setText(if (state == State.PAUSED) R.string.action_resume else R.string.action_pause)

        // 저장이 끝나면 목록을 새로 읽습니다.
        if (state == State.IDLE && lastState != null && lastState != State.IDLE) loadRecordings()
        lastState = state
    }

    private fun formatElapsed(ms: Long): String {
        val total = ms / 1000
        return String.format("%02d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60)
    }

    private fun setupEdgeToEdge() {
        // targetSdk 35 에서는 항상 edge-to-edge 이므로, 모든 버전에서 같은 방식으로 시스템 바 영역만큼 여백을 줍니다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        val root = findViewById<View>(R.id.root)
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsets
            }
            view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
            insets
        }
    }

    // ---- 녹음 시작 ----

    private fun startWithPermissions() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isEmpty()) requestProjection() else requestPermissions(needed.toTypedArray(), REQ_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            requestProjection()
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // "다시 묻지 않음" 상태: 설정 화면으로 안내합니다.
            AlertDialog.Builder(this)
                .setMessage(R.string.permission_denied)
                .setPositiveButton(R.string.action_open_settings) { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    )
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // "앱 하나만" 공유를 고르면 소리가 녹음되지 않을 수 있으므로 전체 화면(기본 디스플레이)으로 고정합니다.
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        startActivityForResult(intent, REQ_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PROJECTION -> {
                if (resultCode == RESULT_OK && data != null) {
                    val intent = Intent(this, RecorderService::class.java)
                        .setAction(RecorderService.ACTION_START)
                        .putExtra(RecorderService.EXTRA_RESULT_CODE, resultCode)
                        .putExtra(RecorderService.EXTRA_RESULT_DATA, data)
                    startForegroundService(intent)
                } else {
                    Toast.makeText(this, R.string.projection_denied, Toast.LENGTH_SHORT).show()
                }
            }
            REQ_DELETE -> loadRecordings()
        }
    }

    // ---- 녹음 파일 목록 ----

    private fun loadRecordings() {
        recordings.clear()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        try {
            contentResolver.query(
                MediaVolumeRecorder.collectionUri(),
                projection,
                "${MediaStore.Audio.Media.RELATIVE_PATH} = ?",
                arrayOf(MediaVolumeRecorder.RELATIVE_PATH),
                "${MediaStore.Audio.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    recordings += Recording(
                        uri = ContentUris.withAppendedId(MediaVolumeRecorder.collectionUri(), cursor.getLong(idCol)),
                        name = cursor.getString(nameCol) ?: "",
                        durationMs = cursor.getLong(durationCol),
                        size = cursor.getLong(sizeCol),
                        dateAddedSec = cursor.getLong(dateCol),
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to load recordings", e)
        }
        adapter.notifyDataSetChanged()
        emptyText.visibility = if (recordings.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun play(recording: Recording) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(recording.uri, "audio/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_player, Toast.LENGTH_SHORT).show()
        }
    }

    private fun share(recording: Recording) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("audio/mp4")
            .putExtra(Intent.EXTRA_STREAM, recording.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, recording.name))
    }

    private fun showOptions(recording: Recording) {
        val items = arrayOf(getString(R.string.action_play), getString(R.string.action_share), getString(R.string.action_delete))
        AlertDialog.Builder(this)
            .setTitle(recording.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> play(recording)
                    1 -> share(recording)
                    2 -> confirmDelete(recording)
                }
            }
            .show()
    }

    private fun confirmDelete(recording: Recording) {
        AlertDialog.Builder(this)
            .setTitle(recording.name)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.action_delete) { _, _ -> delete(recording) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun delete(recording: Recording) {
        try {
            contentResolver.delete(recording.uri, null, null)
            loadRecordings()
        } catch (e: SecurityException) {
            // 앱을 다시 설치한 경우 등 이 앱이 만든 파일로 인식되지 않으면 시스템 확인 창을 띄웁니다.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val request = MediaStore.createDeleteRequest(contentResolver, listOf(recording.uri))
                startIntentSenderForResult(request.intentSender, REQ_DELETE, null, 0, 0, 0)
            } else {
                Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private inner class RecordingAdapter : BaseAdapter() {
        override fun getCount() = recordings.size
        override fun getItem(position: Int) = recordings[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            val item = recordings[position]
            val duration = if (item.durationMs > 0) {
                formatElapsed(item.durationMs)
            } else {
                getString(R.string.unknown_duration)
            }
            val date = DateFormat.format("yyyy-MM-dd HH:mm", item.dateAddedSec * 1000)
            val size = Formatter.formatShortFileSize(this@MainActivity, item.size)
            view.findViewById<TextView>(android.R.id.text1).text = item.name
            view.findViewById<TextView>(android.R.id.text2).text = "$duration · $size · $date"
            return view
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_PERMISSIONS = 1
        private const val REQ_PROJECTION = 2
        private const val REQ_DELETE = 3
    }
}
