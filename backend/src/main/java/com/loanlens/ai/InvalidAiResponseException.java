package com.loanlens.ai;

/** The model's answer failed validation. The message names the rule that failed; it never repeats the answer itself. */
public class InvalidAiResponseException extends RuntimeException {

    public InvalidAiResponseException(String reason) {
        super(reason);
    }
}
