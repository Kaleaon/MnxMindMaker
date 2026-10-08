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

Relationships support `supports`, `contradicts`, `depends_on`, `believed_by` and
`supersedes`. The canonical map stores per-edge records in the string-valued
`metadata.relationship_evidence_json`, keyed by edge ID, because native edges do
not have a portable attribute map. Every relation has its own verification and
support. Organizational inclusion carries no entailment. Added edges remain
unchecked until recorded and reviewed; missing evidence records imply unchecked.

The knowledge-core claim/entity contract remains unchanged. Map classifications
are authoring metadata, not accepted core revisions or proof certificates.
