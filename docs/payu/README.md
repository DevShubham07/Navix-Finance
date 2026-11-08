# PayU integration guide for DhanBoost

> **Status (2026-11-09): integration in progress behind the `payu-repayment` flag (off). Not live.** Compliance items are in [§13](#13-go-live-checklist).
> Extracted from PayU's Developer MCP server (`payu-developer-mcp`) and checked against the code.
> Credentials never go in this file. See [§2](#2-credentials).

**Contents**

1. [The short version](#1-the-short-version)
2. [Credentials](#2-credentials)
3. [Environments](#3-environments)
4. [Which PayU product to use](#4-which-payu-product-to-use)
5. [Hosted Checkout, end to end](#5-hosted-checkout-end-to-end)
6. [Confirming a payment](#6-confirming-a-payment)
7. [Payment Links](#7-payment-links)
8. [TPV: only accept money from the borrower's own account](#8-tpv-only-accept-money-from-the-borrowers-own-account)
9. [Refunds](#9-refunds)
10. [Settlements and reconciliation](#10-settlements-and-reconciliation)
11. [Errors and troubleshooting](#11-errors-and-troubleshooting)
12. [Testing](#12-testing)
13. [Go-live checklist](#13-go-live-checklist)
14. [How PayU fits into DhanBoost](#14-how-payu-fits-into-dhanboost)
15. [Sandbox test results](#15-sandbox-test-results)
16. [Where PayU's docs disagree](#16-where-payus-docs-disagree)
17. [Sources](#17-sources)

---

## 1. The short version

**What it would do for us.** Today a borrower repays by paying us outside the app, uploading a screenshot and waiting for an Accountant to check it. PayU would let them pay inside the flow and have the result confirmed automatically.

**Recommended setup**

| Use | PayU product | Why |
|---|---|---|
| Borrower repays from `/repay` | **Hosted Checkout** | PayU hosts the payment page, so card data never touches our servers |
| Collections chases an overdue borrower | **Payment Links** | An officer can send a link; the borrower doesn't need to log in |

**The four rules that matter most**

1. The **hash is built on the server**. The salt never reaches the browser.
2. **A redirect back from PayU proves nothing.** Before crediting a loan, check the reverse hash and call `verify_payment`.
3. **`pending` means pending.** Don't treat it as failed, or a borrower can end up paying twice.
4. **Credit each payment once.** The browser redirect and the webhook can arrive at the same moment.

---

## 2. Credentials

The PayU dashboard (Developers → API Keys, with the Test/Live toggle in the menu bar) shows two separate pairs:

| Credential | Used for | Secret? |
|---|---|---|
| **Key** | `_payment`, `verify_payment`, refunds. Sent in requests | Identifier; not sensitive by itself |
| **Salt** | Building and checking every hash | **Yes.** Server only, never sent in a request |
| **Client ID** | Payment Links OAuth | Treat as secret |
| **Client Secret** | Payment Links OAuth | **Yes** |
| **Merchant ID (MID)** | `merchantId` header on Payment Links calls | Identifier. **Not on the API Keys tab**; find it in the dashboard or ask PayU |

**Finding the Merchant ID:** PayU's docs don't say where the dashboard shows it. Check the profile or account menu in the dashboard, then your PayU onboarding emails, or ask PayU through [help.payu.in](https://help.payu.in) or your account manager. Ask for the test and live IDs separately. It's only needed for Payment Links.

**Storage for DhanBoost:** SSM SecureString at `/navix/<env>/navix/payu/{key,salt,client-id,client-secret,merchant-id}`, the same pattern as the other vendors. Never in the repo, `.env.example`, logs or tickets.

**Salt notes from PayU**

- PayU recommends Merchant Salt **Version 2**. The 32-character salt on the dashboard is v1.
- A regenerated live salt must be activated within **15 days** or it expires.

---

## 3. Environments

| What | Test | Production |
|---|---|---|
| Start a payment (`_payment`) | `https://test.payu.in/_payment` | `https://secure.payu.in/_payment` |
| Commands (`verify_payment`, refunds) | `https://test.payu.in/merchant/postservice.php?form=2` | `https://info.payu.in/merchant/postservice.php?form=2` |
| OAuth token | `https://uat-accounts.payu.in/oauth/token` | `https://accounts.payu.in/oauth/token` |
| Payment Links | `https://uatoneapi.payu.in/payment-links` | `https://oneapi.payu.in/payment-links` |
| Dashboard | `https://onboarding.payu.in/app/account/signin` (Test Mode toggle) | Same login, Live Mode |

Test and live credentials are not interchangeable. In the test environment PayU shows the specific error and how to fix it. In production it shows a generic message and drops the transaction, so get hashing right in test first.

---

## 4. Which PayU product to use

| Option | What it is | Fit for DhanBoost |
|---|---|---|
| **Hosted Checkout** | We post a form; the borrower pays on PayU's page | **Yes, for `/repay`.** Least compliance burden |
| **Payment Links** | PayU creates a shareable URL | **Yes, for collections** |
| **Merchant Hosted** | We build the payment page and post card or UPI details ourselves | No. Handling card data brings PCI DSS scope |
| **Server-to-server UPI** | UPI collect or intent from our own UI | Later, if the redirect hurts conversion |
| **TPV** | Locks payment to a registered bank account | **Not used** (decided 2026-11-09); see [§8](#8-tpv-only-accept-money-from-the-borrowers-own-account) |
| **Subscriptions / eNACH / UPI Autopay** | Recurring debits | No. We already have Digio for mandates |
| **Payouts** | Sending money out | Not covered here. Disbursal stays manual |

PayU also has v2 JSON APIs (HMAC `Authorization` header, `apitest.payu.in/v2/payments`). This guide uses the v1 form-based APIs, which have the most complete documentation, including Hosted Checkout samples.

Server-side SDKs exist for Java, Node, Python, PHP and Go. The hash is a few lines, so we don't need one.

---

## 5. Hosted Checkout, end to end

```
Borrower        DhanBoost server                PayU
   |  Pay now        |                            |
   |---------------->| work out amount, save      |
   |                 | order row, build hash      |
   |<---- form ------|                            |
   |  browser auto-posts the form                 |
   |--------------------------------------------->|  /_payment
   |                 pays on PayU's page          |
   |<------------- browser POST to surl/furl -----|
   |---------------->| check hash, verify_payment |
   |                 |--------------------------->|
   |                 |<---- true status ----------|
   |                 |<---- webhook (server) -----|  may arrive first
   |<--- 303 to result page                       |
```

### Step 1: request fields

Send as `application/x-www-form-urlencoded` POST to `/_payment`.

| Field | Required | Notes |
|---|---|---|
| `key` | Yes | Merchant key |
| `txnid` | Yes | Our unique order reference, **max 25 characters**. A txnid that has already succeeded can't be reused |
| `amount` | Yes | Use a 2-decimal string, e.g. `10.00`, built from paise |
| `productinfo` | Yes | Max 100 characters |
| `firstname` | Yes | Max 60 characters |
| `email` | Yes | Max 50 characters. Used for fraud and chargeback checks |
| `phone` | Yes in most guides | 10 digits |
| `surl` | Yes | Where the browser is POSTed on success |
| `furl` | Yes | Where the browser is POSTed on failure |
| `hash` | Yes | See below |
| `curl` | No | Where the browser goes if the customer cancels |
| `udf1`–`udf5` | No | Our own fields, max 255 characters. Returned to us unchanged |
| `lastname`, `address1`, `address2`, `city`, `state`, `country`, `zipcode` | No | Billing details |
| `enforce_paymethod` | No | Limits the methods offered, e.g. `creditcard\|debitcard` |

Allowed characters in `productinfo`, `email`, `phone` and address fields: letters, digits, `-`, `_`, `@`, `/`, space and `.`.

### Step 2: request hash

```
sha512(key|txnid|amount|productinfo|firstname|email|udf1|udf2|udf3|udf4|udf5||||||SALT)
```

The result is lowercase hex. With no UDFs, leave them empty but **keep every pipe**, which makes 11 pipes between `email` and `SALT`:

```
key|txnid|amount|productinfo|firstname|email|||||||||||SALT
```

The `amount` in the hash must be the exact string in the form. `10` and `10.00` give different hashes.

```java
// Pattern from PayU's Java sample
String s = key + "|" + txnid + "|" + amount + "|" + productinfo + "|" + firstname + "|" + email
         + "|" + udf1 + "|" + udf2 + "|" + udf3 + "|" + udf4 + "|" + udf5 + "||||||" + salt;
byte[] digest = MessageDigest.getInstance("SHA-512").digest(s.getBytes(StandardCharsets.UTF_8));
```

Extra parameters change the formula. For example, `beneficiarydetail` goes between the six pipes and the salt ([§8](#8-tpv-only-accept-money-from-the-borrowers-own-account)).

### Step 3: the response

PayU sends the browser to `surl` or `furl` with a form POST.

| Field | Meaning |
|---|---|
| `mihpayid` | PayU's ID for the transaction. Needed for refunds |
| `txnid` | Our reference |
| `status` | `success`, `failure` or `pending` |
| `unmappedstatus` | PayU's detailed state ([§6](#payment-states)) |
| `amount` | Original amount requested |
| `net_amount_debit` | Actually debited |
| `mode` | `UPI`, `NB`, `CC`, `DC`, `CASH`, `EMI`, `CLEMI`, `BNPL` |
| `bankcode`, `PG_TYPE` | Payment option and gateway |
| `bank_ref_num` | Bank reference. A good value to show the borrower |
| `addedon` | When the transaction was created, e.g. `2026-03-04 14:46:14` |
| `error`, `error_Message` | `E000` / `No Error` on success |
| `field1`–`field9` | Bank-specific detail. `field9` is PayU's readable message |
| `udf1`–`udf10` | Our fields, returned |
| `hash` | The reverse hash to check |

### Step 4: reverse hash

```
sha512(SALT|status||||||udf5|udf4|udf3|udf2|udf1|email|firstname|productinfo|amount|txnid|key)
```

Build this from the values PayU sent, take the amount from **our own order**, and compare. If it doesn't match, treat the callback as forged.

When the response includes `additionalCharges` (a card fee), the value goes first. The sandbox confirmed this: the plain formula fails on such a response and this one passes.

```
sha512(additionalCharges|SALT|status||||||udf5|udf4|udf3|udf2|udf1|email|firstname|productinfo|amount|txnid|key)
```

---

## 6. Confirming a payment

Don't rely on a single signal. Use the reverse hash, `verify_payment` and webhooks together.

### Verify Payment API

```bash
curl -X POST 'https://test.payu.in/merchant/postservice.php?form=2' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode "key=$PAYU_KEY" \
  --data-urlencode 'command=verify_payment' \
  --data-urlencode "var1=$TXNID" \
  --data-urlencode "hash=$HASH"     # sha512(key|command|var1|salt)
```

`var1` is our `txnid`. PayU's response looks like this (trimmed):

```json
{
  "status": 1,
  "msg": "1 out of 1 Transactions Fetched Successfully",
  "transaction_details": {
    "TXN123": {
      "mihpayid": "21564143078",
      "status": "success",
      "unmappedstatus": "captured",
      "transaction_amount": "2.00",
      "net_amount_debit": 2.00,
      "bank_ref_num": "431998369241",
      "mode": "UPI",
      "error_code": "E000",
      "addedon": "2024-11-14 16:06:40",
      "Merchant_UTR": null,
      "Settled_At": "0000-00-00 00:00:00"
    }
  }
}
```

**The outer `status: 1` only means the API call worked.** The result of the payment is the `status` inside `transaction_details`. An unknown txnid returns `"status": 0` with `"mihpayid": "Not Found"`.

**With a card fee, the amounts split.** On a ₹100 payment with a ₹2.36 fee, the sandbox returned `transaction_amount: "100.00"`, `additional_charges: "2.36"`, and `amt` and `net_amount_debit` of 102.36. The loan is credited from `transaction_amount`, never `amt`.

PayU suggests re-checking pending transactions after about 5 minutes, 15 minutes and 1 hour.

### Payment states

| `unmappedstatus` | PayU `status` | What it means | What we should do |
|---|---|---|---|
| `captured` | success | Paid | Credit the loan |
| `auth` | success | Bank authorised; not captured at PayU | Don't credit; wait |
| `initiated` | pending | Customer landed on PayU's page | Wait |
| `in progress` | pending | Customer sent to the bank or 3DS page | Wait and re-verify |
| `pending` | pending | Started, not finished | Wait and re-verify |
| `failed` | failure | Declined | Mark failed, allow retry |
| `userCancelled` | failure | Customer cancelled | Mark failed, allow retry |
| `bounced` | failure | Customer never clicked Pay | Mark failed, allow retry |
| `dropped` | failure | No response from the gateway | Mark failed, allow retry |
| `autoRefund` | failure | PayU refunded it after reconciliation | Mark failed; never credit |

### Webhooks

**Setup:** dashboard → Developers → Webhooks → Create. Pick type *Payments*, choose the event and enter the URL. Several URLs can be given, separated by commas.

| Event | When it fires |
|---|---|
| Successful | Payment succeeded |
| Failed | Payment failed |
| Refund | Refund succeeded or failed |
| Dispute | A dispute was raised |

- Payment webhooks arrive **form-urlencoded**, not as JSON, with the same fields as the response, including `hash`.
- Return HTTP 200 quickly. Non-200 responses appear in the dashboard's **Webhook Logs** tab.
- Expect duplicates and handle them.
- `callback_on_failure` adds real-time notifications for pending states, and PayU support has to enable it.
- PayU can send a test webhook if you ask support.
- PayU's retry schedule for standard payment webhooks isn't in the material we retrieved. The polling sweep in [§14](#14-how-payu-fits-into-dhanboost) covers any webhook that never arrives.

---

## 7. Payment Links

These use **OAuth** with the Client ID and Secret, not key and salt.

### Get a token

```bash
curl -X POST 'https://uat-accounts.payu.in/oauth/token' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode "client_id=$PAYU_CLIENT_ID" \
  --data-urlencode "client_secret=$PAYU_CLIENT_SECRET" \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode 'scope=create_payment_links read_payment_links update_payment_links'
```

The response has `access_token`, `token_type`, `expires_in`, `scope` and `created_at`. Up to three scopes can be requested, separated by spaces.

| Scope | Allows |
|---|---|
| `create_payment_links` | Create |
| `read_payment_links` | Get one, get all, share |
| `update_payment_links` | Change status or expiry |

### Create a link

```bash
curl -X POST 'https://uatoneapi.payu.in/payment-links' \
  -H "merchantId: $PAYU_MID" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "invoiceNumber": "DBLOAN123A1",
    "subAmount": 2,
    "isPartialPaymentAllowed": false,
    "description": "DhanBoost loan repayment",
    "source": "API",
    "expiryDate": "2026-12-31 23:59:59",
    "successUrl": "https://example.com/ok",
    "failureUrl": "https://example.com/fail"
  }'
```

| Field | Notes |
|---|---|
| `invoiceNumber` | Unique, alphanumeric |
| `subAmount` | Amount, at least 1, when the amount is fixed |
| `isAmountFilledByCustomer` | `true` makes it an open amount; `subAmount` must then be null |
| `isPartialPaymentAllowed`, `minAmountForCustomer` | Partial payment controls |
| `description`, `source` | `source` is `"API"` |
| `expiryDate` | `yyyy-MM-dd HH:mm:ss` |
| `successUrl`, `failureUrl` | **Not** `surl` / `furl`; PayU's FAQ calls that out as a common failure |
| `customerName`, `customerPhone`, `customerEmail`, `udf` | Optional |

Success returns `"status": 0` and `result.paymentLink`. Failure returns `"status": -1`. A duplicate invoice number gives *"Invoice Number already exists"*.

### Other link calls

| Action | Call |
|---|---|
| Get all | `GET /payment-links?dateFrom=…&dateTo=…&pageSize=…&pageOffset=…` |
| Share | `POST /payment-links/{invoiceNumber}/share` with `channelList` |
| Deactivate | `PUT /payment-links/{invoiceNumber}` with `{ "active": false }` |
| See payments on a link | `GET /payment-links/{invoiceNumber}/txns?dateFrom=…&dateTo=…` |

**DhanBoost note:** if we send a link by SMS, the text must match a DLT template and the link domain must be whitelisted on the DLT portal. The sandbox link came back on `v.payu.in`, while PayU's older samples use `pmny.in`, so confirm the live domain before whitelisting.

---

## 8. TPV: only accept money from the borrower's own account

> **Decision (2026-11-09): not used.** Borrowers may repay from any account. Kept here for reference.

TPV (Third-Party Verification) rejects a payment unless it comes from a bank account we registered. PayU lists **loan repayment** as a use case. It works on net banking and UPI. It needs enabling on the merchant account.

Add to the Hosted Checkout request:

| Field | Value |
|---|---|
| `api_version` | `6` |
| `beneficiarydetail` | JSON below, up to 4 accounts separated by `\|` |

```json
{ "beneficiaryAccountNumber": "002001600674", "ifscCode": "KTKB0000046" }
```

Hash changes to:

```
sha512(key|txnid|amount|productinfo|firstname|email|udf1|udf2|udf3|udf4|udf5||||||beneficiarydetail|SALT)
```

The reverse hash doesn't include `beneficiarydetail`. Successful payments come back with TPV bank codes such as `UPITPV` or `SBITPV`.

**Trade-off:** a borrower repaying from a spouse's or a second account would be refused, which could lower collections.

---

## 9. Refunds

| | |
|---|---|
| Endpoint | The commands URL in [§3](#3-environments) |
| `command` | `cancel_refund_transaction` |
| `var1` | `mihpayid` |
| `var2` | Our unique refund token, max 23 characters |
| `var3` | Amount. Less than the original for a partial refund |
| `var5` | Refund callback URL (listed as mandatory, though PayU's simple sample leaves it out) |
| `hash` | `sha512(key\|command\|var1\|salt)`, PayU's pattern for commands. Confirm on a test refund |

In the response, **`error_code` 102 means success**; anything else is a failure. Keep the returned `request_id`.

**Check status:** `check_action_status_txnid` with the request ID, or `getAllRefundsFromTxnIds` with the txnid.

| Refund state | Meaning |
|---|---|
| `QUEUED` | Accepted by PayU, not sent to the bank yet |
| `IN PROGRESS` | With the bank |
| `REQUESTED` | Offline processing at the bank; up to 5–7 business days |
| `od_hit` | Insufficient funds; up to 5–7 business days |
| `SUCCESS` | Done |
| `FAILURE` | Failed; nothing is deducted from settlement |

---

## 10. Settlements and reconciliation

PayU pays us the collected amount **minus its fees**, in batches, so the loan ledger and the bank credit won't match line for line.

| API | Purpose |
|---|---|
| `GET https://apitest.payu.in/settlement/transactionDetails?merchantTransactionId=…` (prod `info.payu.in`) | Settlement status, UTR and net amount for one transaction |
| `GET https://test.payu.in/settlement/v1/merchantUpcomingSettlement` (prod `info.payu.in`) | Upcoming and pending totals. Here `status: 0` means success |
| `get_settlement_details` command | Bank settlement details by date. PayU's FAQ points here, but the request format wasn't in what we retrieved |
| Get TDR API | Fees for a transaction |

`verify_payment` also returns `Merchant_UTR` and `Settled_At` once a transaction settles.

---

## 11. Errors and troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Invalid hash, or the page doesn't load | Wrong pipe count; `500` vs `500.00`; UDFs out of order; key and salt swapped; test credentials on the live URL or the reverse | Rebuild the string per [§5](#step-2-request-hash). PayU has a Hash Verification Tool |
| Duplicate transaction ID | `txnid` reused | New `txnid` per attempt |
| Amount error | Wrong format or too low | 2-decimal string, computed on the server |
| Mandatory parameter missing | A required field wasn't sent | Check [§5](#step-1-request-fields) |
| surl/furl never reached, 400 or 403 | CSRF, auth on the route, or JSON-only handler | Accept form POSTs without a session |
| Callback arrives, order not updated | Reverse hash mismatch, txnid not saved, amount mismatch | Recheck the formula and stored order |
| Order stuck pending | Callback lost | Call `verify_payment` |
| `verify_payment` says Not Found | Payment never started | Treat as "no payment yet" |
| Works in test, fails in live | Environment or credentials not switched together | Switch both together |
| HTTP 429 *"Invalid requests limit reached"* | Too many invalid requests, such as bad hashes | Stop and fix the hash. It cleared within about four minutes in test. Don't retry bad hashes in a loop |
| Hash fails with a salt typed from a screenshot | `I` and `l` look identical in the dashboard font | Use the dashboard's **Copy Salt** button |

**Codes seen in PayU's samples:** `E000` no error · `E500` bank failed to authenticate the customer · `E1602` bank unable to authenticate · `E4936` card load limit reached.

For a failed transaction: `field7` shows the stage it failed at, `field8` the bank's reason and `field9` PayU's translation.

---

## 12. Testing

Test credentials only work in the test environment.

| Method | Details | Source |
|---|---|---|
| Net banking | User `payu`, password `payu`, OTP `123456` | PayU test credentials page |
| UPI | `anything@payu` or `9999999999@payu.in` | PayU test credentials page |
| Wallet (Paytm) | Mobile `7777777777`, OTP `888888` | PayU test credentials page |
| Credit card | `5123456789012346`, expiry `05/30`, CVV `123`, OTP `123456` | PayU's sample requests; **worked in our sandbox run** |

**UPI intent and in-app flows aren't available in test mode.** Only UPI collect can be tested there.

PayU's test simulator can be used as `surl`/`furl`: `https://test-payment-middleware.payu.in/simulatorResponse`.

---

## 13. Go-live checklist

- [ ] Complete merchant KYC (PAN, business, bank details). Check PayU allows our business category
- [ ] Website verification (1–2 days). Production keys aren't shown until it's done
- [ ] Switch dashboard to Live Mode and copy production key and salt into SSM
- [ ] Decide on Salt v2
- [ ] Point at production URLs through config, switching credentials at the same time
- [ ] `surl`, `furl` and webhooks on public HTTPS
- [ ] Production webhooks registered for Successful, Failed, Refund and Dispute
- [ ] `verify_payment` running as the backup to callbacks
- [ ] Feature flag on for a small test first, with one real low-value payment
- [ ] Reconcile that payment through to settlement before opening it up
- [ ] **PayU settles to MLB Securities' own bank account.** RBI Digital Lending Directions para 9(ii) requires repayment directly into the lender's account with no pass-through or pool account, and para 9(iii) bars control by a service provider. Legal confirms the merchant account structure
- [ ] Compliance approves the credit card fee, including who receives it (para 9(iv)), KFS disclosure and card network rules
- [ ] PayU confirms loan repayment is allowed for our merchant category. Its List of Banned Items is not published in its merchant terms
- [ ] Restrict EMI and BNPL at checkout (`enforce_paymethod`), so a loan is not repaid with more credit
- [ ] PayU automatic refunds enabled through the account manager
- [ ] Display any statement or logo PayU requires (merchant terms clause 20.2), using only PayU-supplied logos
- [ ] Sign-off recorded in `agreements/DHAN_BOOST_Review_and_Signoff_Pack.docx`

---

## 14. How PayU fits into DhanBoost

### What the code does today

| Step | Where | Behaviour |
|---|---|---|
| Borrower submits proof | `frontend/src/app/(borrower)/repay/page.tsx` | Txn ref plus a required screenshot |
| Payment recorded | `RepaymentService.recordPayment` | `PENDING_VERIFICATION`; screenshot only required when the actor is a borrower |
| Accountant confirms | `RepaymentService.verifyPayment` | Marks `VERIFIED`, recomputes the balance, closes at zero |
| Record and verify in one step | `LoanDirectoryAdapter.creditCollectionPayment` | What collections and NACH use |
| Provider callbacks | `/api/webhooks/**` in `SecurityConfig` | Public; each handler authenticates itself |

### Proposed design

Our order row is the source of truth, following the Signzy eSign pattern: a callback only triggers a re-check and is never trusted on its own.

```
POST /repay "Pay now" (BFF)
  → backend works out the amount, inserts payu_order (INITIATED), returns form fields + hash
  → browser posts to PayU

PayU → browser POST to a Next.js route (surl/furl)
  → forwards to the public backend path /api/webhooks/payu/return
    (no session or bearer token here; normal /api/** would return 401)
  → backend re-checks with verify_payment, never trusting the POST body
  → 303 to /repay/result?order=…

PayU → webhook → POST /api/webhooks/payu/payment → verify
Scheduler → re-verify open orders at ~5 min, 15 min, 1 hour

verify says captured and amount matches
  → lock the order row → if not yet credited: credit once, mark CREDITED
```

### Things the design has to handle

| # | Issue | What we found | Approach |
|---|---|---|---|
| 1 | **Double credit** | `recordPayment` checks then inserts, and `payment` has no unique index on `(loan_id, txn_ref)`. The V47 index covers `collection_payment` only. Redirect and webhook can arrive together | Guard on a row lock on `payu_order`, with a unique `txnid` |
| 2 | **Duplicate notifications** | `verifyPayment` publishes `RepaymentVerifiedEvent` every time it's called | Credit only on the order's first transition |
| 3 | **Session on return** | The session cookie is `sameSite: "lax"` (`bff-session.ts`), so PayU's cross-site POST arrives **without it** | The return route needs no session; it verifies, then 303s to a GET page where the cookie is present |
| 4 | **Money with nowhere to go** | `recordPayment` throws `LOAN_SETTLED` on a closed loan, e.g. a second payment | Mark the order for staff review or refund. Never swallow the error |
| 5 | **Interest date** | Interest depends on `paidOn`, and a webhook can land after midnight | Use the date from PayU's `addedon` (IST, confirmed in the sandbox), not the arrival time |
| 6 | **Amount integrity** | Docs type `amount` as a float | Format paise as an exact `x.yy` string. Compute it on the server. Compare the paid amount before crediting |
| 7 | **Payment method column** | V2 `CHECK` allows only `UPI`, `BANK_TRANSFER`, `NACH`; `creditCollectionPayment` hardcodes `BANK_TRANSFER` | Add a method through a migration (next is **V93**) if reports must separate PayU |
| 8 | **Manual and gateway together** | A borrower could upload proof and also pay online | Decide; see below |
| 9 | **Webhook actor** | No logged-in user on a webhook. `ActorContext.get()` falls back to `SYSTEM`, so the borrower-only screenshot check doesn't fire | Works as is |
| 10 | **Chargebacks** | A dispute can arrive after a loan has closed | Alert staff from the Dispute webhook. No automatic reversal |
| 11 | **Fees** | `payment.amount` is gross, but settlement is net of PayU's fees | Accounting needs a fee or reconciliation view |
| 12 | **PII** | UDFs travel to PayU and come back | Use internal IDs only. No PAN, Aadhaar or account numbers |

**Plumbing:** feature flag `payu-repayment`, read with `defaultWhenMissing = FALSE` because it moves money. Secrets in SSM per [§2](#2-credentials). Provider mock for local runs, matching how Digio and eSign are configured.

### Credit card fee charged to the borrower

**Decision (2026-11-09):** a borrower who pays by credit card pays a flat **2.36% of the repayment** on top, on every credit card type. UPI, net banking and debit cards have no added fee.

**How to do it.** Send `additional_charges` on the `_payment` request. PayU shows the fee only when that payment method is chosen. The sandbox displayed ₹100 + ₹2.36 "Internet Handling Fee" = ₹102.36 once a credit card was entered.

```
additional_charges=CC:2.36,AMEX:2.36,DC:0,NB:0

sha512(key|txnid|amount|productinfo|firstname|email|udf1|udf2|udf3|udf4|udf5||||||SALT|additional_charges)
```

`amount` stays the repayment. PayU adds the fee itself, so don't add it to `amount`.

**PayU's published rates** ([payu.in/pricing](https://payu.in/pricing/), checked 2026-11-09). GST is extra at 18%. Your signed rates may differ.

| Payment type | PayU's rate | With 18% GST | Borrower pays on top |
|---|---|---|---|
| Visa, Mastercard | 2% | 2.36% | 2.36% |
| **American Express** | **3%** | **3.54%** | **2.36%, so DhanBoost absorbs the difference** |
| Diners | 3% | 3.54% | 2.36%, so DhanBoost absorbs the difference |
| EMI, international | 3% | 3.54% | n/a |
| Net banking, wallets, BNPL | 2% | 2.36% | not charged to the borrower |
| UPI | Varies by business and volume | n/a | not charged to the borrower |
| RuPay, debit cards | Not listed | n/a | Ask PayU |

The test account can't show these: `get_additional_charge` returned null and `get_checkout_details` returned 0.0 for every method, including AMEX, because no fee is configured on it. PayU did recognise a test AMEX BIN correctly.

**Working out the fee.** `fee = repayment × 2.36%`, rounded up to the paisa, set by `NAVIX_PAYU_CARD_FEE_BPS` (`236` = 2.36%, per card type).

PayU charges its percentage on the total including the fee (₹2.05 on ₹102.36 in the sandbox), so a flat 2.36% doesn't fully cover it:

| ₹10,000 repayment by card | Borrower fee | PayU's cost on the total | DhanBoost's shortfall |
|---|---|---|---|
| Visa, Mastercard (2% + GST) | ₹236.00 | ₹241.57 | ₹5.57 |
| AMEX, Diners (3% + GST) | ₹236.00 | ₹362.35 | ₹126.35 |

**Rules for the build**

- Credit the loan with `transaction_amount` only. Store the fee on the order; it isn't a repayment.
- Before crediting, check that `additional_charges` equals the fee we set for the `mode` used.
- Choose the reverse hash formula by whether `additionalCharges` is present ([§5](#step-4-reverse-hash)).
- Keep rates in config, per card type. AMEX and RuPay may differ from Visa and Mastercard.
- PayU's checkout labels the fee non-refundable. Its docs say refund handling can vary, so confirm with PayU.

**Still to confirm**

| Item | Who |
|---|---|
| **Whether your signed rates match the published ones**, and the RuPay credit card rate, which isn't published | PayU agreement, account manager, or the dashboard's monthly TDR report |
| **Credit card via UPI** (UPI CC and credit lines). `additional_charges` was not tested for it. PayU documents a separate fee set up on their side, working on Intent and Dynamic QR only, not Collect | PayU account manager |
| **Whether surcharging is allowed** for a lender. PayU's docs don't cover card-network rules or disclosure, including whether the fee belongs in the Key Fact Statement | Compliance |

PayU can instead add a fee automatically from its side (`get_additional_charge` and `get_checkout_details` then return the amounts). That needs PayU support to enable it and isn't tested.

### Decisions

| Question | Decision |
|---|---|
| Does a PayU-confirmed payment skip the Accountant? | **Yes**, as NACH does. It stays visible in the ledger |
| Restrict payment to the borrower's registered account (TPV)? | **No** |
| Credit cards? | **Accepted, with the card fee added** (above), subject to the checks above |
| Partial payments? | **Allowed** |
| Manual screenshot payment? | **Only after an unsuccessful PayU attempt** |

**Still open:** Payment Links for collections. Recommended: after Hosted Checkout is live.

### What's built

| Part | Where |
|---|---|
| Orders, hashes, card fee, `verify_payment`, once-only credit | `backend/navix-loan/.../payu/` |
| Table `payu_order`, `PAYU` payment method, flag (OFF) | `V93__payu_order.sql` |
| Borrower API | `GET /api/loan/{id}/payu/state`, `POST .../orders`, `GET .../orders/{txnid}` |
| Callbacks | `POST /api/webhooks/payu/{payment,return}`, reached through the BFF at `/api/payu/{webhook,return}` |
| Re-check of unfinished payments, every 5 minutes | `PayuReconcileScheduler` |
| Borrower screen | `/repay` |

**When manual payment appears** (with the flag on): the latest attempt is `FAILED` or `EXPIRED`, or it never left PayU's page and is over 30 minutes old, confirmed by asking PayU at that moment. It stays hidden while a bank is processing a payment. The server enforces this as well as the page. With the flag off, or if PayU isn't configured, manual payment works as before.

**Sandbox protection:** a test payment verifies as `captured` with no money moved, so `test.payu.in` is refused on every call unless `NAVIX_PAYU_ALLOW_SANDBOX` (or `NAVIX_ALLOW_DEV_DEFAULTS`) is true. Never set it where real loans exist.

**Stand-in email:** PayU rejects addresses containing `+` or over 50 characters, so those borrowers are sent to PayU as `pay<loanId>@dhanboost.com`.

**To switch it on**

1. Put live key and salt in SSM, and set `NAVIX_PAYU_KEY`, `NAVIX_PAYU_SALT`, `NAVIX_PAYU_CHECKOUT_URL`, `NAVIX_PAYU_COMMAND_URL` and `NAVIX_APP_BASE_URL` on the backend.
2. Set `NAVIX_PAYU_CARD_FEE_BPS` to the signed rates.
3. Register `https://<site>/api/payu/webhook` in the PayU dashboard for Successful, Failed, Refund and Dispute.
4. `update feature_flag set enabled = true where flag_key = 'payu-repayment';`, starting with one low-value real payment.

**Staff review:** orders needing attention appear on the Accountant's screen as "PayU payments needing review" (ADMIN sees it in the Accountant working role). "Mark resolved" records who dealt with it and a note; it doesn't move money. Refunds are done from the PayU dashboard.

---

## 15. Sandbox test results

Run on 2026-11-09 against PayU's **test hosts only**, with the merchant's test credentials. Each pass has a control, so a success means something.

| # | Check | Result | Control |
|---|---|---|---|
| 1 | Key and salt, via `verify_payment` on an unknown txnid | **Pass.** Returned `"mihpayid": "Not Found"` | Corrupted hash → `Invalid Hash.` |
| 2 | Client ID and secret, via OAuth at `uat-accounts.payu.in` | **Pass.** Bearer token issued for all three Payment Link scopes, `expires_in` 7199 | Wrong secret → 401 `invalid_client` |
| 3 | `_payment` using the [§5](#step-2-request-hash) formula with empty UDFs (11 pipes) | **Pass.** Reached PayU's payment page at `apitest.payu.in/public/` | Wrong hash → *"Transaction failed due to incorrectly calculated hash parameter"* |
| 4 | `verify_payment` on that checkout | **Pass.** Outer `status: 1`; inner `status: pending`, `unmappedstatus: initiated`, `amt: 10.00` | n/a |
| 5 | UPI collect with test VPA `anything@payu` | **Started.** After 20 seconds: `pending`, `in progress`, `mode: UPI`, `field9: Transaction Initiated Successfully` | n/a |
| 6 | Full net banking payment in Chrome, ₹10 | **Pass.** `success`, `captured`, callback received, reverse hash matched, `verify_payment` agreed | n/a |
| 7 | Full credit card payment in Chrome, ₹100 + ₹2.36 fee | **Pass.** `captured`, `additionalCharges: 2.36`, `net_amount_debit: 102.36`, `verify_payment` agreed | Plain reverse hash failed; the fee-prefixed one matched |
| 8 | `get_TDR` on both payments | Returned `0.24` (net banking, ₹10) and `2.05` (card, ₹102.36) | Unit isn't documented; the card figure fits about 2% in rupees. Test values only |
| 9 | Payment Link on UAT: create, read transactions, deactivate | **Pass.** `"status": 0`, link returned on `v.payu.in`; deactivated with `{ "active": false }` | Wrong Merchant ID → HTTP 400, `"status": -1` |

**End to end through the real app** (2026-11-09, Chrome, local backend and frontend, throwaway database, PayU test environment):

| Scenario | Result |
|---|---|
| ₹1,000 part payment by net banking | Passed. `captured`; loan credited, `VERIFIED`, method `PAYU`, no Accountant; balance fell by ₹1,000 |
| Payment fails, borrower cancels on PayU's page | Passed. Order `FAILED`, nothing credited, failure banner, manual payment appears |
| Remaining ₹9,500 by credit card, with fee | Passed. Borrower charged ₹9,729.62; ledger took exactly ₹9,500; ₹229.62 fee stored on the order; loan and application closed |
| Forged webhook and forged return | Ignored on hash mismatch; nothing credited |
| Staff review queue | Passed. Accountant sees and resolves an order; DSA and no-session requests refused; no money moved |

**Bugs only this run could find, all fixed:** the site's `form-action 'self'` policy silently blocked every redirect to PayU; the page used the UTC date, so before 05:30 IST "pay in full" left a day's interest unpaid; an earlier abandoned checkout hid manual payment after a failure.

PayU's behaviour on failure: it returns the borrower to its own page to retry. Our return URL is only reached when they succeed or cancel, so a borrower who fails and closes the tab is picked up by the 30-minute rule and the re-check.

**What the tests established**

- Both credential pairs are valid in the test environment.
- The request hash formula in this guide is correct.
- An abandoned checkout really does show `pending`, which confirms why pending can't be treated as failed.
- `addedon` is in **IST**: the test transaction's `addedon` matched its creation time in IST.
- Too many invalid requests trigger **HTTP 429** (*"Invalid requests limit reached"*). It appeared after four bad-hash requests within about a minute (PayU doesn't publish the threshold), and had cleared when retried about four minutes later.

**Not tested yet**

| Item | Why | What it needs |
|---|---|---|
| Webhook delivery | Needs a public HTTPS endpoint | A deployed test endpoint |
| Refund | Not run yet | A test refund against one of the captured payments |
| `enforce_paymethod` values for UPI and net banking | Only `creditcard\|debitcard` appeared in PayU's samples | A test checkout |

---

## 16. Where PayU's docs disagree

PayU's search results mix merchant, partner and v2 pages, and the MCP says responses may contain mistakes. Where they conflicted, this guide went with the code samples and merchant pages.

| Topic | Conflict | This guide uses |
|---|---|---|
| Hash pipes with no UDFs | Written examples show different counts across pages; one says "15 delimiters in total" | The code samples: 6 pipes after `udf5` |
| Treating `pending` | One response table says treat it as failed; the state table says it's still in progress | Pending stays pending |
| `txnid` length | 25 on merchant pages, 50 on partner and v2 pages | 25 |
| Reverse hash | Some partner pages show 5 pipes after `status` | 6, as on merchant pages |
| Partner flows | `merchant_id\|…\|client_secret` hashes, `test-partnerapilayer`, JSON webhooks | Left out: these are for resellers |
| Link header | `merchantId` in samples; `mid` in one table | `merchantId` |
| `/postservice` path | Both `/postservice?form=2` and `/postservice.php?form=2` appear | `.php`, verified in the sandbox |
| "Success" codes | `status: 1` on commands; `status: 0` on Payment Links and settlement APIs; `error_code 102` on refunds | Handled per API |
| TPV JSON | Field names differ by product | Merchant Hosted Checkout format; confirm on a test transaction |

---

## 17. Sources

All from PayU's Developer MCP (`docs.payu.in`):

- [Hosted Checkout](https://docs.payu.in/docs/prebuilt-checkout-page-integration) · [Collect Payment API](https://docs.payu.in/reference/_payment_payu_hosted_checkout) · [Generate Hash](https://docs.payu.in/docs/generate-hash-payu-hosted) · [Redirect URLs](https://docs.payu.in/docs/handling-the-redirect-urls)
- [Verify Payment](https://docs.payu.in/reference/verify_payment_api) · [Webhooks](https://docs.payu.in/docs/webhooks) · [Webhook events](https://docs.payu.in/docs/webhook-events-and-sample-payloads) · [Create a webhook](https://docs.payu.in/docs/create-a-new-webhook)
- [Create Payment Link](https://docs.payu.in/reference/create-payment-links) · [Payment Link FAQs](https://docs.payu.in/docs/faqs-payment-links) · [Link transactions](https://docs.payu.in/reference/get-transaction-details-api)
- [TPV overview](https://docs.payu.in/docs/introduction-to-payu-tpv) · [UPI TPV](https://docs.payu.in/docs/collect-upi-payment-with-tpv-payu-hosted-checkout)
- [Refund API](https://docs.payu.in/reference/refund_transaction_api) · [Refund status](https://docs.payu.in/reference/check_action_status_api_with_request_id) · [Settlement transaction details](https://docs.payu.in/reference/settlement_transaction_details_api)
- [Error handling](https://docs.payu.in/docs/error-handling) · [Error codes](https://docs.payu.in/reference/payu-errors-v2) · [Test credentials](https://docs.payu.in/docs/ios-custombrowser-test-integration) · [Test key and salt](https://docs.payu.in/docs/generate-test-merchant-key-and-salt) · [Production key and salt](https://docs.payu.in/docs/generate-merchant-key-and-salt-on-payu-dashboard)
