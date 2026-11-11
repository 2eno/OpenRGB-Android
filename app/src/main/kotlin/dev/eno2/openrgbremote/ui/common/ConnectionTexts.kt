package dev.eno2.openrgbremote.ui.common

import androidx.annotation.StringRes
import dev.eno2.openrgbremote.R
import dev.eno2.openrgbremote.core.domain.model.ConnectionProblem

/** Plain-language explanation of a connection problem: what happened and what to do. */
data class ProblemText(
    @StringRes val title: Int,
    @StringRes val explanation: Int,
    @StringRes val hint: Int,
)

val ConnectionProblem.text: ProblemText
    get() = when (this) {
        ConnectionProblem.UNKNOWN_HOST -> ProblemText(R.string.problem_unknown_host_title, R.string.problem_unknown_host_text, R.string.problem_unknown_host_hint)
        ConnectionProblem.CONNECTION_REFUSED -> ProblemText(R.string.problem_refused_title, R.string.problem_refused_text, R.string.problem_refused_hint)
        ConnectionProblem.TIMEOUT -> ProblemText(R.string.problem_timeout_title, R.string.problem_timeout_text, R.string.problem_timeout_hint)
        ConnectionProblem.UNREACHABLE -> ProblemText(R.string.problem_unreachable_title, R.string.problem_unreachable_text, R.string.problem_unreachable_hint)
        ConnectionProblem.NOT_OPENRGB -> ProblemText(R.string.problem_not_openrgb_title, R.string.problem_not_openrgb_text, R.string.problem_not_openrgb_hint)
        ConnectionProblem.PROTOCOL_ERROR -> ProblemText(R.string.problem_protocol_title, R.string.problem_protocol_text, R.string.problem_protocol_hint)
        ConnectionProblem.CONNECTION_LOST -> ProblemText(R.string.problem_lost_title, R.string.problem_lost_text, R.string.problem_lost_hint)
        ConnectionProblem.NO_NETWORK -> ProblemText(R.string.problem_no_network_title, R.string.problem_no_network_text, R.string.problem_no_network_hint)
        ConnectionProblem.UNKNOWN -> ProblemText(R.string.problem_unknown_title, R.string.problem_unknown_text, R.string.problem_unknown_hint)
        ConnectionProblem.JUMP_HOST_UNREACHABLE -> ProblemText(R.string.problem_jump_unreachable_title, R.string.problem_jump_unreachable_text, R.string.problem_jump_unreachable_hint)
        ConnectionProblem.JUMP_HOST_AUTH_FAILED -> ProblemText(R.string.problem_jump_auth_title, R.string.problem_jump_auth_text, R.string.problem_jump_auth_hint)
        ConnectionProblem.JUMP_HOST_KEY_UNKNOWN -> ProblemText(R.string.problem_jump_key_unknown_title, R.string.problem_jump_key_unknown_text, R.string.problem_jump_key_unknown_hint)
        ConnectionProblem.JUMP_HOST_KEY_CHANGED -> ProblemText(R.string.problem_jump_key_changed_title, R.string.problem_jump_key_changed_text, R.string.problem_jump_key_changed_hint)
        ConnectionProblem.JUMP_TARGET_UNREACHABLE -> ProblemText(R.string.problem_jump_target_title, R.string.problem_jump_target_text, R.string.problem_jump_target_hint)
    }

/** "192.168.1.20:6742", or "127.0.0.1:6742 via zeno@pc.local:22" behind a jump host. */
@androidx.compose.runtime.Composable
fun serverAddressLabel(server: dev.eno2.openrgbremote.core.domain.model.Server): String {
    val jump = server.jumpHost ?: return server.address
    return androidx.compose.ui.res.stringResource(R.string.servers_via_jump, server.address, "${jump.username}@${jump.address}")
}
