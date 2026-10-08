# Mnx Drive map editor

A static browser editor for native `.mnxj` files in shared Google Drive folders.
The existing SQLite knowledge core is unchanged. Drive edits do not write back
accepted knowledge revisions.

Connect Google Drive, paste a shared folder URL, select a `.mnxj` map, edit its
concepts/connections and save back to the same file. New/imported maps save in
the folder. Sharing copies an editor link; default preserves existing Drive
permissions. Explicit selection can grant anyone-with-link view/edit if permitted.
Read-only users can inspect and download, but not change the open Drive map.
Every read and write is authorized by Google, using the visitor's own account.

## Google setup

See [setup instructions](dist/setup.html). Enable Google Drive API, configure
Google Auth Platform and create a Web application OAuth client with authorized
JavaScript origin `https://kaleaon.github.io` (no path). Add named test users for
the first rollout. A Drive connector in this chat does not supply credentials to
the separately hosted browser app. No OAuth client secret or Picker key is needed.

This folder-first workflow uses restricted `https://www.googleapis.com/auth/drive`:
`drive.file` does not authorize existing children merely because a folder was
selected. Public distribution may require Google verification and a security
assessment. The token stays in tab memory, is sent only to Google, and is never
stored in browser storage or sent to GitHub. Browser storage holds only an
optional public-client-ID setting.

Set `googleClientId` in `docs/config.js` to the public web client ID once for all
visitors. Never supply a client secret, access token or refresh token. For a local
test, use Settings and authorize `http://localhost:8000`.

## GitHub Pages: main/docs

The site publishes from the `docs/` directory on `main`. Existing Markdown
documentation stays alongside the editor. No switch to an Actions deployment or
separate Pages branch is needed. `npm run sync-docs` copies the editor assets to
`docs/`, preserving site-specific `docs/config.js`. Commit source and generated
assets together. CI verifies they match (excluding the intentional config override).
The published files contain no SQLite databases or private Drive map contents.

```sh
cd tools/map-editor
npm test
npm run check
python -m http.server 8000 --directory dist
```

Open http://localhost:8000. There are no npm dependencies or build step.

## Format and limits

Supports mnx.interchange major 1 with min reader 1.0. IDs, attributes, dimensions,
unknown fields and envelope metadata are preserved. Node deletion removes
connected edges and clears children's parent_id. Node positions, labels, types,
descriptions and connections are editable; the attribute/dimension inspector is
read-only to preserve provenance. Existing Drive filenames stay unchanged when
the graph title changes.

Manual saves check Drive versions and require an ETag for If-Match. If Drive does
not expose the token, saving fails closed: download the draft instead. Saved
content is read back before success is reported. Writes are never retried
automatically; an interrupted request may already have committed, so reload
before retrying. Requests time out after 30 seconds.

Local recovery stores the latest and previous valid draft in IndexedDB (with a localStorage fallback),
without OAuth tokens. On reload the editor offers recovery. Storage denial or
quota exhaustion produces a persistent download warning. Recovery is local to
this browser and origin, not an off-site backup. Edits checkpoint after 250 ms
and on visibility/page changes; abrupt termination before a checkpoint can lose
the latest edits.

Parent cycles and invalid native attributes/dimensions are rejected. Editing a
statement with epistemic schema v1 resets its verification to unchecked while
preserving provenance and recording the previous statement in history.
Limits: 64 MiB, 10,000 nodes, 30,000 connections. Folder listing follows pagination
and supports resource keys/shared-drive flags. A map can open even if the visitor
cannot browse its parent folder. Optional browser WebMCP has one read-only tool.
