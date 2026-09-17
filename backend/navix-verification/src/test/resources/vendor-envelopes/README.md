# Vendor response envelopes (redacted)

Real production response bodies, copied out of `provider_api_execution` during the
2026-09-17 vendor-API failure investigation and **redacted**: names, PANs, mobiles, dates of birth,
order/report/transaction ids and CRIF option lists carrying real lender relationships have been
replaced with synthetic equivalents. The *shapes* — key names, nesting, value formats — are verbatim,
which is the whole point: several of these were parsed wrongly precisely because nobody had the real
shape to hand. Vendor documentation does not cover most of them.

Each file is named `<provider>-<what>-<outcome>.json`. Tests load them from the classpath rather than
embedding the JSON inline, so a shape can be corrected in one place when a vendor changes it.
