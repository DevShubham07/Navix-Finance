package com.navix.loan.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the loan lifecycle.
 *
 * TODO: define request/response DTOs and wire to {@code LoanService}.
 */
@RestController
@RequestMapping("/api/loan")
public class LoanController {

    /**
     * Submit a new loan application.
     *
     * TODO: implement.
     */
    @PostMapping("/applications")
    public Object apply() {
        // TODO: accept LoanApplicationRequest and delegate to LoanService.apply.
        throw new UnsupportedOperationException("LoanController.apply not implemented yet");
    }

    /**
     * Fetch a loan by id.
     *
     * TODO: implement.
     */
    @GetMapping("/{loanId}")
    public Object getLoan(@PathVariable Long loanId) {
        // TODO: return loan view.
        throw new UnsupportedOperationException("LoanController.getLoan not implemented yet");
    }
}
