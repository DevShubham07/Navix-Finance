# UltronSMS send — working curl structure

The curl below is the verified send structure. **Only `DLTTemplateId`, `peid`, `route`,
and `APIKey` are fixed and do NOT change** — everything else (`number`, `text`) varies per
message. The `text` MUST exactly match the approved DLT template registered for that
`DLTTemplateId`, or the gateway returns ErrorCode 006 "Invalid template text".

Fixed values (do not change):
- APIKey       : b12b3f4ce9c74a64aa5bdf1b060bacc4
- peid         : 1701178039634361131
- route        : 2
- DLTTemplateId: 1707178188933037949   (per-template — pick the one whose approved text you send)

```bash
curl -sS -L -G "https://ultronsms.com/api/mt/SendSMS" \
  --data-urlencode "APIKey=b12b3f4ce9c74a64aa5bdf1b060bacc4" \
  --data-urlencode "senderid=WEBSMS" \
  --data-urlencode "channel=Promo" \
  --data-urlencode "DCS=0" \
  --data-urlencode "flashsms=0" \
  --data-urlencode "number=91XXXXXXXXXX" \
  --data-urlencode "text=<EXACT APPROVED TEMPLATE TEXT>" \
  --data-urlencode "route=2" \
  --data-urlencode "peid=1701178039634361131" \
  --data-urlencode "DLTTemplateId=1707178188933037949"
```

Notes:
- Use `https://` — plain `http://` 301-redirects via Cloudflare and drops the params.
- `number` needs the `91` country prefix (e.g. 917417682036).
- Success = `{"ErrorCode":"000"|"0", "JobId":"..."}`; 006 = text≠template.

---

https://smartping.live/entity/login
creds: info@navixfinance.com -> Navix@2026

Below are your demo account login details.

Service : Bulk SMS Service – USER Panel  
URL : https://ultronsms.com/Account/Login
Username : Navixdemo
password : Hndh@3

flow works like that we get template id from https://smartping.live/entity/content-form after login and clicking eye symbol and get template id from there first make a list of all the sms tempalte with tempalte id, and map it in the code then i have these credentials and here is the api key 

here is the api key page https://ultronsms.com/Web/WebAPI/WEBAPIHTTP.aspx and here i sthe api key 


API Key6XChYwzuj0eJHXxNiI1BCA	
API Error Code
Erro Code	Description
000	Done
001	login details cannot be blank
003	sender cannot be blank
004	message text cannot be blank
005	message data cannot be blank
006	error: generic error description
007	username or password is invalid
008	account not active
009	account locked, contact your account manager
010	api restriction
011	ip address restriction
012	invalid length of message text
013	mobile numbers not valid
014	account locked due to spam message contact support
015	senderid not valid
017	groupid not valid
018	multi message to group is not supported
019	schedule date is not valid
020	message or mobile number cannot be blank
021	insufficient credits
022	invalid jobid
023	parameter missing
024	invalid template or template mismatch
025	{Field} can not be blank or empty
026	invalid date range
027	invalid optin user
028	Invalid Data
029	Email can not be blank
030	Password can not be blank
031	Username can not be blank
032	Mobile Number can not be blank
033	UserName Already Exist
034	Mobile Number Already Exist
035	EmailId Already Exist
036	EmailId can not be Blank