# DigiLocker API Integration Guide

**Provider:** Fintrix (`https://admin.fintrix.tech/__api/api/v1/`)
**Auth:** `X-Client-ID: CLIENT_ID` + `X-Client-Secret: CLIENT_SECRET`
**Content-Type:** `application/json`

---

## Overview

DigiLocker integration allows your users to securely share government-issued documents directly from their DigiLocker account. The flow follows a simple 5-step sequence.

### Flow Overview

```
[1] digilocker_initialize     → Get redirect URL + client_id
         │
         ▼
    User opens URL and links DigiLocker account
         │
         ▼
[2] digilocker_status         → Poll until completed = true
         │
         ▼
[3] digilocker_list_documents → See which documents user shared
         │
         ├──────────────────────────────────────┐
         ▼                                      ▼
[4] digilocker_document       [5] digilocker_aadhar_xml
    Download any document          Aadhaar XML + parsed data
    (PDF or XML) by file_id        (only if Aadhaar is linked)
```

---

## Step 1 — Initialize DigiLocker Session

**Endpoint:** `POST /digilocker_initialize`

Creates a new DigiLocker session and returns a redirect URL to send to the user.

**Request**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `redirect_url` | string | Yes | URL to redirect user after DigiLocker flow |
| `expiry_minutes` | integer | Yes | Session expiry in minutes (e.g., `20`) |
| `signup_flow` | boolean | No | `false` for returning users, `true` for new sign-ups |
| `remark` | string | No | Optional internal note |

```json
{
  "redirect_url": "https://yourapp.com/callback",
  "expiry_minutes": 20,
  "signup_flow": false,
  "remark": "KYC session for loan application"
}
```

**curl**
```bash
curl -X POST 'https://admin.fintrix.tech/__api/api/v1/digilocker_initialize' \
  -H 'Content-Type: application/json' \
  -H 'X-Client-ID: CLIENT_ID' \
  -H 'X-Client-Secret: CLIENT_SECRET' \
  -d '{
    "redirect_url": "https://yourapp.com/callback",
    "expiry_minutes": 20,
    "signup_flow": false,
    "remark": "KYC session for loan application"
  }'
```

**Response**
```json
{
  "timestamp": "2026-06-18T17:45:32.017Z",
  "transaction_id": "TXN-PROD-b71a245c-34dc-4c1e-abc1-ef549aa04b2a",
  "status": "success",
  "data": {
    "client_id": "digilocker_HdzpcdeqxESEkyjUProu",
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "url": "https://digilocker-sdk.notbot.in/?gateway=production&type=digilocker&token=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...&auth_type=web",
    "expiry_seconds": 3600
  }
}
```

**Key fields to save:**

| Field | Use |
|-------|-----|
| `data.client_id` | Required in all subsequent API calls |
| `data.url` | Redirect the user to this URL to link DigiLocker |
| `data.expiry_seconds` | Session valid for this many seconds |

---

## Step 2 — Check DigiLocker Status

**Endpoint:** `POST /digilocker_status`

Poll this endpoint to know when the user has completed the DigiLocker flow. Check every few seconds until `completed` is `true`.

**Request**
```json
{
  "client_id": "digilocker_HdzpcdeqxESEkyjUProu"
}
```

**curl**
```bash
curl -X POST 'https://admin.fintrix.tech/__api/api/v1/digilocker_status' \
  -H 'Content-Type: application/json' \
  -H 'X-Client-ID: CLIENT_ID' \
  -H 'X-Client-Secret: CLIENT_SECRET' \
  -d '{
    "client_id": "digilocker_HdzpcdeqxESEkyjUProu"
  }'
```

**Response — Pending (user not done yet)**
```json
{
  "timestamp": "2026-06-18T17:46:10.000Z",
  "transaction_id": "TXN-PROD-20a5d2fd-23a0-4b9e-a28e-ba44b7138e44",
  "status": "success",
  "data": {
    "status": "client_initiated",
    "completed": false,
    "failed": false,
    "aadhaar_linked": false,
    "error_count": 0,
    "error_description": null
  }
}
```

**Response — Completed (user linked DigiLocker)**
```json
{
  "timestamp": "2026-06-18T17:48:30.000Z",
  "transaction_id": "TXN-PROD-99b3c12d-11aa-4f2e-bc33-de990abc1234",
  "status": "success",
  "data": {
    "status": "completed",
    "completed": true,
    "failed": false,
    "aadhaar_linked": true,
    "error_count": 0,
    "error_description": null
  }
}
```

**Status values:**

