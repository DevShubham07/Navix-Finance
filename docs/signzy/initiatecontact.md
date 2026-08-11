Initiate Contract API

> **⚠️ Vendor doc — PRODUCTION ONLY (verified 2026-08-11).**
> The sample cURL below points at **preproduction, which we are not entitled to**: it answers
> `403 {"message":"You cannot consume this service"}`. Use **`https://api.signzy.app`**, i.e. the
> `SIGNZY_PROD_CLIENT` / `navix.signzy.prod-*` credentials — the same production account DigiLocker v2
> and Liveness run on. These two contract endpoints do **not** require the `x-client-unique-id` header
> that the rest of the Signzy surface needs.
>
> **Every call mints a real, billable, legally binding Aadhaar eSign contract, and there is no sandbox.**
> Do not retry speculatively. For a smoke test use `./test-contract-esign.sh pull <contractId>` against
> an existing contract instead — reading costs nothing.
>
> **`contractTtl` appears not to be honoured.** A live call sending `600000` (10 min) came back with
> `contractExpiresOn` ~7 days out, and the doc's own claimed default is 14 days. Treat contract expiry
> as "roughly a week, don't rely on it": `ApplicationVerificationService.esignStatus` re-mints when
> `pullData` 404s with nothing signed.
>
> Wired up in `SignzyEsignAdapter` behind the `EsignPort` seam; response parsing in
> `SignzyContractClient`. Note the response is **flat** — there is no `result` wrapper.

Introduction
Welcome to the Initiate Contract API, your gateway to effortlessly streamline and orchestrate the process of creating and managing contracts within your application. This comprehensive guide will walk you through the seamless integration of this API, empowering you to initiate contracts, define signers, customize contract parameters, and efficiently manage callback URLs. With the power of this API at your fingertips, you can seamlessly orchestrate complex contract workflows while ensuring a smooth and user-friendly experience for all parties involved.

By leveraging the Initiate Contract API, you can easily upload your contract files, specify signer details, define callback URLs, and even customize various aspects of the contract to align with your specific requirements. The API harnesses the capability to dynamically generate signing URLs for each signer, providing a direct and secure path for individuals to engage with and sign the contract digitally.

Whether you're a developer looking to enhance your application's contract management capabilities or a business seeking a sophisticated and streamlined way to engage signers, the Initiate Contract API offers a powerful solution that seamlessly integrates into your existing infrastructure.

Join us as we delve into the details of how to leverage the Initiate Contract API to empower your application with the ability to effortlessly initiate, manage, and facilitate the signing of contracts, ensuring a frictionless and secure contract execution process for all stakeholders involved. Let's embark on this journey to revolutionize the way contracts are managed and executed within your ecosystem.

Authentication
API authentication is a crucial security process that ensures authorized access to an application programming interface (API). It involves validating the identity of users or systems seeking to interact with the API. Please use the access token shared with you by your assigned Signzy's CSM. Please set the value of the key Authorization in the headers to the access token, while making an API call. In this way, Signzy's system will be able to authenticate you and you will be able to make a successful API call.

API Details
The details of the API can be found here.

Sample cURL
0

