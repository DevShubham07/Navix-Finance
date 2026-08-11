Pull Contract API

> **⚠️ Vendor doc — corrected against the live API (verified 2026-08-11, production).**
> This is Signzy's own copy. Three things in it are wrong; build DTOs from the live shape, not from
> the tables below (see `SignzyContractClient` / `SignzyDtos.ContractPullResponse`):
> 1. **`matchScoreResult` does not exist.** The live field is **`nameMatchResult`**, and it is a
>    **String**, not an object.
> 2. **`noOfFailureAttempts` is never returned.** Only `noOfSuccessfulAttempts` comes back.
> 3. **`estamp` is omitted entirely** when the contract was created without one — it is not present-
>    but-empty, so don't assume the key exists.
>
> Also: the sample cURL below points at **preproduction, which we are not entitled to** — it answers
> `403 {"message":"You cannot consume this service"}`. Use **`https://api.signzy.app`**. These two
> contract endpoints do **not** require the `x-client-unique-id` header that the rest of Signzy needs.
>
> Undocumented fields the live response *does* return, top level: `contractCreationTime`,
> `contractStatus`, `cancelledSignerCount`, `contractCancelTimestamp`, `auditCertificateUrl`,
> `auditCertificateHash`, `callbackUrlAuthorizationHeader`, `callbackHeaders`,
> `signerCallbackUrlAuthorizationHeader`, `signerCallbackHeaders`, `customerMailList`, `endOfLife`,
> `callbackStatus`, `reviewerdetail`; per-signer: `uidLastFourDigits`, `esignUrl`,
> `signerCancelMessage`.
>
> Live-test with `./test-contract-esign.sh pull <contractId>` (read-only, costs nothing).

Introduction

The Pull Contract API offers a streamlined way to retrieve comprehensive details about a contract using its unique Contract ID. This API provides real-time updates on signer statuses, including completion, pending signatures, and deletions. With this information, you can efficiently track the signing progress, manage signed and deleted signers, and gauge the overall contract completion status.

Key Features:

Retrieve contract details using Contract ID.

Track real-time signer statuses.

Monitor completed, pending, and deleted signers.

Get an overview of contract completion status.

Seamlessly integrate with existing systems.

The Pull Contract API simplifies contract management by providing actionable insights into signer activities and overall contract progress.

Authentication

API authentication is a crucial security process that ensures authorized access to an application programming interface (API). It involves validating the identity of users or systems seeking to interact with the API. Please use the access token shared with you by your assigned Signzy's CSM. Please set the value of the key Authorization in the headers to the access token, while making an API call. In this way, Signzy's system will be able to authenticate you and you will be able to make a successful API call.

API Details

The details of the API can be found here.

Sample cURL

0

curl --location '<https://api-preproduction.signzy.app/api/v3/contract/pullData>' \\

\--header 'Authorization: \*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*' \\

\--header 'Content-Type: application/json' \\

\--data '{

    "contractId": "\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*\*"

}'

Input Parameters

Key	Description	Type	Mandatory/Optional

contractId	ID of the contract	String	Mandatory

Sample Response

0

{

    "contractId": "3c54deeb-b91d-4893-8ec9-46573e85f45b",

    "initialSignerCount": 2,

    "signedSignerCount": 0,

    "deletedSignerCount": 0,

    "contractName": "dd",

    "contractExecuterName": "dd",

    "successRedirectUrl": "<https://signzy.com>",

    "failureRedirectUrl": "<https://google.com>",

    "callbackUrl": "<https://qa.signzy.xyz/callback/mohitposts>",

    "signerCallbackUrl": "<https://qa.signzy.xyz/callback/mohitposts>",

    "contractTtl": 10000,

    "initialContract": "<https://staging-persist.signzy.tech/api/files/4879805/download/1a3018421e5b411687718238f8d045df60031ccea6404341a63c23d81aa29279.pdf>",

    "initialContractHash": "51acd08565171da3a2e1f50b600df06b444ff18204e53103d2ef590688a3a39a",

    "finalSignedContract": "",

    "finalSignedContractHash": "",

    "isCompleted": false,

    "contractCompletionTime": "",

    "estamp": {

        "transactionId": "64d9c218374c36670d994286",

        "estampStatus": "PENDING",

        "transactionResult": {

            "challanNo": "NL0013",

            "challanSubNo": "NL0013-00001",

            "eStampedFile": "<https://staging-persist.signzy.tech/api/files/4879805/download/1a3018421e5b411687718238f8d045df60031ccea6404341a63c23d81aa29279.pdf>"

        }

    },

    "signerdetail": \[

        {

            "signerName": "mohit",

            "signerGender": "male",

            "signerYearOfBirth": "0101",

            "signerId": "0ee176c4-39a1-4b9d-9923-85be82730ac4",

            "signatureType": "AADHAARESIGN-OTP",

            "contractLastSignTime": "",

            "status": "DRAFT",

            "errorMessage": "",

            "aadhaarStatus": "",

            "aadhaarErrorMessage": "",

            "matchScoreResult": {},

            "signerIP": "",

            "noOfSuccessfulAttempts": 0,

            "noOfFailureAttempts": 0,

            "signerSignedContract": "",

            "signerSignedContractHash": "",

            "esignAttempts": \[\]

        },

        {

            "signerName": "cdcdc",

            "signerGender": "NA",

            "signerYearOfBirth": "0101",

            "signerId": "93b6308c-0adc-4c8a-85ff-7b307439d74b",

            "signatureType": "AADHAARESIGN-OTP",

            "contractLastSignTime": "",

            "status": "PENDING",

            "errorMessage": "",

            "aadhaarStatus": "",

            "aadhaarErrorMessage": "",

            "matchScoreResult": {},

            "signerIP": "",

            "noOfSuccessfulAttempts": 0,

            "noOfFailureAttempts": 0,

            "signerSignedContract": "",

            "signerSignedContractHash": "",

            "esignAttempts": \[

                {

                    "errorMessage": "NA",

                    "aadhaarStatus": "1",

                    "aadhaarErrorMessage": "",

                    "signerDscData": {

                        "state": "KARNATAKA",

                        "pincode": "",

                        "dnQualifier": "",

                        "yob": "",

                        "gender": "Male",

                        "uidLastFourDigits": "",

                        "x500UniqueIdentifier": "",

                        "aadhaarToken": "",

                        "pseudonym": "",

                        "aadhaarType": "PERSONAL",

                        "country": "IN",

                        "name": ""

                    }

                }

            \]

        }

    \],

    "deletedsignerdetail": \[\]

}

