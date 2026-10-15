package com.calendar.social.application.rest.mappers;

import com.calendar.social.application.rest.dtos.CommunityMemberResponseDTO;
import com.calendar.social.application.rest.dtos.CommunityRequestDTO;
import com.calendar.social.application.rest.dtos.CommunityResponseDTO;
import com.calendar.social.application.rest.dtos.InvitationResponseDTO;
import com.calendar.social.application.rest.dtos.LeaveCommunityResponseDTO;
import com.calendar.social.domain.models.Community;
import com.calendar.social.domain.models.CommunityDraft;
import com.calendar.social.domain.models.CommunityInvitation;
import com.calendar.social.domain.models.CommunityLeave;
import com.calendar.social.domain.models.CommunityMember;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.time.Instant;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CommunityRestMapper {

    CommunityDraft toDraft(CommunityRequestDTO request);

    CommunityResponseDTO toResponse(Community community);

    CommunityMemberResponseDTO toResponse(CommunityMember member);

    LeaveCommunityResponseDTO toResponse(CommunityLeave leave);

    /** {@code now} decides whether it reads as expired: the same instant the service would judge it by. */
    @Mapping(target = "expired", expression = "java(invitation.isExpiredAt(now))")
    InvitationResponseDTO toResponse(CommunityInvitation invitation, Instant now);
}
