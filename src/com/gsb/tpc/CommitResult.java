package com.gsb.tpc;

/**
 * Outcome of {@link Coordinator#commit(String)}.
 *
 * Note the distinction between the <em>decision</em> and its
 * <em>delivery</em>: once the decision COMMIT is logged it is final and
 * will never change, but delivery to every participant may still be in
 * progress (finished later by the coordinator itself or by
 * {@link Coordinator#recover()} after a crash).
 */
public final class CommitResult {

    public enum Status { COMMITTED, ABORTED }

    private final String txId;
    private final Status status;
    private final boolean fullyDelivered;

    private CommitResult(String txId, Status status, boolean fullyDelivered) {
        this.txId = txId;
        this.status = status;
        this.fullyDelivered = fullyDelivered;
    }

    public static CommitResult committed(String txId, boolean fullyDelivered) {
        return new CommitResult(txId, Status.COMMITTED, fullyDelivered);
    }

    public static CommitResult aborted(String txId, boolean fullyDelivered) {
        return new CommitResult(txId, Status.ABORTED, fullyDelivered);
    }

    public String getTxId() {
        return txId;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isCommitted() {
        return status == Status.COMMITTED;
    }

    /**
     * True when every enlisted participant has acknowledged the decision.
     * When false, the decision is durable and will be (re-)delivered by
     * a later {@code commit}/{@code abort} call or by {@code recover()}.
     */
    public boolean isFullyDelivered() {
        return fullyDelivered;
    }

    @Override
    public String toString() {
        return "CommitResult{txId=" + txId + ", status=" + status
                + ", fullyDelivered=" + fullyDelivered + '}';
    }
}
