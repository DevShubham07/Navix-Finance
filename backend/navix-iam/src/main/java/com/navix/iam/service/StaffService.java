package com.navix.iam.service;

import com.navix.iam.dto.StaffDtos.StaffResponse;
import com.navix.iam.dto.StaffDtos.UpdateStaffRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Manages staff user accounts (listing, role/status changes, deactivation).
 * TODO: implement persistence-backed logic via StaffUserRepository.
 */
@Service
public class StaffService {

    public List<StaffResponse> listStaff() {
        // TODO: load all staff users and map to StaffResponse.
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public StaffResponse getStaff(Long id) {
        // TODO: fetch a single staff user by id.
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public StaffResponse updateStaff(Long id, UpdateStaffRequest request) {
        // TODO: update role/status with validation.
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public void disableStaff(Long id) {
        // TODO: set status to DISABLED.
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