| `status` | `completed` | Meaning |
|----------|-------------|---------|
| `client_initiated` | `false` | User hasn't opened the link yet |
| `in_progress` | `false` | User is completing the flow |
| `completed` | `true` | User finished — proceed to next steps |
| `failed` | `false` | Flow failed — check `error_description` |

> **Polling tip:** Check every 3–5 seconds. Stop polling once `completed` is `true` or `failed` is `true`.

---

## Step 3 — List Documents

**Endpoint:** `POST /digilocker_list_documents`

Fetch the list of documents the user has shared from their DigiLocker account.

**Request**
```json
{
  "client_id": "digilocker_HdzpcdeqxESEkyjUProu"
}
```

**curl**
```bash
curl -X POST 'https://admin.fintrix.tech/__api/api/v1/digilocker_list_documents' \
  -H 'Content-Type: application/json' \
  -H 'X-Client-ID: CLIENT_ID' \
  -H 'X-Client-Secret: CLIENT_SECRET' \
  -d '{
    "client_id": "digilocker_HdzpcdeqxESEkyjUProu"
  }'
```

**Response**
```json
{
  "timestamp": "2026-06-18T17:51:17.326Z",
  "transaction_id": "TXN-PROD-a3add37c-4f68-4a79-83bf-2279a13065c3",
  "status": "success",
  "data": [
    {
      "file_id": "digilocker_file_ouClOJBmPGlchcoFCDim",
      "name": "Aadhaar Card",
      "doc_type": "ADHAR",
      "downloaded": true,
      "issuer": "Unique Identification Authority of India (UIDAI)",
      "description": "Aadhaar Card",
      "file_type": "xml"
    },
    {
      "file_id": "digilocker_file_HAugyUwwZfmsJXxiqLNp",
      "name": "PAN Verification Record",
      "doc_type": "PANCR",
      "downloaded": true,
      "issuer": "Income Tax Department",
      "description": "PAN Verification Record",
      "file_type": "pdf"
    },
    {
      "file_id": "aadhaar",
      "name": "Aadhaar Card",
      "doc_type": "ADHAR",
      "downloaded": true,
      "issuer": "Unique Identification Authority of India (UIDAI)",
      "description": "Aadhaar Card",
      "file_type": "pdf"
    },
    {
      "file_id": "pan",
      "name": "PAN Verification Record",
      "doc_type": "PANCR",
      "downloaded": true,
      "issuer": "Income Tax Department",
      "description": "PAN Verification Record",
      "file_type": "xml"
    }
  ]
}
```

**Document fields:**

| Field | Description |
|-------|-------------|
| `file_id` | Use this in Step 4 to download the document |
| `name` | Human-readable document name |
| `doc_type` | Document type code (`ADHAR`, `PANCR`, `DRVLC`, etc.) |
| `file_type` | File format — `pdf` or `xml` |
| `downloaded` | `true` if file is ready to download |
| `issuer` | Issuing government authority |

> **Note:** The same document may appear twice — once as `xml` and once as `pdf`. Use the `file_type` field to pick the format you need.

---

## Step 4 — Download Document

**Endpoint:** `POST /digilocker_document`

Download any document by its `file_id` from the list above. Returns a pre-signed S3 download URL.

**Request**

| Field | Type | Description |
|-------|------|-------------|
| `client_id` | string | DigiLocker session ID from Step 1 |
| `file_id` | string | `file_id` from Step 3 list |

```json
{
  "client_id": "digilocker_HdzpcdeqxESEkyjUProu",
  "file_id": "aadhaar"
}
```

**curl**
```bash
curl -X POST 'https://admin.fintrix.tech/__api/api/v1/digilocker_document' \
  -H 'Content-Type: application/json' \
  -H 'X-Client-ID: CLIENT_ID' \
  -H 'X-Client-Secret: CLIENT_SECRET' \
  -d '{
    "client_id": "digilocker_HdzpcdeqxESEkyjUProu",
    "file_id": "aadhaar"
  }'
```

**Response**
```json
{
  "timestamp": "2026-06-18T17:55:42.175Z",
  "transaction_id": "TXN-PROD-1b8f1cc6-e19d-432e-8f2b-1af4f468a0b2",
  "status": "success",
  "data": {
    "download_url": "https://aadhaar-kyc-docs.s3.amazonaws.com/fintrixApp/digilocker/digilocker_HdzpcdeqxESEkyjUProu/aadhaar_1781805342074458.pdf?X-Amz-Expires=600&...",
    "mime_type": "application/pdf"
  }
}
```

> **Extract:** `data.download_url` — pre-signed S3 URL, valid for **10 minutes**. Download the file immediately.

**Common `file_id` values:**

| `file_id` | Document |
|-----------|---------|
| `aadhaar` | Aadhaar Card (PDF) |
| `pan` | PAN Card (PDF or XML) |
| `digilocker_file_xxxx` | Any other user-uploaded document |

