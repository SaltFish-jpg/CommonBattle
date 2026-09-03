package com.commonbattle.actor;

record ActorMailboxEnqueueResult(boolean accepted, ActorTask droppedTask, DeadLetterReason rejectionReason) {
    static ActorMailboxEnqueueResult acceptedResult() {
        return new ActorMailboxEnqueueResult(true, null, null);
    }

    static ActorMailboxEnqueueResult acceptedWithDrop(ActorTask droppedTask) {
        return new ActorMailboxEnqueueResult(true, droppedTask, null);
    }

    static ActorMailboxEnqueueResult rejected(DeadLetterReason reason) {
        return new ActorMailboxEnqueueResult(false, null, reason);
    }
}
