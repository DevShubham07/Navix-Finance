/** Common Indian salary-account banks. Keep this list alphabetical for predictable search results. */
export const INDIAN_BANKS = [
  "AU Small Finance Bank",
  "Axis Bank",
  "Bandhan Bank",
  "Bank of Baroda",
  "Bank of India",
  "Bank of Maharashtra",
  "Canara Bank",
  "Central Bank of India",
  "City Union Bank",
  "DBS Bank India",
  "DCB Bank",
  "Federal Bank",
  "HDFC Bank",
  "ICICI Bank",
  "IDBI Bank",
  "IDFC FIRST Bank",
  "Indian Bank",
  "Indian Overseas Bank",
  "IndusInd Bank",
  "Jammu & Kashmir Bank",
  "Karnataka Bank",
  "Karur Vysya Bank",
  "Kotak Mahindra Bank",
  "Punjab & Sind Bank",
  "Punjab National Bank",
  "RBL Bank",
  "South Indian Bank",
  "State Bank of India",
  "Tamilnad Mercantile Bank",
  "UCO Bank",
  "Union Bank of India",
  "YES BANK",
].sort((a, b) => a.localeCompare(b));

export function isListedIndianBank(value: string): boolean {
  return INDIAN_BANKS.includes(value.trim());
}
