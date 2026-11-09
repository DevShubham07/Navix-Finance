# Mandate lifecycle — diagrams

Derived from `02-` and `09-`. Digio states and events are VERIFIED; the Navix column is the proposal
in `08-`/`09-`.

## 1. Registration

```mermaid
sequenceDiagram
    participant B as Borrower
    participant N as Navix
    participant D as Digio
    participant S as Yes Bank (sponsor)
    participant P as NPCI
    participant X as Destination bank

    N->>D: POST /v3/client/mandate/create_form
    D-->>N: 200 ENA… · state=partial · authentication_url
    Note over N: mandate = PENDING_AUTHORIZATION

    N->>B: present authentication_url
    B->>D: authorise (net banking / debit card / Aadhaar / eSign)
    D-->>N: webhook MNDT.AUTHSUCCESS
    N->>D: GET /v3/client/mandate/{id}   (re-read, never trust the body)
    D-->>N: state=auth_success · NO UMRN
    Note over N: AUTHORIZED_PENDING_REGISTRATION — cannot collect yet

    D->>S: mandate file
    D-->>N: webhook MNDT.TRANSUCCESS
    S->>P: ACH registration file
    D-->>N: webhook MNDT.SPOBANKACCEPT → MNDT.NPCIACCEPT
    P->>X: registration request
    X-->>P: accepted + UMRN
    P-->>S: response file
    S-->>D: response
    D-->>N: webhook MNDT.DESTBANKACCEPT
    N->>D: GET /v3/client/mandate/{id}
    D-->>N: bank_details.state=register_success · umrn=…
    Note over N: ACTIVE — the first state that can fund a debit
```

**Days, not seconds**, from authorisation to UMRN. Everything before `MNDT.DESTBANKACCEPT` is
progress, not permission.

## 2. Debit

```mermaid
sequenceDiagram
    participant N as Navix
    participant D as Digio
    participant S as Yes Bank
    participant P as NPCI
    participant X as Destination bank

    Note over N: T-1, before the late-night cutoff
    N->>D: POST /v3/client/nach_debit/scheduled/register (umrn, amount_in_paise, unique_key)
    D-->>N: 200 NSP… · upcoming_transaction.state=SCHEDULED
    D-->>N: webhook NACH.DEBIT.TXN.CREATED

    Note over D: 00:00 on T — ACH debit file built
    D->>S: debit file
    D-->>N: webhook NACH.DEBIT.SUBMITTEDTOBANK / SPOBANKACCEPT
    S->>P: first clearing window
    P->>X: debit
    X-->>P: success / return
    P-->>S: settlement response
    S-->>D: status (usually EOD on settlement date)
    D-->>N: webhook NACH.DEBIT.SUCCESS | NACH.DEBIT.FAILED
    N->>D: GET /v3/client/nach_debit/transaction/{txnId}
    D-->>N: status · utr · failure_code
    Note over N: on success → Payment → outstandingAsOf → loan closes at zero
```

If T is an NPCI holiday, Digio reschedules to the next working day automatically — a later settlement
is **not** a failure.

## 3. Mandate states

```mermaid
stateDiagram-v2
    [*] --> PENDING_AUTHORIZATION : create_form
    PENDING_AUTHORIZATION --> AUTHORIZED_PENDING_REGISTRATION : MNDT.AUTHSUCCESS
    PENDING_AUTHORIZATION --> AUTHORIZATION_FAILED : MNDT.AUTHFAIL
    PENDING_AUTHORIZATION --> EXPIRED : expire_in_days elapsed

    AUTHORIZED_PENDING_REGISTRATION --> REGISTRATION_IN_PROGRESS : transfer_success / accepted_spo_bank
    REGISTRATION_IN_PROGRESS --> ACTIVE : MNDT.DESTBANKACCEPT (UMRN)
    REGISTRATION_IN_PROGRESS --> REGISTRATION_FAILED : DESTBANKREJECT / SPOBANKREJECT / NPCIREJECT
    AUTHORIZED_PENDING_REGISTRATION --> REGISTRATION_FAILED : transfer_failed

    ACTIVE --> CANCELLED : registration_cancel
    ACTIVE --> REVOKED : MNDT.BANKCANCEL

    AUTHORIZATION_FAILED --> [*]
    REGISTRATION_FAILED --> [*]
    EXPIRED --> [*]
    CANCELLED --> [*]
    REVOKED --> [*]

    note right of ACTIVE
      The only state with a UMRN.
      The only state that can fund a debit.
    end note
```

Transitions are **monotonic** — a late out-of-order webhook must never move a mandate backwards
(`09-` §4). Any unrecognised Digio state goes to `UNKNOWN_QUARANTINE`, not to an exception.

## 4. Debit states

```mermaid
stateDiagram-v2
    [*] --> SCHEDULED : nach_debit/scheduled/register
    SCHEDULED --> CANCELLED : transaction/action cancel (before initiation only)
    SCHEDULED --> SKIPPED : plan paused
    SCHEDULED --> IN_PROGRESS : SUBMITTED_TO_BANK
    IN_PROGRESS --> SUCCEEDED : NACH.DEBIT.SUCCESS
    IN_PROGRESS --> FAILED : NACH.DEBIT.FAILED
    IN_PROGRESS --> FAILED_TECHNICAL : DIGIO_TECHNICAL_ERROR
    FAILED --> SCHEDULED : re-present (only where Allow RPN permits, e.g. insufficient balance)
    SUCCEEDED --> [*]
```
