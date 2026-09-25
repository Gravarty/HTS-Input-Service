@file:Suppress("DEPRECATION")

package com.gravarty.htsp.tvinput

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.widget.Toast
import androidx.leanback.app.GuidedStepFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.leanback.widget.GuidedActionsStylist
import com.gravarty.htsp.core.HtspConnection
import com.gravarty.htsp.core.HtspSettings
import com.gravarty.htsp.provider.HtspServerSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Login ("Tvheadend-Server"), taken from the old htsptvinput AuthenticatorActivity:
 * ServerFragment -> AccountFragment -> ValidateHTSPAccountFragment -> Completed / Failed.
 * Differences: stored in our settings instead of AccountManager, the server step also asks
 * for the HTTP port (logos), and the fields are pre-filled with the saved values.
 * Returns RESULT_OK when the login was saved.
 */
class HtspLoginActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fragment = ServerFragment()
        fragment.arguments = intent.extras ?: Bundle()
        GuidedStepFragment.addAsRoot(this, fragment, android.R.id.content)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val current = GuidedStepFragment.getCurrentGuidedStepFragment(fragmentManager)
        if (current is CompletedFragment || current is FailedFragment) finish()
        else super.onBackPressed()
    }

    abstract class BaseGuidedStepFragment : GuidedStepFragment() {
        override fun onProvideTheme(): Int = R.style.Theme_Wizard_Account

        protected fun saved(): HtspSettings = HtspServerSync.loadSettings(activity)

        /** Dots instead of the clear-text password (empty stays empty). */
        protected fun mask(value: String): String = "•".repeat(value.length.coerceAtMost(12))
    }

    class ServerFragment : BaseGuidedStepFragment() {

        override fun onCreateGuidance(savedInstanceState: Bundle?) = GuidanceStylist.Guidance(
            getString(R.string.server_name), getString(R.string.server_name_body), null, null
        )

        override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
            val s = saved()
            actions.add(
                GuidedAction.Builder(activity)
                    .id(ACTION_ID_HOSTNAME)
                    .title(R.string.server_ip)
                    .description(s.host)
                    .descriptionEditInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
                    .descriptionInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
                    .descriptionEditable(true)
                    .build()
            )
            actions.add(
                GuidedAction.Builder(activity)
                    .id(ACTION_ID_HTSP_PORT)
                    .title(R.string.server_htsp_port)
                    .description(s.port.toString())
                    .descriptionEditInputType(InputType.TYPE_CLASS_NUMBER)
                    .descriptionInputType(InputType.TYPE_CLASS_NUMBER)
                    .descriptionEditable(true)
                    .build()
            )
            // Added: HTTP port for the channel logos (pvr.hts setting "HTTP port")
            actions.add(
                GuidedAction.Builder(activity)
                    .id(ACTION_ID_HTTP_PORT)
                    .title(R.string.server_http_port)
                    .description(s.httpPort.toString())
                    .descriptionEditInputType(InputType.TYPE_CLASS_NUMBER)
                    .descriptionInputType(InputType.TYPE_CLASS_NUMBER)
                    .descriptionEditable(true)
                    .build()
            )
        }

        override fun onCreateButtonActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
            actions.add(
                GuidedAction.Builder(activity)
                    .id(ACTION_ID_NEXT)
                    .title(R.string.setup_continue)
                    .editable(false)
                    .build()
            )
        }

        override fun onGuidedActionClicked(action: GuidedAction) {
            if (action.id != ACTION_ID_NEXT) return
            val args = arguments

            val hostname = findActionById(ACTION_ID_HOSTNAME).description
            if (hostname == null || TextUtils.isEmpty(hostname)) {
                Toast.makeText(activity, R.string.server_ip_invalid, Toast.LENGTH_SHORT).show()
                return
            }
            args.putString(HtspSettings.KEY_HOST, hostname.toString().trim())

            val htspPort = findActionById(ACTION_ID_HTSP_PORT).description?.toString()?.toIntOrNull()
            if (htspPort == null) {
                Toast.makeText(activity, R.string.server_htsp_port_invalid, Toast.LENGTH_SHORT).show()
                return
            }
            args.putInt(HtspSettings.KEY_PORT, htspPort)

            val httpPort = findActionById(ACTION_ID_HTTP_PORT).description?.toString()?.toIntOrNull()
            if (httpPort == null) {
                Toast.makeText(activity, R.string.server_http_port_invalid, Toast.LENGTH_SHORT).show()
                return
            }
            args.putInt(HtspSettings.KEY_HTTP_PORT, httpPort)

            val fragment = AccountFragment()
            fragment.arguments = args
            add(fragmentManager, fragment)
        }

        private companion object {
            const val ACTION_ID_HOSTNAME = 1L
            const val ACTION_ID_HTSP_PORT = 2L
            const val ACTION_ID_NEXT = 3L
            const val ACTION_ID_HTTP_PORT = 4L
        }
    }

    class AccountFragment : BaseGuidedStepFragment() {

        /** Real password while editing; the list only ever shows dots. */
        private var pendingPassword: String? = null

        override fun onCreateGuidance(savedInstanceState: Bundle?) = GuidanceStylist.Guidance(
            getString(R.string.account_name), getString(R.string.account_name_body), null, null
        )

        override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
            val s = saved()
            actions.add(
                GuidedAction.Builder(activity)
                    .id(ACTION_ID_USERNAME)
                    .title(R.string.account_username)
                    .description(s.username)
                    .descriptionEditInputType(InputType.TYPE_CLASS_TEXT)
                    .descriptionInputType(InputType.TYPE_CLASS_TEXT)
                    .descriptionEditable(true)
                    .build()
            )
            actions.add(
                GuidedAction.Builder(activity)
                    .id(ACTION_ID_PASSWORD)
                    .title(R.string.account_password)
                    .description(mask(s.password))
                    .descriptionEditInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
                    .descriptionInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
                    .descriptionEditable(true)
                    .build()
            )
        }

        override fun onCreateButtonActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
            actions.add(
                GuidedAction.Builder(activity)
                    .id(ACTION_ID_ADD_ACCOUNT)
                    .title(R.string.setup_account_finish)
                    .editable(false)
                    .build()
            )
        }

        override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
            if (action.id == ACTION_ID_PASSWORD) {
                val typed = action.description?.toString().orEmpty()
                val current = pendingPassword ?: saved().password
                // Unchanged (still the dots) keeps the old password
                if (typed != mask(current)) pendingPassword = typed
                action.description = mask(pendingPassword ?: current)
                notifyActionChanged(findActionPositionById(ACTION_ID_PASSWORD))
            }
            return super.onGuidedActionEditedAndProceed(action)
        }

        override fun onGuidedActionClicked(action: GuidedAction) {
            if (action.id != ACTION_ID_ADD_ACCOUNT) return
            val args = arguments

            val username = findActionById(ACTION_ID_USERNAME).description
            if (username == null || TextUtils.isEmpty(username)) {
                Toast.makeText(activity, R.string.account_username_invalid, Toast.LENGTH_SHORT).show()
                return
            }
            args.putString(HtspSettings.KEY_USERNAME, username.toString())

            val password = pendingPassword ?: saved().password
            if (TextUtils.isEmpty(password)) {
                Toast.makeText(activity, R.string.account_password_invalid, Toast.LENGTH_SHORT).show()
                return
            }
            args.putString(HtspSettings.KEY_PASSWORD, password)

            val fragment = ValidateHTSPAccountFragment()
            fragment.arguments = args
            add(fragmentManager, fragment)
        }

        private companion object {
            const val ACTION_ID_USERNAME = 1L
            const val ACTION_ID_PASSWORD = 2L
            const val ACTION_ID_ADD_ACCOUNT = 3L
        }
    }

    class ValidateHTSPAccountFragment : BaseGuidedStepFragment() {
        private val scope = CoroutineScope(Dispatchers.Main)
        private var job: Job? = null

        override fun onCreateActionsStylist(): GuidedActionsStylist = object : GuidedActionsStylist() {
            override fun onProvideItemLayoutId(): Int = R.layout.setup_progress
        }

        override fun onProvideTheme(): Int = R.style.Theme_Wizard_Account_NoSelector

        override fun onCreateGuidance(savedInstanceState: Bundle?) = GuidanceStylist.Guidance(
            getString(R.string.account_name), getString(R.string.setup_progress_title), null, null
        )

        override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
            actions.add(
                GuidedAction.Builder(activity)
                    .id(ACTION_ID_PROCESSING)
                    .title(R.string.setup_progress_body)
                    .infoOnly(true)
                    .build()
            )
        }

        override fun onStart() {
            super.onStart()
            val args = arguments
            val login = saved().copy(
                host = args.getString(HtspSettings.KEY_HOST, ""),
                port = args.getInt(HtspSettings.KEY_PORT),
                httpPort = args.getInt(HtspSettings.KEY_HTTP_PORT),
                username = args.getString(HtspSettings.KEY_USERNAME, ""),
                password = args.getString(HtspSettings.KEY_PASSWORD, "")
            )

            job = scope.launch {
                val conn = HtspConnection(login.host, login.port, login.username, login.password)
                val ok = withContext(Dispatchers.IO) { conn.connect().also { conn.disconnect() } }
                if (!isAdded) return@launch

                if (ok) {
                    saveLogin(activity, login)
                    activity.setResult(RESULT_OK)
                    val fragment = CompletedFragment()
                    fragment.arguments = args
                    add(fragmentManager, fragment)
                } else {
                    args.putString(
                        KEY_ERROR_MESSAGE,
                        getString(if (conn.authFailed) R.string.setup_failed_htsp else R.string.setup_htsp_failed)
                    )
                    val fragment = FailedFragment()
                    fragment.arguments = args
                    add(fragmentManager, fragment)
                }
            }
        }

        override fun onStop() {
            super.onStop()
            job?.cancel()
            job = null
        }

        private companion object {
            const val ACTION_ID_PROCESSING = 1L
        }
    }

    class CompletedFragment : BaseGuidedStepFragment() {
        override fun onCreateGuidance(savedInstanceState: Bundle?) = GuidanceStylist.Guidance(
            getString(R.string.account_name), getString(R.string.account_add_successful), null, null
        )

        override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
            actions.add(
                GuidedAction.Builder(activity)
                    .title(R.string.setup_complete_action_title)
                    .description(R.string.setup_account_complete_body)
                    .editable(false)
                    .build()
            )
        }

        override fun onGuidedActionClicked(action: GuidedAction) {
            activity.finish()
        }
    }

    class FailedFragment : BaseGuidedStepFragment() {
        override fun onCreateGuidance(savedInstanceState: Bundle?) = GuidanceStylist.Guidance(
            getString(R.string.account_name), getString(R.string.setup_complete_failed), null, null
        )

        override fun onResume() {
            super.onResume()
            val errorMessage = arguments.getString(KEY_ERROR_MESSAGE)
            guidanceStylist.descriptionView.text = getString(R.string.account_failed_error, errorMessage)
        }

        override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
            actions.add(
                GuidedAction.Builder(activity)
                    .title(R.string.setup_complete_action_title)
                    .editable(false)
                    .build()
            )
        }

        override fun onGuidedActionClicked(action: GuidedAction) {
            activity.finish()
        }
    }

    companion object {
        private const val KEY_ERROR_MESSAGE = "error_message"

        fun saveLogin(context: Context, s: HtspSettings) {
            context.getSharedPreferences(HtspSettings.PREF_NAME, Context.MODE_PRIVATE).edit()
                .putString(HtspSettings.KEY_HOST, s.host)
                .putInt(HtspSettings.KEY_PORT, s.port)
                .putInt(HtspSettings.KEY_HTTP_PORT, s.httpPort)
                .putString(HtspSettings.KEY_USERNAME, s.username)
                .putString(HtspSettings.KEY_PASSWORD, s.password)
                .commit()
        }
    }
}
