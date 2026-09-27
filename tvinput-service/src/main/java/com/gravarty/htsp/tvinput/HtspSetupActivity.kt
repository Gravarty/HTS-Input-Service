@file:Suppress("DEPRECATION")

package com.gravarty.htsp.tvinput

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.leanback.app.GuidedStepFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import com.gravarty.htsp.provider.HtspServerSync
import com.gravarty.htsp.provider.HtspSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Channel scan, taken from the old htsptvinput TvInputSetupActivity:
 * IntroFragment -> SyncingFragment (counter + live channel list) -> CompletedFragment.
 * Differences: the account selection step is gone (one server, stored in our settings);
 * if no server is set up yet, "Starten" opens the login first. A failed scan shows
 * ScanFailedFragment (the old project had no failure page for the scan).
 */
class HtspSetupActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fragment = IntroFragment()
        fragment.arguments = intent.extras ?: Bundle()
        GuidedStepFragment.addAsRoot(this, fragment, android.R.id.content)
    }

    abstract class BaseGuidedStepFragment : GuidedStepFragment() {
        override fun onProvideTheme(): Int = R.style.Theme_Wizard_Setup
    }

    class IntroFragment : BaseGuidedStepFragment() {

        override fun onCreateGuidance(savedInstanceState: Bundle?) = GuidanceStylist.Guidance(
            getString(R.string.setup_intro_title),
            getString(R.string.setup_intro_body),
            getString(R.string.account_label),
            null
        )

        override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
            actions.add(
                GuidedAction.Builder(activity)
                    .title(R.string.setup_begin_title)
                    .description(R.string.setup_begin_body)
                    .editable(false)
                    .build()
            )
        }

        override fun onGuidedActionClicked(action: GuidedAction) {
            if (HtspServerSync.loadSettings(activity).host.isEmpty()) {
                startActivityForResult(Intent(activity, HtspLoginActivity::class.java), REQUEST_LOGIN)
            } else {
                startScan()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            if (requestCode == REQUEST_LOGIN && resultCode == RESULT_OK) startScan()
        }

        private fun startScan() {
            val fragment = SyncingFragment()
            fragment.arguments = arguments
            add(fragmentManager, fragment)
        }

        private companion object {
            const val REQUEST_LOGIN = 1
        }
    }

    class SyncingFragment : BaseGuidedStepFragment(), HtspServerSync.Listener {
        private val scope = CoroutineScope(Dispatchers.Main)
        private var job: Job? = null

        override fun onStart() {
            super.onStart()
            if (job != null) return
            val context = activity.applicationContext
            job = scope.launch {
                val result = HtspServerSync.run(
                    context = context,
                    settings = HtspServerSync.loadSettings(context),
                    onProgress = { text -> mUiHandler.post { mStatusView?.text = text } },
                    listener = this@SyncingFragment
                )
                if (!isAdded) return@launch
                if (result == HtspServerSync.Result.OK) {
                    HtspSyncWorker.schedule(context)
                    val fragment = CompletedFragment()
                    fragment.arguments = arguments
                    add(fragmentManager, fragment)
                } else {
                    val fragment = ScanFailedFragment()
                    fragment.arguments = (arguments ?: Bundle()).apply {
                        putString(ScanFailedFragment.KEY_RESULT, result.name)
                    }
                    add(fragmentManager, fragment)
                }
            }
        }

        override fun onDestroy() {
            job?.cancel()
            super.onDestroy()
        }

        // Channel scan display: counter, status and a live list of found channels

        private val mUiHandler = Handler(Looper.getMainLooper())
        private val mPendingChannels = ArrayList<GuidedAction>()
        private var mFlushScheduled = false
        private var mChannelCount = 0

        private var mCountView: TextView? = null
        private var mStatusView: TextView? = null

        private val mFlushRunnable = Runnable {
            mFlushScheduled = false
            flushChannels()
        }

        // Listener callbacks come from the sync thread
        override fun onChannelFound(channelNumber: Int, channelName: String) {
            mUiHandler.post { channelFound(channelNumber, channelName) }
        }

        override fun onChannelsCompleted(channelCount: Int) {
            mUiHandler.post { channelsCompleted() }
        }

        private fun channelFound(channelNumber: Int, channelName: String) {
            if (!isAdded) return
            mChannelCount++
            mCountView?.text = mChannelCount.toString()

            mPendingChannels.add(
                GuidedAction.Builder(activity)
                    .title(if (channelNumber > 0) "$channelNumber   $channelName" else channelName)
                    .focusable(true)
                    .build()
            )

            // Batch list updates, hundreds of channels arrive within seconds
            if (!mFlushScheduled) {
                mFlushScheduled = true
                mUiHandler.postDelayed(mFlushRunnable, 250)
            }
        }

        private fun channelsCompleted() {
            if (!isAdded) return
            mUiHandler.removeCallbacks(mFlushRunnable)
            mFlushScheduled = false
            flushChannels()

            val stylist = guidanceStylist
            stylist.breadcrumbView?.text = getString(R.string.setup_scan_step, 2)
            stylist.titleView?.setText(R.string.setup_epg_title)
            stylist.descriptionView?.setText(R.string.setup_epg_body)
            mStatusView?.setText(R.string.setup_epg_status)
        }

        private fun flushChannels() {
            if (!isAdded || mPendingChannels.isEmpty()) return
            val actions = ArrayList(getActions())
            actions.addAll(mPendingChannels)
            mPendingChannels.clear()
            setActions(actions)
            // Highlight the most recently found channel (the list scrolls along)
            setSelectedActionPosition(actions.size - 1)
        }

        override fun onCreateGuidanceStylist(): GuidanceStylist = object : GuidanceStylist() {
            override fun onProvideLayoutId(): Int = R.layout.setup_scan_guidance

            override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, guidance: Guidance): View {
                val view = super.onCreateView(inflater, container, guidance)
                mCountView = view.findViewById(R.id.scan_count)
                mStatusView = view.findViewById(R.id.scan_status)
                mCountView?.text = mChannelCount.toString()
                return view
            }
        }

        override fun onDestroyView() {
            mUiHandler.removeCallbacks(mFlushRunnable)
            mCountView = null
            mStatusView = null
            super.onDestroyView()
        }

        override fun onProvideTheme(): Int = R.style.Theme_Wizard_Setup

        override fun onCreateGuidance(savedInstanceState: Bundle?) = GuidanceStylist.Guidance(
            getString(R.string.setup_scan_title),
            getString(R.string.setup_scan_body),
            getString(R.string.setup_scan_step, 1),
            null
        )

        override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
            // Filled live with the channels found
        }
    }

    class CompletedFragment : BaseGuidedStepFragment() {

        override fun onCreateGuidance(savedInstanceState: Bundle?) = GuidanceStylist.Guidance(
            getString(R.string.setup_complete_title),
            getString(R.string.setup_complete_body),
            getString(R.string.account_label),
            null
        )

        override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
            actions.add(
                GuidedAction.Builder(activity)
                    .id(ACTION_ID_SETTINGS)
                    .title(R.string.setup_settings_title)
                    .description(R.string.setup_settings_body)
                    .editable(false)
                    .build()
            )
            actions.add(
                GuidedAction.Builder(activity)
                    .id(ACTION_ID_COMPLETE)
                    .title(R.string.setup_complete_action_title)
                    .description(R.string.setup_account_complete_body)
                    .editable(false)
                    .build()
            )
        }

        override fun onGuidedActionClicked(action: GuidedAction) {
            when (action.id) {
                ACTION_ID_SETTINGS -> startActivity(Intent(activity, HtspSettingsActivity::class.java))
                ACTION_ID_COMPLETE -> {
                    activity.setResult(RESULT_OK)
                    activity.finish()
                }
            }
        }

        private companion object {
            const val ACTION_ID_SETTINGS = 1L
            const val ACTION_ID_COMPLETE = 2L
        }
    }

    /** Not in the old project: shown when the scan fails, built like its FailedFragment. */
    class ScanFailedFragment : BaseGuidedStepFragment() {

        override fun onCreateGuidance(savedInstanceState: Bundle?) = GuidanceStylist.Guidance(
            getString(R.string.setup_scan_title),
            getString(
                when (arguments?.getString(KEY_RESULT)) {
                    HtspServerSync.Result.CONNECT_FAILED.name -> R.string.setup_htsp_failed
                    else -> R.string.setup_scan_failed
                }
            ),
            getString(R.string.account_label),
            null
        )

        override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
            actions.add(
                GuidedAction.Builder(activity)
                    .id(ACTION_ID_RETRY)
                    .title(R.string.setup_retry)
                    .editable(false)
                    .build()
            )
            actions.add(
                GuidedAction.Builder(activity)
                    .id(ACTION_ID_SETTINGS)
                    .title(R.string.setup_settings_title)
                    .description(R.string.setup_settings_body)
                    .editable(false)
                    .build()
            )
        }

        override fun onGuidedActionClicked(action: GuidedAction) {
            when (action.id) {
                ACTION_ID_RETRY -> {
                    val fragment = SyncingFragment()
                    fragment.arguments = arguments
                    add(fragmentManager, fragment)
                }
                ACTION_ID_SETTINGS -> startActivity(Intent(activity, HtspSettingsActivity::class.java))
            }
        }

        companion object {
            const val KEY_RESULT = "scan_result"
            private const val ACTION_ID_RETRY = 1L
            private const val ACTION_ID_SETTINGS = 2L
        }
    }
}
