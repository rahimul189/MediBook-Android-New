package com.example.medibook.utils;

/** In-memory request fence. Contains no password, token, credential or Activity reference. */
public final class GoogleAuthFlow {
    public enum Mode { SIGN_IN, LINK }
    public enum Stage { IDLE, REAUTHENTICATING, SELECTING, EXCHANGING, VERIFYING, SUCCEEDED, FAILED }
    private int request = -1;
    private Mode mode = Mode.SIGN_IN;
    private Stage stage = Stage.IDLE;
    private String owner;

    public boolean busy() {
        return stage == Stage.REAUTHENTICATING || stage == Stage.SELECTING
                || stage == Stage.EXCHANGING || stage == Stage.VERIFYING;
    }
    public boolean start(int id, Mode purpose, String uid) {
        if (busy() || id <= request || purpose == null || (purpose == Mode.LINK && uid == null)
                || (purpose == Mode.SIGN_IN && uid != null)) return false;
        request = id; mode = purpose; owner = uid;
        stage = purpose == Mode.LINK ? Stage.REAUTHENTICATING : Stage.SELECTING;
        return true;
    }
    public boolean accepts(int id, Stage expected, String currentUid) {
        return request == id && stage == expected && busy()
                && (mode != Mode.SIGN_IN || stage != Stage.SELECTING || currentUid == null)
                && (mode != Mode.LINK || owner.equals(currentUid));
    }
    public boolean advance(int id, Stage expected, Stage next, String currentUid) {
        if (!accepts(id, expected, currentUid)) return false;
        boolean legal = (expected == Stage.REAUTHENTICATING && next == Stage.SELECTING)
                || (expected == Stage.SELECTING && next == Stage.EXCHANGING)
                || (expected == Stage.EXCHANGING && next == Stage.VERIFYING);
        if (!legal) return false;
        stage = next; return true;
    }
    public void finish(boolean success) { stage = success ? Stage.SUCCEEDED : Stage.FAILED; }
    public void clear() { request = -1; owner = null; stage = Stage.IDLE; }
    public int request() { return request; }
    public Mode mode() { return mode; }
    public Stage stage() { return stage; }
    public String owner() { return owner; }
}
