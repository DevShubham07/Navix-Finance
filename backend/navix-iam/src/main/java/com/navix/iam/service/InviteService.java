package com.navix.iam.service;

import com.navix.iam.dto.StaffDtos.AcceptInviteRequest;
import com.navix.iam.dto.StaffDtos.CreateInviteRequest;
import com.navix.iam.dto.StaffDtos.InviteResponse;
import com.navix.iam.dto.StaffDtos.StaffResponse;
import org.springframework.stereotype.Service;

/**
 * Handles Admin-issued staff invites and their one-time activation.
 * TODO: generate secure tokens, set expiry, send invite emails, and
 * convert an accepted invite into an ACTIVE StaffUser.
 */
@Service
public class InviteService {

    public InviteResponse createInvite(CreateInviteRequest request) {
        // TODO: persist a StaffInvite with a fresh token + expiry.
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public StaffResponse acceptInvite(AcceptInviteRequest request) {
        // TODO: validate token + expiry, mark accepted, create active staff user.
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
