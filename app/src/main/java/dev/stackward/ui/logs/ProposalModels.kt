package dev.stackward.ui.logs

import dev.stackward.permissions.ActionProposal
import dev.stackward.permissions.PermissionDecision

data class ProposalWithDecision(
    val proposal: ActionProposal,
    val decision: PermissionDecision,
)