curl --location 'https://api-preproduction.signzy.app/api/v3/contract/initiate' \
--header 'Authorization: ***************************************' \
--header 'Content-Type: application/json' \
--data '{
    "pdf": "****************",
    "persistAuthKey": "****************",
    "contractName": "Savings Account Opening Form",
    "contractExecuterName": "Signzy",
    "successRedirectUrl": "https://signzy.com",
    "failureRedirectUrl": "https://google.com",
    "contractTtl": 10000,
    "callbackUrl": "",
    "callbackUrlAuthorizationHeader": "Test",
    "eSignProvider": "EMUDHRA",
    "emudhraCustomization": {
        "logoURL": "",
        "headerColour": "#FF0000",
        "buttonColour": "#FF0000"
    },
    "signerdetail": [
        {
            "signerName": "Mohit",
            "signerGender": "male",
            "signatureType": "AADHAARESIGN-OTP",
            "uidLastFourDigits": "",
            "signerYearOfBirth": "",
            "signatures": [
                {
                    "pageNo": [
                        "All"
                    ],
                    "signaturePosition": [
                        "BottomLeft"
                    ]
                }
            ]
        },
        {
            "signerName": "Ashish",
            "signatureType": "AADHAARESIGN-OTP",
            "uidLastFourDigits": "",
            "signerYearOfBirth": "",
            "signatures": [
                {
                    "pageNo": [
                        11,
                        2,
                        5
                    ],
                    "signaturePosition": [
                        "TOPLEFT",
                        "customize"
                    ],
                    "xCoordinate": [
                        300,
                        5
                    ],
                    "yCoordinate": [
                        400,
                        5
                    ]
                },
                {
                    "pageNo": [
                        1,
                        2,
                        5
                    ],
                    "signaturePosition": [
                        "TOPLEFT",
                        "customize"
                    ],
                    "xCoordinate": [
                        300,
                        5
                    ],
                    "yCoordinate": [
                        400,
                        5
                    ]
                }
            ]
        }
    ],
    "estamp": {
        "type": "eChallan",
        "secondPartyName": "Test",
        "firstPartyName": "Signzy",
        "stateCode": "KA",
        "articleCode": "KA001",
        "stampDutyValue": ""
        "purposeOfStampDuty": "",
        "considerationPrice": "",
        "stampDutyPaidBy": "",
        "amount": 1,
        "location": "middleCenter",
        "pageNo": [],
        "custmonDefacement": "true",
        "message": ""
    },
    "signerCallbackUrl": "",
    "signerCallbackAuthorizationHeader": "test"
    "nameMatchThreshold": "0.90",
    "allowSignerYOBMatch": true,
    "allowSignerGenderMatch": true
}'
Input Parameters
Key	Type	M/O	Description
pdf	String	Mandatory	The input PDF file which needs to be signed. It can be in PDF or Base64 formats.
persistAuthKey	String	Optional	If the key is applied during contract creation, both the signed contract and the links to the final signed contract will be password protected using that key.
It accepts alpha numeric values.
contractName	String	Optional	Name of the contract
contractExecuterName	String	Optional	Name of the entity who has initiated/created/started the contract signing process
successRedirectUrl	String	Mandatory	The redirect URL - Post the completion of the Journey, in cases of successful completion, it will be redirected to this page
failureRedirectUrl	String	Mandatory	The redirect URL - Post the completion of the Journey, in cases of failures, it will be redirected to this page
contractTtl	String	Optional	The time in milliseconds after which the contract will expire (By default, it's 14 days)
esignProvider	String	Optional	The provider of the Aadhaar eSign. By default, it will be eMudhra. It can be set to either -
nsdl
eMudhra
emudhraCustomization.logoURL	String	Optional	In case of eMudhra as eSign Provider, pass the logo URL to do a brand customization on UI of eMudhra pages
emudhraCustomization.headerColour	String	Optional	In case of eMudhra as eSign Provider, pass the Header Colour to do a brand customization on UI of eMudhra pages
emudhraCustomization.buttonColour	String	Optional	In case of eMudhra as eSign Provider, pass the Button Colour to do a brand customization on UI of eMudhra pages
signerdetail	Array of Objects	Mandatory	Details about all the signers.
signerName	String	Mandatory	Name of the signer.
signatureType	String	Mandatory	The type of signatures. The three possible values are -
aadhaaresign-otp,
aadhaaresign-fingerprint
aadhaaresign-faceauth
signerGender	String	Optional	Gender of the signer (Male/Female/Transgender).
uidLastFourDigits	String	Optional	Last 4 digits of the Aadhaar Number of the signer.
signerYearOfBirth	String	Optional	Year of birth of the signer.
signatures	Array of Objects	Mandatory	To allow signature to be placed at multiple places on the same page. Following is the signature object:

pageNo : array of page numbers where signautres need to be pasted.
Pass ALL, if signature is required on all pages.

signaturePosition : Position where signature is required
TOP-LEFT
TOP-CENTER
TOP-RIGHT
MIDDLE-LEFT
MIDDLE-CENTER
MIDDLE-RIGHT
BOTTOM-LEFT
BOTTOM-CENTER
BOTTOM-RIGHT
CUSTOMIZE
In case of CUSTOMIZE, pas the x,y coordinate and height, width.
height : height of signautre
width : width of signature
xCoordinate : array of coordinates,
yCoordinate : array of coordinates,
NOTE : No. of xCoordinate and yCoordinate must be equal
callbackUrl	String	Mandatory	Callback url for posting data of the contract
callbackUrlAuthorizationHeader	String	Optional	If the callback URL uses authentication, pass the authroization header value in this key
signerCallback Url	String	Optional	Signer Callback url for posting data once Aadhaar ESign Process for the particular transaction is completed by the signer.
signerCallbackAuthorizationHeader	String	Optional	If the Signer Callback URL uses authentication, pass the authroization header value in this key
nameMatchThreshold	String	Optional	In case of Aadhaar esigning, the name extracted after signer signs with aadhaar esign must match with signerName mentioned for a particular signer. If the percentage of name match is less than the threshold, it will be retry scenario. The details would be posted on callback URL for the integrator to make a decision.
Possible Values : "0.01", "0.02", "0.03", ...... , "1.00"
allowSignerGenderMatch	String	Optional	In case of Aadhaar esign, if this parameter is set to true, the signer gender extracted from aadhaar must match with signerGender mentioned for a particular signer. If it doesn't match, it wiil be a retry scenario.
Note : This parameter will be considered only if nameMatchThreshold field is passed. And once this parameter is passed, signerGender becomes a mandatory parameter with values ["Male","Female","Transgender"].
allowSignerYOBMatch	String	Optional	In case of Aadhaar esign, if this parameter is set to true, the signer YOB extracted from aadhaar must match with YOB mentioned in signerYearOfBirth for a particular signer. If it doesn't match, it will be a retry scenario.
Note : This parameter will be considered only if nameMatchThreshold field is passed. And once this parameter is passed, signerYearOfBirth becomes a mandatory parameter.
eStamp	Object	Optional	This is a option to get a stamped document to sign
eStamping Details
Keys	Description	Type	M/O
type	you want to use estamp or eChallan. Values are -
eChallan
eStamp	string	Mandatory
stateCode	state code of the states where estamps are valid. Possible values : ["KA", "MH", "GJ", "AP", "AN", "AS", "BR", "CT", "CH", "DN", "DD", "DL", "HP", "JK", "JH", "LA", "OR", "PY", "RJ", "TN", "TR", "UP", "UT", "PB", "WB", "GA", "HR", "KL", "MN", "ML", "MZ", "NL", "LD", "SK", "AR", "TG", "MP"]	string	Mandatory
firstPartyName	name of first party involved in the transaction	string	Mandatory
secondPartyName	name of second party involved in the transactiton	string	Mandatory
stampDutyValue	amount of the stamp duty	string	Mandatory in cases when type eStamp
purposeOfStampDuty	purpose of stamp duty	string	Mandatory
considerationPrice	amount of fund involved in the transaction	string	Mandatory in cases when type eStamp
articleCode	article Code for E-stamping	string	Mandatory
stampDutyPaidBy	name of the party who paid the stamp duty(first party or second party)	string	Mandatory
location	location of defacement. available option : topLeft, topCenter, middleLeft, middleCenter, bottomLeft, bottomCenter
Right not included as there can be space issue.	String	Optional (By default bottomLeft)
amount	amount of the eChallan duty	String	Mandatory in cases when type is eChallan, not needed when type is estamp
pageNo	Page no on which defacement is needed.	Array	Optional(By default first page)
custmonDefacement	custmDefacement is required or not	Boolean	Optional(By default false) when type is eChallan
message	Message
text for custom defacement, It can contain variables. the variable should be enclosed in {{}} and can be one from the given {{challanNo}},{{challanDate}},{{stateCode}},{{firstParty}},{{articleCode}},{{amount}},{{secondPartyName}},{{purposeOfStampDuty}},{{stampDutyPaidBy}}	String	Mandatory when custmonDefacement is true
Sample Response
0

{
    "emudhraCustomization": {
        "buttonColour": "#FF0000",
        "headerColour": "#FF0000",
        "logoURL": "https://media.trustradius.com/vendor-logos/th/Nh/MVDWEP9M89X5-180x180.JPEG"
    },
    "customerId": "bc3024f6-b2de-493f-8b30-e132c3701ce7",
    "username": "adityasahu",
    "contractId": "e6b9bf89-5725-4331-9efd-0ff53b914764",
    "pdf": "https://staging-persist.signzy.tech/api/files/4879875/download/11d297ab903e442784c6a847a2ce10f3970b314ed512437db9f6bdfc55f6b501.pdf",
    "initialContractHash": "51acd08565171da3a2e1f50b600df06b444ff18204e53103d2ef590688a3a39a",
    "contractName": "dd",
    "contractExecuterName": "dd",
    "successRedirectUrl": "https://signzy.com",
    "failureRedirectUrl": "https://google.com",
    "eSignProvider": "EMUDHRA",
    "contractTtl": 10000,
    "signerdetail": [
        {
            "signerId": "3bd160a3-5e52-4ea8-9819-c1fc1e1bd838",
            "signerName": "mohit",
            "signatureType": "AADHAARESIGN-OTP",
            "uidLastFourDigits": "1345",
            "signerYearOfBirth": "0101",
            "signerGender": "male",
            "signatures": [
                {
                    "pageNo": [
                        "All"
                    ],
                    "signaturePosition": [
                        "BottomLeft"
                    ],
                    "xCoordinate": [],
                    "yCoordinate": []
                }
            ],
            "esignUrl": "https://api-preproduction.signzy.app/api/v3/contract/esign/3bd160a3-5e52-4ea8-9819-c1fc1e1bd838"
        },
        {
            "signerId": "523af19d-762f-40e1-ae00-dbc46f7ed8b1",
            "signerName": "cdcdc",
            "signatureType": "AADHAARESIGN-OTP",
            "uidLastFourDigits": "1345",
            "signerYearOfBirth": "0101",
            "signatures": [
                {
                    "pageNo": [
                        11,
                        2,
                        5
                    ],
                    "signaturePosition": [
                        "TOPLEFT",
                        "customize"
                    ],
                    "xCoordinate": [
                        300,
                        5
                    ],
                    "yCoordinate": [
                        400,
                        5
                    ]
                },
                {
                    "pageNo": [
                        1,
                        2,
                        5
                    ],
                    "signaturePosition": [
                        "TOPLEFT",
                        "customize"
                    ],
                    "xCoordinate": [
                        300,
                        5
                    ],
                    "yCoordinate": [
                        400,
                        5
                    ]
                }
            ],
            "esignUrl": "https://api-preproduction.signzy.app/api/v3/contract/esign/523af19d-762f-40e1-ae00-dbc46f7ed8b1"
        }
    ],
    "callbackUrl": "https://qa.signzy.xyz/callback/mohitposts",
    "callbackUrlAuthorizationHeader": "test",
    "signerCallbackUrl": "https://qa.signzy.xyz/callback/mohitposts",
    "callbackExtraParameter": [],
    "fileTtl": "2 days",
    "estamp": {
        "type": "eChallan",
        "secondPartyName": "Test",
        "firstPartyName": "VMWARE",
        "stateCode": "NL",
        "articleCode": "NL001",
        "purposeOfStampDuty": "sjs",
        "amount": 1,
        "location": "middleCenter",
        "custmonDefacement": "true",
        "message": "Stamp duty of Rs. 1 paid via Stamp Paper Certificate No. xdxdxcd dated 121212"
    }
}
Output Parameters
Field Name	Description	Mandatory/Optional	Type
emudhraCustomization	Customization options for Emudhra ESP including button color, header color, and logo URL.	Optional	Object
customerId	Unique identifier for the customer.	Mandatory	String
username	Username of the customer.	Mandatory	String
contractId	Unique identifier for the contract.	Mandatory	String
pdf	URL to the PDF document.	Mandatory	String
initialContractHash	Initial hash of the contract.	Mandatory	String
contractName	Name of the contract.	Mandatory	String
contractExecuterName	Name of the contract executor.	Mandatory	String
successRedirectUrl	URL to redirect after successful contract execution.	Mandatory	String
failureRedirectUrl	URL to redirect in case of contract execution failure.	Mandatory	String
eSignProvider	Name of the e-sign provider (e.g., EMUDHRA).	Mandatory	String
contractTtl	Time to live (in milliseconds) for the contract.	Mandatory	Number
signerdetail	Details of signers for the contract.	Mandatory	Array of Objects
callbackUrl	URL for callback upon contract completion.	Mandatory	String
callbackUrlAuthorizationHeader	Authorization header for the callback URL.	Optional	String
signerCallbackUrl	URL for individual signer callbacks.	Optional	String
callbackExtraParameter	Extra parameters for the callback.	Optional	Array of Strings
fileTtl	Time to live for the uploaded files (e.g., "2 days").	Mandatory	String
estamp	Electronic stamp details for the contract.	Optional	Object
SignerDetail:
Field Name	Description	Mandatory/Optional	Type
signerId	Unique identifier for the signer.	Mandatory	String
signerName	Name of the signer.	Mandatory	String
signatureType	Type of signature for the signer.	Mandatory	String
uidLastFourDigits	Last four digits of signer's UID.	Mandatory	String
signerYearOfBirth	Year of birth of the signer.	Mandatory	String
signerGender	Gender of the signer.	Mandatory	String
signatures	Signature details for the signer.	Mandatory	Array of Objects
esignUrl	URL for the e-sign process of the signer.	Mandatory	String
Signature (within SignerDetail):
Field Name	Description	Mandatory/Optional	Type
pageNo	Page number(s) for the signature.	Mandatory	Array
signaturePosition	Position(s) of the signature on the page.	Mandatory	Array of Strings
xCoordinate	X-coordinate(s) of the signature.	Optional	Array of Numbers
yCoordinate	Y-coordinate(s) of the signature.	Optional	Array of Numbers
eStamp:
Field Name	Description	Mandatory/Optional	Type
type	Type of electronic stamp (e.g., eChallan).	Mandatory	String
secondPartyName	Name of the second party in the stamp.	Mandatory	String
firstPartyName	Name of the first party in the stamp.	Mandatory	String
stateCode	State code for the stamp.	Mandatory	String
articleCode	Article code for the stamp.	Mandatory	String
purposeOfStampDuty	Purpose of the stamp duty.	Mandatory	String
amount	Amount of stamp duty.	Mandatory	Number
location	Location of the stamp on the document.	Mandatory	String
custmonDefacement	Customization option for defacement.	Mandatory	String
message	Message for the stamp.	Mandatory	String
Sample Errors
0

{
    "name": "error",
    "message": "Invalid URL extension",
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
500	Internal Server Error	Internal error at Signzy, Please reach out to help@signzy.com