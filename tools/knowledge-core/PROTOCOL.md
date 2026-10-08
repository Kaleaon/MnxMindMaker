# Knowledge contribution protocol v1

The executable validator is `mnx_knowledge/store.py`. Unknown fields are rejected.
All numbers must be finite. IDs and required strings must be nonblank.
`examples/seed.json` is a complete, valid example.

## Envelope

```json
{
  "schema_version": 1,
  "id": "adapter-run-unique-id",
  "base_revision": 0,
  "contributor": {
    "id": "adapter-or-human-id",
    "method": "extraction method or prompt version",
    "model": "optional exact model/version"
  },
  "changes": {
    "concepts": [],
    "sources": [],
    "spaces": [],
    "claims": [],
    "observations": []
  }
}
```

`base_revision` must match the current database revision on submission and
acceptance. Omit empty collections if desired; at least one change is required.
IDs must be unique within each collection in a proposal. All references must
resolve to accepted records or other records in that same proposal.

Repeating the same proposal ID with identical content is an idempotent retry.
Changing its content requires a new ID. Only concepts can be replaced in place;
their old values remain in earlier accepted proposals. Other records are immutable:
an identical record may be resubmitted, but changing it requires a new ID.

## Records

| Collection | Required fields | Optional fields |
|---|---|---|
| `concepts` | `id`, `label` | `aliases`: array of strings |
| `sources` | `id`, `kind`, `title`, `uri` | `content_sha256`: 64 lowercase hex characters |
| `spaces` | `id`, `model`, `dimensions`: positive integer | None |
| `claims` | `id`, `subject_id`, `predicate`, `object`, `evidence`, `confidence` | `supersedes`, `contradicts`, `derived_from`: arrays of claim IDs |
| `observations` | `id`, `concept_id`, `modality`, `source_id`, `locator`, `features` | `embedding` |

Source `kind`: `paper`, `book`, `video`, `image`, `audio`, `dataset`, `experience`,
or `web`. A URI is a reference, not an instruction to fetch content. An optional
digest is recorded as supplied; the core does not fetch a source to verify it.

Claim `object` contains exactly one of `{"concept_id":"..."}` or
`{"literal":...}`; a literal is any non-null finite JSON value. `confidence`
is a number in `[0,1]`. `evidence` is a nonempty array of
`{"source_id":"...","locator":"page, timestamp, region, or measurement"}`.
Predicates are open strings, for example `is_a`, `has_part`, `sounds_like`,
`associated_with`, and `summary`. They are not automatically inferred or verified.

Condensation uses a new `summary` claim with original source evidence and
optional `derived_from` links to the claims it condenses. The caller supplies
the summary. Earlier assertions and source records are retained. Link presence
is checked; the core does not perform logical contradiction detection or establish
whether the linked claim supports the summary.

Observation `modality`: `text`, `image`, `audio`, `video`, `chemical`, `shape`,
or `experience`. `features` is a JSON object; adapters must state units,
extraction method, and measurement details where relevant.
`embedding` is `{"space_id":"...","values":[...]}`. Its space must be registered,
its length must equal `dimensions`, and it must be finite and nonzero. A space
ID identifies the full encoder/model configuration; create a new space ID after
changing preprocessing, model weights, or dimensionality. The `model` string is
metadata, not an automatically loaded model.

## Review behavior

Submitting never changes the accepted graph. Acceptance revalidates all changes
and commits them atomically with one increasing revision number. Rejection retains
the proposal with a review reason and leaves the graph unchanged. CLI filesystem
access is the trust boundary in v0.1; reviewer identity authentication is not provided.

Concept replacement supplies the complete record, including any aliases to retain.
Assertions are never silently overwritten: use a fresh claim ID and optional
`supersedes` or `contradicts` links. Superseded claims remain visible; consumers
must decide which claims to use. There is no automatic active-claim resolution.
