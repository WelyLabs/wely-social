package com.calendar.social.infrastructure.users.mappers;

import com.calendar.social.domain.models.DeletedUser;
import com.calendar.social.domain.models.DeletedUsersPage;
import com.calendar.social.infrastructure.users.models.DeletedUserResponse;
import com.calendar.social.infrastructure.users.models.DeletedUsersPageResponse;
import org.mapstruct.Mapper;

/** wely-users' listing to domain tombstones; a UUID becomes its canonical lowercase form. */
@Mapper(componentModel = "spring")
public interface DeletedUsersMapper {

    DeletedUser toDomain(DeletedUserResponse item);

    DeletedUsersPage toDomain(DeletedUsersPageResponse page);
}
