package com.calendar.social.application.rest.dtos;

/**
 * @param deleteIfLast confirms that leaving as the only member deletes the community. A wrapper, so
 *                     that a body without it reads as false: Jackson 3 refuses a missing primitive.
 */
public record LeaveCommunityRequestDTO(Boolean deleteIfLast) {}
