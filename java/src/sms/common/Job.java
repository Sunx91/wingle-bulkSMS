package sms.common;

/** One SMS to send. {@code index} is the caller's line number so results can be written back. */
public record Job(int index, String phone, String message) {}