Output Parameters

Field Name	Description	Type

contractId	Unique identifier for the contract.	String

initialSignerCount	Number of initial signers for the contract.	Number

signedSignerCount	Number of signers who have already signed the contract.	Number

deletedSignerCount	Number of signers who have been deleted from the contract.	Number

contractName	Name of the contract.	String

contractExecuterName	Name of the contract executor.	String

successRedirectUrl	URL to redirect after successful contract execution.	String

failureRedirectUrl	URL to redirect in case of contract execution failure.	String

callbackUrl	URL for callback upon contract completion.	String

signerCallbackUrl	URL for individual signer callbacks.	String

contractTtl	Time to live (in milliseconds) for the contract.	Number

initialContract	URL to the initial contract document.	String

initialContractHash	Initial hash of the contract.	String

finalSignedContract	URL to the final signed contract document.	String

finalSignedContractHash	Hash of the final signed contract.	String

isCompleted	Status indicating whether the contract is completed (true) or not (false).	Boolean

contractCompletionTime	Timestamp indicating when the contract was completed.	String

estamp	Electronic stamp details for the contract.	Object

signerdetail	Details of signers for the contract.	Array of Objects

deletedsignerdetail	Details of deleted signers from the contract.	Array of Objects

eStamp:

Field Name	Description	Type

transactionId	Unique identifier for the eStamp transaction.	String

estampStatus	Status of the eStamp transaction.	String

transactionResult	Result details of the eStamp transaction.	Object

Transaction Result (within eStamp):

Field Name	Description	Type

challanNo	Challan number for the eStamp transaction.	String

challanSubNo	Sub-challan number for the eStamp transaction.	String

eStampedFile	URL to the eStamped contract document.	String

SignerDetail:

Field Name	Description	Type

signerName	Name of the signer.	String

signerGender	Gender of the signer.	String

signerYearOfBirth	Year of birth of the signer.	String

signerId	Unique identifier for the signer.	String

signatureType	Type of signature for the signer.	String

contractLastSignTime	Timestamp of the signer's last sign attempt.	String

status	Status of the signer (e.g., DRAFT, PENDING).	String

errorMessage	Error message related to the signer.	String

aadhaarStatus	Aadhaar status of the signer.	String

aadhaarErrorMessage	Error message related to Aadhaar verification.	String

matchScoreResult	❌ WRONG — see the note at the top. The live field is nameMatchResult (String).	Object

signerIP	IP address of the signer.	String

noOfSuccessfulAttempts	Number of successful sign attempts by the signer.	Number

noOfFailureAttempts	❌ NOT RETURNED by the live API — see the note at the top.	Number

signerSignedContract	URL to the signer's signed contract document.	String

signerSignedContractHash	Hash of the signer's signed contract.	String

esignAttempts	Details of e-sign attempts by the signer.	Array of Objects

eSign Attempts:

Field Name	Description	Type

errorMessage	Error message, if any	String

aadhaarStatus	Aadhaar status	String

aadhaarErrorMessage	Error message related to Aadhaar	String

signerDscData	Object containing signer DSC data	Object

signerDscData.state	State information	String

signerDscData.pincode	Pincode information	String

signerDscData.dnQualifier	Distinguished Name qualifier	String

signerDscData.yob	Year of birth	String

signerDscData.gender	Gender	String

signerDscData.uidLastFourDigits	Last four digits of UID	String

signerDscData.x500UniqueIdentifier	X.500 unique identifier	String

signerDscData.aadhaarToken	Aadhaar token	String

signerDscData.pseudonym	Pseudonym	String

signerDscData.aadhaarType	Type of Aadhaar	String

[signerDscData.country](http://signerDscData.country)	Country	String

[signerDscData.name](http://signerDscData.name)	Name	String

Sample Errors

0

{

    "name": "error",

    "message": "contractId length must be 36 characters long",

    "reason": "VALIDATION_ERROR",

    "type": "Bad Request",

    "statusCode": 400

}

Error Parameters

Parameter	Type	Description

name	String	In case of errors, it will have the value "error". It represents an error.

message	String	Message for the error

reason	String	Reasons for the error

type	String	Type of error

statusCode	String	Status code of the error

Error Codes

Error Code	Error Message	Explanation

400	Bad Request	Input parameter has a missing required parameter or invalid inputs

401	Authorization Failed	Authorization token is invalid

404	Not Found	Contract ID eneterd is not found in the database

500	Internal Server Error	Internal error at Signzy, Please reach out to [help@signzy.com](mailto:help@signzy.com)