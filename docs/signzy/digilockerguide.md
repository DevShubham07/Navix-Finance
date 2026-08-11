
==============================

Digilocker 2.0 — Get e-Aadhaar API

OVERVIEW

Retrieves an individual's personal identity details from their e-Aadhaar stored in Digilocker, using a requestId obtained after user consent/authorization via the Digilocker iframe flow (Create URL API).

ENDPOINTS

Production 2.0: POST <https://api.signzy.app/api/v3/digilocker-v2/geteAadhaar>

UAT/Testing 2.0: POST <https://api-preproduction.signzy.app/api/v3/digilocker-v2/geteAadhaar>

REQUEST HEADERS

\- Content-Type: application/json (Mandatory)

\- Authorization: &lt;access_token&gt; (Mandatory) — token issued by Signzy support team

REQUEST BODY PARAMETERS

\- requestId (string, Mandatory): Unique ID returned as requestId from the Create URL API, after user authorization in Digilocker iframe

\- extraDigitalCertificateParams (boolean, Optional): true to also fetch digital signature (signatureData) info

\- getBase64Files (boolean, Optional): Return Base64-encoded strings instead of persisted file URLs

\- getEAadhaarPdf (boolean, Optional): Return the e-Aadhaar PDF

\- getEAadhaarJpeg (boolean, Optional): Return the e-Aadhaar JPEG

SAMPLE REQUEST (cURL)

curl --location '<https://api-preproduction.signzy.app/api/v3/digilocker-v2/geteAadhaar>' \\

\--header 'Authorization: &lt;Your Access Token&gt;' \\

\--header 'Content-Type: application/json' \\

\--data '{

  "requestId": "652523835a9f9000112b1ee6",

  "extraDigitalCertificateParams": "true",

  "getEAadhaarPdf": true,

  "getEAadhaarJpeg": true

}'

RESPONSE BODY SCHEMA

\- [result.name](http://result.name) (string): Individual's name

\- result.uid (string): Aadhaar number, first 8 digits masked

\- result.dob (string): Date of birth, DD/MM/YYYY

\- result.gender (string): Gender

\- result.address (string): Full address

\- [result.photo](http://result.photo) (string): URL to photo

\- result.splitAddress (object): district, state, city, pincode, country, addressLine, landMark

\- result.x509Data (object): Digital certificate info — subjectName, certificate (base64), details (version, subject, issuer, serial, validity dates, fingerprint, extensions, etc.)

\- result.x509Data.validAadhaarDSC (string): "yes"/"no" — whether the digital signature certificate is valid for Aadhaar

\- result.signatureData (object): Present only if extraDigitalCertificateParams is true. Contains XML-DSig data: SignedInfo (canonicalization/signature/digest methods), SignatureValue, KeyInfo.X509Data (subject names + certificate chain)

\- aadhaarJpeg (string): URL (or Base64) of Aadhaar JPEG, if requested

\- aadhaarPdf (string): URL (or Base64) of Aadhaar PDF, if requested

SAMPLE SUCCESS RESPONSE (200) - structure (certificate/signature values truncated)

{

  "result": {

    "name": "NAME",

    "uid": "xxxxxxxx0353",

    "dob": "DD/MM/YYYY",

    "gender": "FEMALE",

    "x509Data": {

      "subjectName": "DS NATIONAL E-GOVERNANCE DIVISION 1",

      "certificate": "&lt;base64 certificate&gt;",

      "details": {

        "version": 2,

        "subject": {

          "countryName": "IN",

          "organizationName": "NATIONAL E-GOVERNANCE DIVISION",

          "organizationalUnitName": "NATIONAL E-GOVERNANCE DIVISION",

          "postalCode": "110003",

          "stateOrProvinceName": "DELHI",

          "commonName": "DS NATIONAL E-GOVERNANCE DIVISION 1"

        },

        "issuer": {

          "countryName": "IN",

          "organizationName": "Sify Technologies Limited",

          "organizationalUnitName": "Sub-CA",

          "commonName": "SafeScrypt sub-CA for Document Signer 2022"

        },

        "serial": "99EEBB282E55",

        "notBefore": "2023-08-22T08:21:46.000Z",

        "notAfter": "2026-02-11T07:57:28.000Z",

        "subjectHash": "9c1af880",

        "signatureAlgorithm": "sha256WithRSAEncryption",

        "fingerPrint": "06:78:03:C2:09:4C:18:79:92:82:31:14:65:35:86:04:FF:31:2A:FC",

        "publicKey": { "algorithm": "sha256WithRSAEncryption" },

        "altNames": \[\],

        "extensions": {

          "authorityKeyIdentifier": "...",

          "subjectKeyIdentifier": "...",

          "authorityInformationAccess": "...",

          "cRLDistributionPoints": "...",

          "certificatePolicies": "...",

          "basicConstraints": "CA:FALSE",

          "extendedKeyUsage": "E-mail Protection, ...",

          "subjectAlternativeName": "email:...",

          "keyUsage": "Digital Signature, Non Repudiation"

        }

      },

      "validAadhaarDSC": "yes"

    },

    "address": "address",

    "photo": "https://.../download/0000000000.jpeg",

    "splitAddress": {

      "district": \["THANJAVUR"\],

      "state": \[\["TAMIL NADU", "TN"\]\],

      "city": \["KUMBAKONAM"\],

      "pincode": "612001",

      "country": \["IN", "IND", "INDIA"\],

      "addressLine": "Address",

      "landMark": "KUMBAKONAM"

    },

    "aadhaarJpeg": "https://.../download/....jpeg",

    "aadhaarPdf": "https://.../download/....pdf",

    "signatureData": {

      "xmlns": "<http://www.w3.org/2000/09/xmldsig#>",

      "SignedInfo": {

        "CanonicalizationMethod": { "Algorithm": "<http://www.w3.org/2001/10/xml-exc-c14n#>" },

        "SignatureMethod": { "Algorithm": "<http://www.w3.org/2000/09/xmldsig#rsa-sha1>" },

        "Reference": {

          "URI": "",

          "Transforms": { "Transform": { "Algorithm": "<http://www.w3.org/2000/09/xmldsig#enveloped-signature>" } },

          "DigestMethod": { "Algorithm": "<http://www.w3.org/2001/04/xmlenc#sha256>" },

          "DigestValue": "&lt;base64 digest&gt;"

        }

      },

      "SignatureValue": "&lt;base64 signature&gt;",

      "KeyInfo": {

        "X509Data": {

          "X509SubjectName": \["&lt;DN 1&gt;", "&lt;DN 2&gt;", "..."\],

          "X509Certificate": \["&lt;base64 cert 1&gt;", "&lt;base64 cert 2&gt;", "..."\]

        }

      }

    }

  }

}

SUPPORT

Contact: [help@signzy.com](mailto:help@signzy.com) for API access/issues.

NOTE

\- signatureData only appears when extraDigitalCertificateParams is true.

\- requestId is a one-time value tied to a completed Digilocker consent flow — treat as ephemeral input, do not persist/log long-term.