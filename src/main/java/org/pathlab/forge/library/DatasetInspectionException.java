package org.pathlab.forge.library;

public final class DatasetInspectionException extends Exception {
    private static final long serialVersionUID = 1L;
    private final String code;

    public DatasetInspectionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
