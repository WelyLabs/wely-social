package com.calendar.social.application.rest.mappers;

import com.calendar.social.application.rest.dtos.PersonResponseDTO;
import com.calendar.social.domain.models.Person;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface PersonRestMapper {

    PersonResponseDTO toResponse(Person person);
}