---

## Step 5 — Aadhaar XML (Parsed Data)

**Endpoint:** `POST /digilocker_aadhar_xml`

If the user linked Aadhaar in DigiLocker (`aadhaar_linked: true` from Step 2), this returns the full parsed Aadhaar data — name, DOB, address, gender, and a profile photo — without needing to parse raw XML yourself.

**Request**
```json
{
  "client_id": "digilocker_HdzpcdeqxESEkyjUProu"
}
```

**curl**
```bash
curl -X POST 'https://admin.fintrix.tech/__api/api/v1/digilocker_aadhar_xml' \
  -H 'Content-Type: application/json' \
  -H 'X-Client-ID: CLIENT_ID' \
  -H 'X-Client-Secret: CLIENT_SECRET' \
  -d '{
    "client_id": "digilocker_HdzpcdeqxESEkyjUProu"
  }'
```

**Response**
```json
{
  "timestamp": "2026-06-18T17:52:28.048Z",
  "transaction_id": "TXN-PROD-638ce45f-49d6-4252-a317-6956b71927ba",
  "status": "success",
  "data": {
    "client_id": "digilocker_HdzpcdeqxESEkyjUProu",
    "digilocker_metadata": {
      "name": "Rahul Sharma",
      "gender": "M",
      "dob": "1995-08-15",
      "mobile_number": "98XXXXX210"
    },
    "aadhaar_xml_data": {
      "full_name": "Rahul Sharma",
      "care_of": "S/O: Suresh Kumar Sharma",
      "dob": "1995-08-15",
      "yob": "1995",
      "gender": "M",
      "masked_aadhaar": "XXXXXXXX4321",
      "zip": "122003",
      "full_address": "Flat No 302 Sector 45 Gurgaon Haryana 122003 India",
      "father_name": "S/O: Suresh Kumar Sharma",
      "profile_image": "/9j/4AAQSkZJRgABAgAAAQABAAD...",
      "address": {
        "house": "Flat No 302",
        "street": "Sector 45",
        "vtc": "Gurgaon",
        "subdist": "Gurgaon",
        "dist": "Gurgaon",
        "state": "Haryana",
        "po": "Sector 45",
        "loc": "",
        "landmark": "",
        "country": "India"
      },
      "uniqueness_id": "9da1b8f3fcdcb57c8b5d0517c964e569343513acd3960f6d0dd20e459b73a98e"
    },
    "xml_url": "https://aadhaar-kyc-docs.s3.amazonaws.com/fintrixApp/digilocker/.../ADHAR_1781805147868498.xsl?X-Amz-Expires=600&...",
    "auid": "6bac927f43b82748"
  }
}
```

**Key extracted fields:**

| Field | Path | Description |
|-------|------|-------------|
| Full Name | `aadhaar_xml_data.full_name` | As on Aadhaar |
| Date of Birth | `aadhaar_xml_data.dob` | Format `YYYY-MM-DD` |
| Gender | `aadhaar_xml_data.gender` | `M` or `F` |
| Masked Aadhaar | `aadhaar_xml_data.masked_aadhaar` | Last 4 digits visible |
| Full Address | `aadhaar_xml_data.full_address` | Single-line address |
| State | `aadhaar_xml_data.address.state` | State name |
| Pincode | `aadhaar_xml_data.zip` | 6-digit PIN |
| Father Name | `aadhaar_xml_data.father_name` | Care-of field |
| Profile Photo | `aadhaar_xml_data.profile_image` | Base64 encoded JPEG |
| Raw XML URL | `data.xml_url` | Pre-signed S3 URL, valid 10 min |

---

## Complete Field Reference

### Response Status Codes

| `status` field | Meaning |
|----------------|---------|
| `success` | Request processed successfully |
| `error` | Something went wrong — check `error_description` |

### Document Type Codes

| `doc_type` | Document |
|------------|---------|
| `ADHAR` | Aadhaar Card |
| `PANCR` | PAN Verification Record |
| `DRVLC` | Driving Licence |
| `VOTERID` | Voter ID |
| `PASDOC` | Passport |

### Session Lifecycle

```
Initialize → [URL sent to user] → Status polling → Completed
    │                                                   │
    │                                          List Documents
    │                                                   │
    └──────────── client_id valid throughout ───────────┘
```

> **Important:** The `client_id` from Step 1 must be passed to all subsequent API calls. Store it immediately after initialization.


X-Client-ID='cli_rZz4yd0vIvCKPI2k'
X-Client-Secret='E7SSlidSAwwT-9-IKEYHIDl3ocqtbyLvVjWoTE1TAj8'
