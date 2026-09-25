@file:Suppress("DEPRECATION")

package com.gravarty.htsp.tvinput

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.tv.TvContract
import android.os.Bundle
import androidx.leanback.preference.LeanbackPreferenceFragment
import androidx.leanback.preference.LeanbackSettingsFragment
import androidx.preference.DialogPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragment
import androidx.preference.PreferenceScreen
import com.gravarty.htsp.core.HtspConnection
import com.gravarty.htsp.core.HtspSettings
import com.gravarty.htsp.provider.HtspInputs
import com.gravarty.htsp.provider.HtspLog
import com.gravarty.htsp.provider.HtspServerSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Settings, taken from the old htsptvinput SettingsActivity / SettingsFragment /
 * StreamProfileLoader (Leanback preferences). Differences: "Tvheadend-Server" entry on top
 * opens the login; only the EPG and profile preferences; changes to EPG or login apply
 * right away (live sync reconnects + full sync, like pvr.hts reconnects), so the old
 * "restart required" toast is not shown.
 */
class HtspSettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_settings)
    }

    class SettingsFragment : LeanbackSettingsFragment(), DialogPreference.TargetFragment {
        private var mPreferenceFragment: PreferenceFragment? = null

        override fun onPreferenceStartInitialScreen() {
            val fragment = buildPreferenceFragment(null)
            mPreferenceFragment = fragment
            startPreferenceFragment(fragment)
        }

        override fun onPreferenceStartFragment(caller: PreferenceFragment, pref: Preference): Boolean = false

        override fun onPreferenceStartScreen(caller: PreferenceFragment, pref: PreferenceScreen): Boolean {
            startPreferenceFragment(buildPreferenceFragment(pref.key))
            return true
        }

        private fun buildPreferenceFragment(root: String?): PreferenceFragment {
            val fragment = LocalLeanbackPreferenceFragment()
            fragment.arguments = Bundle().apply { putString(PreferenceFragment.ARG_PREFERENCE_ROOT, root) }
            return fragment
        }

        override fun <T : Preference?> findPreference(key: CharSequence): T? =
            mPreferenceFragment?.findPreference(key)
    }

    class LocalLeanbackPreferenceFragment : LeanbackPreferenceFragment(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val scope = CoroutineScope(Dispatchers.Main)
        private var mProfileJob: Job? = null

        override fun onCreatePreferences(bundle: Bundle?, s: String?) {
            preferenceManager.sharedPreferencesName = HtspSettings.PREF_NAME
            preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE

            val root = arguments?.getString(PreferenceFragment.ARG_PREFERENCE_ROOT, null)
            if (root == null) addPreferencesFromResource(R.xml.preferences)
            else setPreferencesFromResource(R.xml.preferences, root)
        }

        override fun onStart() {
            super.onStart()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
            loadStreamProfiles()
            updateChannelCount()
        }

        override fun onStop() {
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
            mProfileJob?.cancel()
            mProfileJob = null
            super.onStop()
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            if (preference.key == KEY_SERVER) {
                startActivityForResult(Intent(activity, HtspLoginActivity::class.java), REQUEST_LOGIN)
                return true
            }
            return super.onPreferenceTreeClick(preference)
        }

        @Deprecated("Deprecated in Java")
        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            if (requestCode == REQUEST_LOGIN && resultCode == RESULT_OK) applyNow()
        }

        override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
            // Profile: used from the next channel switch on. EPG: reload right away.
            if (key == HtspSettings.KEY_ENABLE_EPG || key == HtspSettings.KEY_EPG_MAX_TIME) applyNow()
        }

        private fun applyNow() {
            val context = activity ?: return
            // Only reconnects the live sync if Live Channels is running; no channel scan from here
            LiveMetadataSync.restart(context)
        }

        /** Shows how many TV and radio channels of this app are in the TvProvider. */
        private fun updateChannelCount() {
            val tvPref = findPreference<Preference>(KEY_COUNT_TV) ?: return
            val radioPref = findPreference<Preference>(KEY_COUNT_RADIO) ?: return
            val context = activity?.applicationContext ?: return
            scope.launch {
                val (tv, radio) = withContext(Dispatchers.IO) {
                    countChannels(context, HtspInputs.tv(context)) to
                        countChannels(context, HtspInputs.radio(context))
                }
                if (!isAdded) return@launch
                tvPref.summary = tv?.let { getString(R.string.pref_channels_count, it) } ?: "–"
                radioPref.summary = radio?.let { getString(R.string.pref_channels_count, it) } ?: "–"
            }
        }

        /** Fills the stream profile list with the profiles available on the server. */
        private fun loadStreamProfiles() {
            val profilePreference = findPreference<ListPreference>(HtspSettings.KEY_PROFILE) ?: return

            val current = profilePreference.value ?: getString(R.string.pref_default_htsp_stream_profile)

            // Until the server answers, only the current profile can be chosen
            setProfileEntries(profilePreference, listOf(current))
            profilePreference.setSummary(R.string.pref_profile_loading)

            val context = activity.applicationContext
            mProfileJob = scope.launch {
                val profileNames = loadProfiles(context)
                mProfileJob = null
                if (!isAdded) return@launch
                if (profileNames.isNullOrEmpty()) {
                    profilePreference.summary = getString(R.string.pref_profile_unavailable, current)
                    return@launch
                }
                val names = ArrayList(profileNames)
                if (!names.contains(current)) {
                    // Keep a configured profile selectable even if the server no longer lists it
                    names.add(0, current)
                }
                setProfileEntries(profilePreference, names)
                profilePreference.summary = getString(R.string.pref_profile_current, current)
            }

            profilePreference.setOnPreferenceChangeListener { preference, newValue ->
                preference.summary = getString(R.string.pref_profile_current, newValue)
                true
            }
        }

        private companion object {
            const val KEY_SERVER = "server"
            const val KEY_COUNT_TV = "channel_count_tv"
            const val KEY_COUNT_RADIO = "channel_count_radio"
            const val REQUEST_LOGIN = 1
            const val TIMEOUT_MS = 5000L

            fun countChannels(context: Context, inputId: String): Int? = try {
                context.contentResolver.query(
                    TvContract.buildChannelsUriForInput(inputId),
                    arrayOf(TvContract.Channels._ID),
                    null, null, null
                )?.use { it.count }
            } catch (e: Exception) {
                HtspLog.e("Channel count failed", e)
                null
            }

            fun setProfileEntries(preference: ListPreference, names: List<String>) {
                val entries = names.toTypedArray<CharSequence>()
                preference.entries = entries
                preference.entryValues = entries
            }

            /** Old StreamProfileLoader: HTSP getProfiles, null if it could not be loaded. */
            suspend fun loadProfiles(context: Context): List<String>? = withContext(Dispatchers.IO) {
                val s = HtspServerSync.loadSettings(context)
                if (s.host.isEmpty()) return@withContext null
                val conn = HtspConnection(s.host, s.port, s.username, s.password)
                try {
                    withTimeoutOrNull(TIMEOUT_MS * 2) {
                        if (!conn.connect()) return@withTimeoutOrNull null
                        val response = conn.sendRequest("getProfiles", emptyMap())
                        response.getList("profiles")
                            ?.mapNotNull { (it as? Map<*, *>)?.get("name") as? String }
                            ?.filter { it.isNotEmpty() }
                    }
                } catch (e: Exception) {
                    HtspLog.e("Failed to load stream profiles", e)
                    null
                } finally {
                    conn.disconnect()
                }
            }
        }
    }
}
