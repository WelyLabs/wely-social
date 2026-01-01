package com.calendar.social.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum BusinessErrorCode {

    SEND_FRIEND_REQUEST_FAILURE("SCL_BUS_001", "L'utilisateur n'existe pas ou vous êtes déjà amis", HttpStatus.BAD_REQUEST),
    ACCEPT_FRIEND_REQUEST_FAILURE("SCL_BUS_002", "L'utilisateur n'existe pas ou aucune relation n'existe entre vous", HttpStatus.BAD_REQUEST),
    REJECT_FRIEND_REQUEST_FAILURE("SCL_BUS_003", "L'utilisateur n'existe pas ou aucune relation n'existe entre vous", HttpStatus.BAD_REQUEST),
    DELETE_FRIENDSHIP_FAILURE("SCL_BUS_004", "L'utilisateur n'existe pas ou aucune relation n'existe entre vous", HttpStatus.BAD_REQUEST),
    USER_DOES_NOT_EXIST("SCL_BUS_005", "L'utilisateur n'existe pas", HttpStatus.NOT_FOUND),;

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
