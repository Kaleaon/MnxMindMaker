package com.kaleaon.mnxmindmaker.persona

/**
 * Persona readiness lifecycle.
 */
enum class PersonaLifecycleState {
    DRAFT,
    REVIEWED,
    READY_TO_DEPLOY,
    DEPLOYED,
    ARCHIVED;

    fun canTransitionTo(target: PersonaLifecycleState): Boolean {
        if (this == target) return true
        return when (this) {
            DRAFT -> target == REVIEWED || target == ARCHIVED
            REVIEWED -> target == DRAFT || target == READY_TO_DEPLOY || target == ARCHIVED
            READY_TO_DEPLOY -> target == REVIEWED || target == DEPLOYED || target == ARCHIVED
            DEPLOYED -> target == ARCHIVED
            ARCHIVED -> target == DRAFT
        }
    }

    fun transitionTo(target: PersonaLifecycleState): PersonaLifecycleState {
        require(canTransitionTo(target)) {
            "Invalid lifecycle transition from $this to $target"
        }
        return target
    }
}
