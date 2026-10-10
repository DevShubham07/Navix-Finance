import type { JsonValue } from "@/lib/credit/provider-report";

/** Minimal Digitap Experian shape (no real report is safe to copy into the repo). */
export const EXPERIAN: JsonValue = {
  result: {
    result_json: {
      INProfileResponse: {
        Header: { ReportDate: "20260809" },
        SCORE: { BureauScore: "742" },
        Current_Application: { Current_Application_Details: { Current_Other_Details: { Income: "0", Employment_Status: "" } } },
        CAIS_Account: {
          CAIS_Account_DETAILS: [
            {
              Subscriber_Name: "SAMPLE NBFC LTD", Account_Type: "05", Identification_Number: "NBFXXXXXXXX",
              AccountHoldertypeCode: "1", Account_Status: "11", Open_Date: "20260110", Date_Closed: "", Date_Reported: "20260731",
              Occupation_Code: "S",
              CAIS_Account_History: [
                { Year: "2026", Month: "07", Days_Past_Due: "15" },
                { Year: "2026", Month: "6", Days_Past_Due: "0" },
              ],
            },
            {
              Subscriber_Name: "SAMPLE BANK", Account_Type: "10", Identification_Number: "PVTXXXXXXXX",
              AccountHoldertypeCode: "7", Account_Status: "13", Open_Date: "20200101", Date_Closed: "20210101", Date_Reported: "20210131",
              Payment_History_Profile: "000",
            },
          ],
        },
      },
    },
  },
};
