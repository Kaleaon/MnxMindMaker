# Epistemic attributes v1

Keep one native map. Store node classification in string-valued `attributes`;
arrays/objects use JSON strings. Preserve unknown properties on browser round trips.

`claim_kind`: index, definition, fact, theorem, conjecture, belief, hypothesis,
proposal, interpretation. A belief records `belief_holder`. Hypotheses are testable
explanations; conjectures are explicitly open mathematical claims.

`verification_status`: unchecked, source_attributed, corroborated, computed_locally,
established_in_literature, proof_claimed_verification_pending, independently_verified,
disputed, refuted, superseded, conditional, not_applicable.

`evidence_status` describes the support actually present. `evidence_json` contains
source references, locators, their role and whether exact support was checked.
Further-reading links are not automatically evidence for the precise statement.
Use `scope`, `attribution`, `valid_from`, `valid_until`, `last_reviewed_at`, `reviewer`
and `history_json` to retain assumptions, accountability and changes.

`failure_classification`: none_detected, unsupported, fabricated_citation,
invented_quotation, source_mismatch. None detected is not a certification.
"Hallucination" requires documented failure evidence, not just absence of support.
Do not confuse an unchecked manuscript proof with an unproved conjecture.

Relationships support explicit typed links including `supports`, `contradicts`, `depends_on`, `believed_by`, `supersedes`, `evidence`, `has_claim`, `observes`, `observed_in`, and default `relates_to`. Native edges carry key-value attribute maps (`MindEdge.attributes`) so per-edge evidence records, citations, and review metadata are stored directly on edge instances rather than relying solely on indirect JSON blobs. Every relation has its own verification and support.

Standard edge attribute keys include:
- `evidence_status`: `unchecked`, `source_attributed`, `corroborated`, `computed_locally`, `established_in_literature`, `disputed`, `refuted`, `superseded`.
- Citations & Locators: `citation`, `evidence_locator`, `source_id`, `source_uri`, `evidence_json`.
- Review History: `last_reviewed_at`, `reviewer`, `review_history`, `history_json`, `review_reason`.
- Relational Metadata: `confidence`, `claim_id`, `predicate`.

Organizational inclusion carries no entailment. Added edges remain unchecked until recorded and reviewed; missing evidence records imply unchecked.

The knowledge-core claim/entity contract remains unchanged. Map classifications
are authoring metadata, not accepted core revisions or proof certificates.

Content retention: internal `mnx://node/<id>` references resolve inside the same
map. Data payloads use `embedded_data_json`; source reading text uses
`embedded_text`; original PDFs use `embedded_pdf_base64`, with name and SHA-256
attributes. External provenance URLs do not replace retained data. The inspector
reads embedded text and exports checksum-verified PDFs without fetching them.
