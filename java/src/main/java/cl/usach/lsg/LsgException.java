package cl.usach.lsg;

/**
 * Error del cliente LSG con un código estable que Lua puede interpretar:
 * CONFIG, AUTH, NETWORK, HTTP_<código>, PARSE.
 */
public final class LsgException extends Exception {
    private final String code;
    private final int httpStatus;

    public LsgException(String code, String message) { this(code, message, 0); }

    public LsgException(String code, String message, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() { return code; }
    public int httpStatus() { return httpStatus; }
}
