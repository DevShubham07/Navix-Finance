import { describe, expect, it } from "vitest";
import { flattenProviderReport } from "./provider-report";

describe("flattenProviderReport", () => {
  it("keeps every nested, array, null, and empty provider field", () => {
    const rows = flattenProviderReport({
      Header: { ReportDate: "20260809", MessageText: null },
      CAIS_Account: {
        CAIS_Account_DETAILS: [
          {
            Subscriber_Name: "TEST BANK",
            Payment_History_Profile: "000000",
            CAIS_Account_History: [{ Days_Past_Due: "0" }],
            Special_Comment: "",
          },
        ],
      },
      Empty_Array: [],
    });

    expect(rows).toEqual([
      { path: "Header > Report Date", value: "20260809" },
      { path: "Header > Message Text", value: "null" },
      { path: "CAIS Account > CAIS Account DETAILS [1] > Subscriber Name", value: "TEST BANK" },
      { path: "CAIS Account > CAIS Account DETAILS [1] > Payment History Profile", value: "000000" },
      { path: "CAIS Account > CAIS Account DETAILS [1] > CAIS Account History [1] > Days Past Due", value: "0" },
      { path: "CAIS Account > CAIS Account DETAILS [1] > Special Comment", value: '""' },
      { path: "Empty Array", value: "[]" },
    ]);
  });
});
