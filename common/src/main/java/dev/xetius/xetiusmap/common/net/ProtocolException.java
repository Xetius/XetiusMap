package dev.xetius.xetiusmap.common.net;

/** Thrown when a peer sends something that does not decode. Always recoverable at the dispatch site. */
public class ProtocolException extends RuntimeException {

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
